package nl.vdzon.softwarefactory.audit.services

import nl.vdzon.softwarefactory.audit.AuditGateway
import nl.vdzon.softwarefactory.audit.models.AuditDispatchHandle
import nl.vdzon.softwarefactory.audit.models.AuditOutcome
import nl.vdzon.softwarefactory.audit.repositories.AuditJobStatus
import nl.vdzon.softwarefactory.audit.repositories.AuditProjectSettings
import nl.vdzon.softwarefactory.audit.repositories.AuditProjectSettingsRepository
import nl.vdzon.softwarefactory.audit.repositories.AuditRunJobRecord
import nl.vdzon.softwarefactory.audit.repositories.AuditRunJobRepository
import nl.vdzon.softwarefactory.audit.repositories.AuditRunKind
import nl.vdzon.softwarefactory.audit.repositories.AuditRunRecord
import nl.vdzon.softwarefactory.audit.repositories.AuditRunRepository
import nl.vdzon.softwarefactory.audit.repositories.AuditRunStatus
import nl.vdzon.softwarefactory.audit.repositories.AuditReportRepository
import nl.vdzon.softwarefactory.audit.repositories.AuditSettings
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
 * in [seedProject]: niet alle enabled audits, maar de N oudste per project (default 1, per project
 * instelbaar via [AuditProjectSettingsRepository]) — en elk project seedt op z'n eigen starttijd
 * (globale [AuditSettings.startTime] als fallback), zie [pendingProjects].
 */
@Component
class AuditScheduler(
    private val settingsRepository: AuditSettingsRepository,
    private val projectSettingsRepository: AuditProjectSettingsRepository,
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

    /**
     * Start direct één specifieke audit ("Run now"-knop in het dashboard), buiten de nachtelijke
     * ronde om. Lukt alleen als er geen run loopt (zelfde invariant als de scheduler: hoogstens 1
     * actieve run tegelijk); de gekozen audit hoeft niet `enabled` te zijn. Eenmaal aangemaakt
     * pakt de eerstvolgende [runOnce]-tick 'm op net als een gewone geplande job.
     */
    fun startManualAudit(project: String, auditType: String): Boolean {
        if (runRepository.activeRun() != null) return false
        val job = runCatching { gateway.allJobs() }
            .getOrElse {
                logger.warn("Audit: kon de audits niet lezen voor handmatige start.", it)
                emptyList()
            }
            .firstOrNull { it.project == project && it.name == auditType } ?: return false
        val run = runRepository.create(factoryTime.nlToday(), now(), AuditRunStatus.RUNNING, AuditRunKind.MANUAL)
        jobRepository.add(run.id, project, auditType, job.title)
        logger.info("Audit run ${run.id} handmatig gestart voor $project/$auditType.")
        return true
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

        // MANUAL-runs blijven beperkt tot de ene expliciet aangevraagde audit — geen per-project
        // cascade (zie startManualAudit); alleen SCHEDULED-runs (en de "nog geen run"-situatie
        // ervoor) krijgen pendingProjects.
        val pendingProjects = if (run == null || run.kind == AuditRunKind.SCHEDULED) {
            pendingProjects(nlToday, settings, jobs)
        } else {
            emptyMap()
        }

        val input = AuditPlannerInput(
            settings = settings,
            run = run,
            jobs = jobs,
            outcomes = outcomes,
            scheduledRunExistsToday = runRepository.hasScheduledRunOn(nlToday),
            pendingProjects = pendingProjects,
        )

        for (action in AuditPlanner.plan(input)) {
            execute(action, run, jobs, nlToday)
        }
    }

    /**
     * Projecten die (nog) niet geseed zijn in de huidige run — of, vóór er een run is, alle
     * kandidaten — met of hun (per-project, anders globale) starttijd al bereikt is. Een project
     * zonder enabled audits of met audit_count 0 telt niet mee (zou anders nooit "geseed" raken en
     * de run laten hangen).
     */
    private fun pendingProjects(nlToday: LocalDate, settings: AuditSettings, jobs: List<AuditRunJobRecord>): Map<String, Boolean> {
        val allJobs = runCatching { gateway.allJobs() }.getOrElse {
            logger.warn("Audit: kon de audits niet lezen voor de pending-projectbepaling.", it)
            emptyList()
        }
        val projectSettings = runCatching { projectSettingsRepository.all() }.getOrElse { emptyList() }.associateBy { it.project }
        val seeded = jobs.map { it.project }.toSet()
        val eligible = allJobs.filter { it.enabled }.map { it.project }.toSet()
            .filter { auditCountFor(it, projectSettings) > 0 }
        return eligible.filter { it !in seeded }.associateWith { project ->
            factoryTime.hasReached(nlToday, startTimeFor(project, settings, projectSettings))
        }
    }

    private fun startTimeFor(project: String, settings: AuditSettings, projectSettings: Map<String, AuditProjectSettings>) =
        projectSettings[project]?.startTime ?: settings.startTime

    private fun auditCountFor(project: String, projectSettings: Map<String, AuditProjectSettings>) =
        projectSettings[project]?.auditCount ?: AuditProjectSettings.DEFAULT_AUDIT_COUNT

    private fun execute(
        action: AuditAction,
        run: AuditRunRecord?,
        jobs: List<AuditRunJobRecord>,
        nlToday: LocalDate,
    ) {
        when (action) {
            is AuditAction.CreateRun -> runRepository.create(nlToday, now(), AuditRunStatus.RUNNING, AuditRunKind.SCHEDULED)
            is AuditAction.SeedProject -> run?.let { seedProject(it, action.project) }
            is AuditAction.StartJob -> jobs.firstOrNull { it.id == action.jobId }?.let { startJob(it) }
            is AuditAction.MarkJobTerminal ->
                jobRepository.markTerminal(action.jobId, action.status, now(), action.reportId, action.error)
            is AuditAction.EndRun -> run?.let { runRepository.updateStatus(it.id, AuditRunStatus.ENDED, now()) }
        }
    }

    /** Kiest en seedt de N oudste enabled audits van dit project (N = audit_count, default 1). */
    private fun seedProject(run: AuditRunRecord, project: String) {
        // Alleen seeden als dit project nog geen job in deze run heeft (voorkomt dubbele jobs bij
        // een race/herhaling — pendingProjects is per tick opnieuw berekend, maar StartJob/etc.
        // draaien allemaal in dezelfde tick op de jobs-snapshot van vóór deze actie).
        if (jobRepository.forRun(run.id).any { it.project == project }) return
        val allJobs = runCatching { gateway.allJobs() }.getOrElse {
            logger.warn("Audit: kon de audits niet lezen om project $project te seeden.", it)
            emptyList()
        }
        val count = runCatching { projectSettingsRepository.get(project)?.auditCount }.getOrNull()
            ?: AuditProjectSettings.DEFAULT_AUDIT_COUNT
        val chosen = allJobs
            .filter { it.project == project && it.enabled }
            .sortedBy { reportRepository.lastGeneratedAt(it.project, it.name) ?: OffsetDateTime.MIN }
            .take(count)
        chosen.forEach { jobRepository.add(run.id, it.project, it.name, it.title) }
        logger.info("Audit run ${run.id}: project $project geseed met ${chosen.size} audit(s) (van de $count gevraagd).")
    }

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
