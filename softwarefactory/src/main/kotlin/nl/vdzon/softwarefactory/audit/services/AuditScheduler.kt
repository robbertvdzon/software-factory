package nl.vdzon.softwarefactory.audit.services

import nl.vdzon.softwarefactory.audit.AuditGateway
import nl.vdzon.softwarefactory.audit.models.AuditDispatchHandle
import nl.vdzon.softwarefactory.audit.models.AuditOutcome
import nl.vdzon.softwarefactory.audit.repositories.AuditJobStatus
import nl.vdzon.softwarefactory.audit.repositories.AuditRunJobRecord
import nl.vdzon.softwarefactory.audit.repositories.AuditRunJobRepository
import nl.vdzon.softwarefactory.audit.repositories.AuditRunKind
import nl.vdzon.softwarefactory.audit.repositories.AuditRunRecord
import nl.vdzon.softwarefactory.audit.repositories.AuditRunRepository
import nl.vdzon.softwarefactory.audit.repositories.AuditRunStatus
import nl.vdzon.softwarefactory.audit.repositories.AuditReportRepository
import nl.vdzon.softwarefactory.audit.repositories.AuditSettingsRepository
import nl.vdzon.softwarefactory.config.time.FactoryTime
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Restart-veilige audit-scheduler. Draait elke ~30s, leest de hele run-status uit de DB (geen
 * in-memory state) en laat [AuditPlanner] de acties bepalen; deze klasse voert ze enkel uit tegen
 * de repositories en de [AuditGateway]. Zelfde opzet als
 * [nl.vdzon.softwarefactory.nightly.services.NightlyScheduler]; het enige structurele verschil zit
 * in [createRunWithJobs]: niet alle enabled audits, maar precies 1 per project — degene met de
 * oudste laatste-rapport-timestamp (nooit gedraaid = oudste).
 */
@Component
class AuditScheduler(
    private val settingsRepository: AuditSettingsRepository,
    private val runRepository: AuditRunRepository,
    private val jobRepository: AuditRunJobRepository,
    private val reportRepository: AuditReportRepository,
    private val factoryTime: FactoryTime,
    private val gateway: AuditGateway,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${sf.audit.tick-ms:30000}",
        initialDelayString = "\${sf.audit.initial-delay-ms:30000}",
    )
    fun tick() {
        try {
            runOnce()
        } catch (exception: Exception) {
            logger.warn("Audit scheduler tick faalde.", exception)
        }
    }

    /** Eén reconciliation-stap; public zodat tests 'm deterministisch kunnen aanroepen. */
    fun runOnce() {
        val settings = settingsRepository.read()
        val nlToday = factoryTime.nlToday()
        val run = runRepository.activeRun()
        val jobs = run?.let { jobRepository.forRun(it.id) } ?: emptyList()
        val outcomes = jobs
            .filter { it.status == AuditJobStatus.RUNNING && it.containerName != null }
            .associate { it.id to safeOutcome(it) }
            .filterValues { it != null }
            .mapValues { it.value!! }

        val input = AuditPlannerInput(
            settings = settings,
            run = run,
            jobs = jobs,
            outcomes = outcomes,
            startReached = factoryTime.hasReached(nlToday, settings.startTime),
            scheduledRunExistsToday = runRepository.hasScheduledRunOn(nlToday),
        )

        for (action in AuditPlanner.plan(input)) {
            execute(action, run, jobs, nlToday)
        }
    }

    private fun execute(
        action: AuditAction,
        run: AuditRunRecord?,
        jobs: List<AuditRunJobRecord>,
        nlToday: LocalDate,
    ) {
        when (action) {
            is AuditAction.CreateRun -> createRunWithJobs(nlToday, AuditRunKind.SCHEDULED)
            is AuditAction.StartJob -> jobs.firstOrNull { it.id == action.jobId }?.let { startJob(it) }
            is AuditAction.MarkJobTerminal ->
                jobRepository.markTerminal(action.jobId, action.status, now(), action.reportId, action.error)
            is AuditAction.EndRun -> run?.let { runRepository.updateStatus(it.id, AuditRunStatus.ENDED, now()) }
        }
    }

    /** Maakt een nieuwe run aan en seedt 'm met, per project, de enabled audit met de oudste historie. */
    private fun createRunWithJobs(nlToday: LocalDate, kind: String) {
        val run = runRepository.create(nlToday, now(), AuditRunStatus.RUNNING, kind)
        // Alleen seeden als de run nog leeg is (voorkomt dubbele jobs bij een race/herhaling).
        if (jobRepository.forRun(run.id).isNotEmpty()) return
        val allJobs = runCatching { gateway.allJobs() }.getOrElse {
            logger.warn("Audit: kon de audits niet lezen voor run ${run.id}.", it)
            emptyList()
        }
        val chosen = allJobs
            .filter { it.enabled }
            .groupBy { it.project }
            .mapNotNull { (_, projectJobs) -> oldestAudit(projectJobs) }
            .sortedBy { it.project.lowercase() }
        chosen.forEach { jobRepository.add(run.id, it.project, it.name, it.title) }
        logger.info("Audit run ${run.id} aangemaakt voor $nlToday met ${chosen.size} audit(s) (1 per project).")
    }

    /** De enabled audit van dit project met de oudste `lastGeneratedAt` (nooit gedraaid = oudste). */
    private fun oldestAudit(projectJobs: List<AuditJob>): AuditJob? =
        projectJobs.minByOrNull { reportRepository.lastGeneratedAt(it.project, it.name) ?: OffsetDateTime.MIN }

    /** Dispatcht de auditor-agent voor een pending job en zet 'm op running. */
    private fun startJob(job: AuditRunJobRecord) {
        runCatching { gateway.startAudit(job.project, job.auditType) }
            .onSuccess { handle ->
                jobRepository.markRunning(job.id, handle.containerName, handle.workspacePath, handle.storyRunId, now())
                logger.info("Audit ${job.project}/${job.auditType} gestart als container ${handle.containerName}.")
            }
            .onFailure { error ->
                jobRepository.markTerminal(job.id, AuditJobStatus.FAILED, now(), error = "Audit starten faalde: ${error.message}")
                logger.warn("Audit ${job.project}/${job.auditType} kon niet starten.", error)
            }
    }

    private fun safeOutcome(job: AuditRunJobRecord): AuditOutcome? {
        val handle = AuditDispatchHandle(
            containerName = job.containerName ?: return null,
            workspacePath = job.workspacePath,
            storyRunId = job.storyRunId ?: return null,
        )
        return runCatching { gateway.auditOutcome(handle) }.getOrNull()
    }

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(factoryTime.now(), ZoneOffset.UTC)
}
