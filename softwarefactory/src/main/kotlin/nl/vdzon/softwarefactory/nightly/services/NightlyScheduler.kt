package nl.vdzon.softwarefactory.nightly.services

import nl.vdzon.softwarefactory.nightly.*
import nl.vdzon.softwarefactory.nightly.models.*
import nl.vdzon.softwarefactory.nightly.types.*
import nl.vdzon.softwarefactory.nightly.services.*
import nl.vdzon.softwarefactory.nightly.repositories.*

import nl.vdzon.softwarefactory.config.time.FactoryTime
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * VERVANGEN door [nl.vdzon.softwarefactory.audit.services.AuditScheduler] — nightly jobs pasten zelf
 * code aan (tot en met automerge/deploy); audits zijn read-only en stellen hoogstens 1 story voor.
 * Zie `.factory/nightly/README.md`.
 *
 * Deze klasse blijft (nog) bestaan voor `runOnce()`/`startManualRun()`/`stopActiveRun()`-tests en
 * omdat `stopActiveRun()` nuttig blijft om een eventuele, van vóór deze migratie nog openstaande
 * run netjes te sluiten — maar `tick()` en `startManualRun()` zijn bewust uitgezet: de content-
 * migratie (SF, audit-redesign) verving `story.md`/`subtasks.yaml` door `prompt.md`, dus een
 * nightly-job die nu nog zou draaien zou een lege/kapotte story aanmaken. Volledige verwijdering van
 * deze klasse (en de bijbehorende dashboard-schermen) is bewust als aparte opruim-stap gelaten i.p.v.
 * in dezelfde wijziging als de audit-invoering, zodat die niet ook nog de bestaande nightly-
 * dashboardpagina's/-bridge-operaties hoeft te slopen.
 */
