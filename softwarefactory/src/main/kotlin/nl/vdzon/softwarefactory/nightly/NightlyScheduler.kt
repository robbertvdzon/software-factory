package nl.vdzon.softwarefactory.nightly

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Restart-veilige nachtelijke scheduler. Draait elke ~30s, leest de hele run-status uit de DB (geen
 * in-memory state) en laat [NightlyPlanner] de acties bepalen; deze klasse voert ze enkel uit tegen de
 * repositories en de [NightlyGateway]. Door de scheiding plan/uitvoer is een rest-restart vanzelf veilig:
 * de volgende tick haalt de lopende run opnieuw op en pikt 'm op zonder dubbele stories.
 */
@Component
class NightlyScheduler(
    private val settingsRepository: NightlySettingsRepository,
    private val runRepository: NightlyRunRepository,
    private val jobRepository: NightlyRunJobRepository,
    private val nightlyTime: NightlyTime,
    private val gateway: NightlyGateway,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${sf.nightly.tick-ms:30000}", initialDelayString = "\${sf.nightly.initial-delay-ms:30000}")
    fun tick() {
        try {
            runOnce()
        } catch (exception: Exception) {
            logger.warn("Nightly scheduler tick faalde.", exception)
        }
    }

    /** Eén reconciliation-stap; public zodat tests 'm deterministisch kunnen aanroepen. */
    fun runOnce() {
        val settings = settingsRepository.read()
        val nlToday = nightlyTime.nlToday()
        // De relevante run: de actieve (status != ended), anders de run van vandaag (kan ended zijn).
        val run = runRepository.activeRun() ?: runRepository.forDate(nlToday)
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
        )

        for (action in NightlyPlanner.plan(input)) {
            execute(action, run, jobs, nlToday)
        }
    }

    private fun execute(action: NightlyAction, run: NightlyRunRecord?, jobs: List<NightlyRunJobRecord>, nlToday: java.time.LocalDate) {
        when (action) {
            is NightlyAction.CreateRun -> createRunWithJobs(nlToday)
            is NightlyAction.StartJob -> jobs.firstOrNull { it.id == action.jobId }?.let { startJob(it) }
            is NightlyAction.MarkJobTerminal ->
                jobRepository.markTerminal(action.jobId, action.status, now(), action.error)
            is NightlyAction.SendDigest -> run?.let { sendDigest(it) }
            is NightlyAction.EndRun -> run?.let { runRepository.updateStatus(it.id, NightlyRunStatus.ENDED, now()) }
        }
    }

    /** Maakt de run voor vandaag aan (idempotent op run_date) en vult de queue met de enabled jobs. */
    private fun createRunWithJobs(nlToday: java.time.LocalDate) {
        val run = runRepository.create(nlToday, now(), NightlyRunStatus.RUNNING)
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

    /** Bouwt de digest uit de actuele DB-state, stuurt 'm en zet summary_sent_at + de tekst. */
    private fun sendDigest(run: NightlyRunRecord) {
        val jobs = jobRepository.forRun(run.id)
        // Eén AI-aanroep voor alle afgeronde stories: links + samenvatting van de wijzigingen.
        val refs = jobs
            .filter { it.status == NightlyJobStatus.DONE && it.storyKey != null }
            .map { NightlyChangeRef(it.storyKey!!, it.project, it.title) }
        val details = runCatching { gateway.describeChanges(refs) }
            .onFailure { logger.warn("Nightly: kon de wijzigingen niet samenvatten.", it) }
            .getOrDefault(emptyMap())
        val digestJobs = jobs.map { job ->
            val outcome = job.storyKey?.let { safeOutcome(it) }
            val detail = job.storyKey?.let { details[it] }
            NightlyDigestJob(
                project = job.project,
                jobName = job.jobName,
                title = job.title,
                status = job.status,
                storyKey = job.storyKey,
                youTrackLink = detail?.youTrackUrl,
                changeUrl = detail?.changeUrl,
                startedAt = job.startedAt,
                endedAt = job.endedAt,
                costUsd = outcome?.costUsd ?: 0.0,
                sections = detail?.sections ?: emptyList(),
                note = job.error?.takeIf { job.status == NightlyJobStatus.FAILED } ?: outcome?.error,
            )
        }
        val text = NightlyDigest.build(run.runDate, run.startedAt, now(), digestJobs)
        runCatching { gateway.sendDigest(text) }
            .onFailure { logger.warn("Nightly digest kon niet naar Telegram worden gestuurd.", it) }
        // summary_sent_at + tekst worden altijd gezet: de digest blijft in de UI zichtbaar en herhaalde
        // ticks versturen niet opnieuw (idempotentie), ongeacht of Telegram het bericht accepteerde.
        runRepository.markSummarySent(run.id, now(), text)
        logger.info("Nightly digest verstuurd voor run ${run.id}.")
    }

    private fun safeOutcome(storyKey: String): NightlyStoryOutcome? =
        runCatching { gateway.storyOutcome(storyKey) }.getOrNull()

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(nightlyTime.now(), ZoneOffset.UTC)
}