@Component
class NightlyScheduler(
    private val settingsRepository: NightlySettingsRepository,
    private val runRepository: NightlyRunRepository,
    private val jobRepository: NightlyRunJobRepository,
    private val nightlyTime: FactoryTime,
    private val gateway: NightlyGateway,
) : NightlyControl {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${sf.nightly.tick-ms:30000}",
        initialDelayString = "\${sf.nightly.initial-delay-ms:30000}",
    )
    fun tick() {
        // Uitgezet: zie klasse-KDoc. Was voorheen `runOnce()` (met try/catch); nightly jobs draaien
        // niet meer automatisch — dat doet nu AuditScheduler.
    }

    /** Eén reconciliation-stap; public zodat tests 'm deterministisch kunnen aanroepen. */
    fun runOnce() {
        val settings = settingsRepository.read()
        val nlToday = nightlyTime.nlToday()
        // We reconcilen altijd de actieve run (status != ended); er loopt er hooguit één tegelijk.
        val run = runRepository.activeRun()
        val jobs = run?.let { jobRepository.forRun(it.id) } ?: emptyList()
        val outcomes = jobs
            .filter { it.status == NightlyJobStatus.RUNNING && it.storyKey != null }
            .associate { it.id to safeOutcome(it.storyKey!!) }
            .filterValues { it != null }
            .mapValues { it.value!! }

        val input = NightlyPlannerInput(
            settings = settings,
            run = run,
            jobs = jobs,
            outcomes = outcomes,
            startReached = nightlyTime.hasReached(nlToday, settings.startTime),
            summaryReached = run != null && nightlyTime.hasReached(run.runDate, settings.summaryTime),
            scheduledRunExistsToday = runRepository.hasScheduledRunOn(nlToday),
        )

        for (action in NightlyPlanner.plan(input)) {
            execute(action, run, jobs, nlToday)
        }
    }

    /**
     * Uitgezet: zie klasse-KDoc. Was voorheen de "Run nu"-knop; geeft nu altijd `false` terug
     * (geen run gestart) i.p.v. een kapotte/lege story te maken.
     */
    override fun startManualRun(): Boolean {
        logger.warn("Nightly 'Run nu' genegeerd: nightly jobs zijn vervangen door audits (AuditScheduler).")
        return false
    }

    /**
     * Onderbreekt de lopende run: markeert alle nog niet-terminale jobs als `cancelled` en sluit de run.
     * Een eventueel al-lopende story-agent draait buiten de nightly om door (die wordt hier niet gekild);
     * de queue stopt wel en een nieuwe run kan weer gestart worden. @return false als er geen run liep.
     */
    override fun stopActiveRun(): Boolean {
        val run = runRepository.activeRun() ?: return false
        jobRepository.forRun(run.id)
            .filter { !NightlyJobStatus.isTerminal(it.status) }
            .forEach {
                jobRepository.markTerminal(it.id, NightlyJobStatus.CANCELLED, now(), "Run handmatig onderbroken.")
            }
        runRepository.updateStatus(run.id, NightlyRunStatus.ENDED, now())
        logger.info("Nightly run ${run.id} handmatig onderbroken.")
        return true
    }

    private fun execute(
        action: NightlyAction,
        run: NightlyRunRecord?,
        jobs: List<NightlyRunJobRecord>,
        nlToday: LocalDate,
    ) {
        when (action) {
            is NightlyAction.CreateRun -> createRunWithJobs(nlToday, NightlyRunKind.SCHEDULED)
            is NightlyAction.StartJob -> jobs.firstOrNull { it.id == action.jobId }?.let { startJob(it) }
            is NightlyAction.MarkJobTerminal ->
                jobRepository.markTerminal(action.jobId, action.status, now(), action.error)
            is NightlyAction.SendDigest -> run?.let { sendDigest(it) }
            is NightlyAction.EndRun -> run?.let { runRepository.updateStatus(it.id, NightlyRunStatus.ENDED, now()) }
        }
    }

    /** Maakt een nieuwe run aan en vult de queue met de enabled jobs. */
    private fun createRunWithJobs(nlToday: java.time.LocalDate, kind: String) {
        val run = runRepository.create(nlToday, now(), NightlyRunStatus.RUNNING, kind)
        // Alleen seeden als de run nog leeg is (voorkomt dubbele jobs bij een race/herhaling).
        if (jobRepository.forRun(run.id).isNotEmpty()) return
        val jobs = runCatching { gateway.allJobs() }.getOrElse {
            logger.warn("Nightly: kon de jobs niet lezen voor run ${run.id}.", it)
            emptyList()
        }
        jobs.filter { it.enabled }
            .sortedWith(compareBy({ it.project.lowercase() }, { it.name.lowercase() }))
            .forEach { jobRepository.add(run.id, it.project, it.name, it.title) }
        logger.info("Nightly run ${run.id} aangemaakt voor $nlToday met ${jobs.count { it.enabled }} job(s).")
    }

    /** Start de story voor een pending job en zet 'm op running; een fout markeert alleen deze job failed. */
    private fun startJob(job: NightlyRunJobRecord) {
        runCatching { gateway.startStory(job.project, job.jobName) }
            .onSuccess { storyKey ->
                jobRepository.markRunning(job.id, storyKey, now())
                logger.info("Nightly job ${job.project}/${job.jobName} gestart als $storyKey.")
            }
            .onFailure { error ->
                jobRepository.markTerminal(
                    job.id,
                    NightlyJobStatus.FAILED,
                    now(),
                    "Story aanmaken faalde: ${error.message}",
                )
                logger.warn("Nightly job ${job.project}/${job.jobName} kon niet starten.", error)
            }
    }

    /** Bouwt de digest uit de actuele DB-state, stuurt 'm per project en zet summary_sent_at + de tekst. */
    private fun sendDigest(run: NightlyRunRecord) {
        val digestJobs = buildDigestJobs(run)
        val texts = sendPerProject(run, digestJobs, prefix = null)
        // summary_sent_at + tekst worden altijd gezet: de digest blijft in de UI zichtbaar en herhaalde
        // ticks versturen niet opnieuw (idempotentie), ongeacht of Telegram het bericht accepteerde.
        runRepository.markSummarySent(run.id, now(), texts.joinToString("\n\n"))
        // Ontbreken de AI-details voor afgeronde stories (bv. Claude 5-uurs-limiet op direct na de run)?
        // Markeer de run: een latere, rustigere tick stuurt de details na zodra het budget hersteld is.
        val aiMissing = digestJobs.any {
            it.status == NightlyJobStatus.DONE && it.storyKey != null && it.sections.isEmpty()
        }
        runRepository.setAiDetailPending(run.id, aiMissing)
        logger.info(
            "Nightly digest verstuurd voor run ${run.id} " +
                "(${texts.size} bericht(en), ai-details-pending=$aiMissing).",
        )
    }

    /** Per-job digest-data incl. links + (indien beschikbaar) AI-samenvatting van de wijzigingen. */
    private fun buildDigestJobs(run: NightlyRunRecord): List<NightlyDigestJob> {
        val jobs = jobRepository.forRun(run.id)
        // Eén AI-aanroep (per project, met retries in de adapter) voor alle afgeronde stories.
        val refs = jobs
            .filter { it.status == NightlyJobStatus.DONE && it.storyKey != null }
            .map { NightlyChangeRef(it.storyKey!!, it.project, it.title) }
        val details = runCatching { gateway.describeChanges(refs) }
            .onFailure { logger.warn("Nightly: kon de wijzigingen niet samenvatten.", it) }
            .getOrDefault(emptyMap())
        return jobs.map { job ->
            val outcome = job.storyKey?.let { safeOutcome(it) }
            val detail = job.storyKey?.let { details[it] }
            NightlyDigestJob(
                project = job.project,
                jobName = job.jobName,
                title = job.title,
                status = job.status,
                storyKey = job.storyKey,
                storyLink = detail?.storyLink,
                changeUrl = detail?.changeUrl,
                startedAt = job.startedAt,
                endedAt = job.endedAt,
                costUsd = outcome?.costUsd ?: 0.0,
                sections = detail?.sections ?: emptyList(),
                note = job.error?.takeIf { job.status == NightlyJobStatus.FAILED } ?: outcome?.error,
            )
        }
    }

    /** Stuurt per project één digest-bericht (optioneel met [prefix]); geeft de verstuurde teksten terug. */
    private fun sendPerProject(
        run: NightlyRunRecord,
        digestJobs: List<NightlyDigestJob>,
        prefix: String?,
    ): List<String> {
        val texts = mutableListOf<String>()
        fun send(project: String?, body: String) {
            val text = if (prefix != null) "$prefix\n\n$body" else body
            runCatching { gateway.sendDigest(project, text) }
                .onFailure { logger.warn("Nightly digest voor ${project ?: "default"} kon niet worden gestuurd.", it) }
            texts += text
        }
        if (digestJobs.isEmpty()) {
            send(null, NightlyDigest.build(run.runDate, run.startedAt, now(), emptyList()))
        } else {
            digestJobs.groupBy { it.project }.toSortedMap().forEach { (project, projectJobs) ->
                send(project, NightlyDigest.build(run.runDate, run.startedAt, now(), projectJobs))
            }
        }
        return texts
    }

    /**
     * Uitgestelde AI-verrijking: runs waarvan de digest zonder AI-details ging (bv. Claude-limiet op direct
     * na de run) krijgen later alsnog een aanvulling, zodra het budget hersteld is. Draait los van de
     * hoofd-tick op een rustiger interval zodat we Claude niet blijven hameren.
     */
    @Scheduled(
        fixedDelayString = "\${sf.nightly.ai-retry-ms:1200000}",
        initialDelayString = "\${sf.nightly.ai-retry-initial-delay-ms:120000}",
    )
    fun aiEnrichmentTick() {
        try {
            enrichPendingDigests()
        } catch (exception: Exception) {
            logger.warn("Nightly AI-verrijking-tick faalde.", exception)
        }
    }

    /** Probeert per openstaande run opnieuw de AI-samenvatting; lukt het, stuur de details als aanvulling. */
    fun enrichPendingDigests() {
        for (run in runRepository.pendingAiDetail()) {
            val tooOld = run.startedAt?.let { Duration.between(it, now()).toHours() >= MAX_ENRICH_HOURS } ?: true
            if (tooOld) {
                runRepository.setAiDetailPending(run.id, false)
                logger.info("Nightly: AI-verrijking opgegeven voor run ${run.id} (ouder dan ${MAX_ENRICH_HOURS}u).")
                continue
            }
            val digestJobs = buildDigestJobs(run)
            val hasAi = digestJobs.any {
                it.status == NightlyJobStatus.DONE && it.storyKey != null && it.sections.isNotEmpty()
            }
            if (!hasAi) continue // budget nog steeds op → volgende cyclus opnieuw proberen
            val texts = sendPerProject(run, digestJobs, prefix = "🔁 Nightly digest — AI-details (aanvulling)")
            runRepository.markSummarySent(run.id, now(), texts.joinToString("\n\n"))
            runRepository.setAiDetailPending(run.id, false)
            logger.info("Nightly: AI-details nagestuurd voor run ${run.id} (${texts.size} bericht(en)).")
        }
    }

    private fun safeOutcome(storyKey: String): NightlyStoryOutcome? =
        runCatching { gateway.storyOutcome(storyKey) }.getOrNull()

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(nightlyTime.now(), ZoneOffset.UTC)

    private companion object {
        /** Na zoveel uur stoppen we met de AI-details na te sturen (budget bleef te lang op). */
        const val MAX_ENRICH_HOURS = 12L
    }
}
