package nl.vdzon.softwarefactory.nightly

import nl.vdzon.softwarefactory.config.FactorySecrets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Executor-tests voor [NightlyScheduler] met in-memory fake-repositories + fake-gateway. Toetst de hele
 * keten: idempotente run-creatie, sequentieel/parallel uitvoeren, restart-pickup zonder dubbele stories
 * en digest-idempotentie.
 */
class NightlySchedulerTest {

    // 27-06-2026 03:00 UTC = 05:00 NL → ná start (02:00 NL), vóór summary (07:00 NL).
    private val duringRun = Instant.parse("2026-06-27T03:00:00Z")
    private val afterSummary = Instant.parse("2026-06-27T06:00:00Z") // 08:00 NL → ná summary
    private val today = LocalDate.of(2026, 6, 27)

    private val secrets = FactorySecrets(
        youTrackBaseUrl = "https://yt", youTrackToken = "t", youTrackProjects = emptyList(), githubToken = "g",
        factoryDatabaseUrl = "jdbc:postgresql://db", factoryDatabaseSchema = "s", kubeconfig = null,
        aiCredentialsDir = null, aiOauthToken = null, loadedFrom = "test",
    )

    private val clock = MutableClock(duringRun)
    private val nightlyTime = NightlyTime(clock)
    private val settings = FakeSettingsRepository(NightlySettings(true, java.time.LocalTime.of(2, 0), java.time.LocalTime.of(7, 0)))
    private val runs = FakeRunRepository()
    private val jobs = FakeJobRepository()
    private val gateway = FakeGateway()
    private val scheduler = NightlyScheduler(settings, runs, jobs, nightlyTime, gateway)

    @Test
    fun `creates exactly one run per day and seeds the enabled jobs`() {
        gateway.jobs = listOf(
            njob("alpha", "lint", enabled = true),
            njob("alpha", "test", enabled = true),
            njob("beta", "deps", enabled = true),
            njob("gamma", "skip", enabled = false), // disabled → niet in de queue
        )

        scheduler.runOnce()

        val run = runs.forDate(today)
        assertNotNull(run)
        val seeded = jobs.forRun(run!!.id)
        assertEquals(3, seeded.size)
        assertTrue(seeded.none { it.jobName == "skip" })

        // Tweede tick op dezelfde dag maakt geen tweede run en geen dubbele jobs aan.
        scheduler.runOnce()
        assertEquals(1, runs.all.size)
    }

    @Test
    fun `runs projects in parallel and jobs within a project sequentially`() {
        gateway.jobs = listOf(
            njob("alpha", "lint"), njob("alpha", "test"), njob("beta", "deps"),
        )

        scheduler.runOnce() // creëert run + seed
        scheduler.runOnce() // start eerste job per project (alpha/lint, beta/deps)

        val afterStart = jobs.forRun(runs.forDate(today)!!.id)
        val running = afterStart.filter { it.status == NightlyJobStatus.RUNNING }
        assertEquals(setOf("alpha", "beta"), running.map { it.project }.toSet())
        assertEquals(2, gateway.startedStories.size) // parallel: één per project

        // alpha/lint nog niet terminaal → alpha/test mag NIET starten.
        val alphaPending = afterStart.filter { it.project == "alpha" && it.status == NightlyJobStatus.PENDING }
        assertEquals(1, alphaPending.size)

        // Markeer alpha/lint done → volgende tick start alpha/test.
        val alphaLint = afterStart.first { it.project == "alpha" && it.status == NightlyJobStatus.RUNNING }
        gateway.outcomes[alphaLint.storyKey!!] = outcome(NightlyOutcomeStatus.DONE)
        scheduler.runOnce()

        val now = jobs.forRun(runs.forDate(today)!!.id)
        assertEquals(NightlyJobStatus.DONE, now.first { it.id == alphaLint.id }.status)
        assertTrue(now.any { it.project == "alpha" && it.jobName == "test" && it.status == NightlyJobStatus.RUNNING })
    }

    @Test
    fun `a failed job does not block the rest of the project`() {
        gateway.jobs = listOf(njob("alpha", "first"), njob("alpha", "second"))
        scheduler.runOnce(); scheduler.runOnce()

        val first = jobs.forRun(runs.forDate(today)!!.id).first { it.status == NightlyJobStatus.RUNNING }
        gateway.outcomes[first.storyKey!!] = outcome(NightlyOutcomeStatus.FAILED, error = "kapot")
        scheduler.runOnce()

        val after = jobs.forRun(runs.forDate(today)!!.id)
        assertEquals(NightlyJobStatus.FAILED, after.first { it.id == first.id }.status)
        assertTrue(after.any { it.jobName == "second" && it.status == NightlyJobStatus.RUNNING })
    }

    @Test
    fun `restart mid-run picks up the existing run without creating duplicate stories`() {
        // Simuleer state-na-restart: een bestaande run met één lopende job die al een story heeft.
        val run = runs.create(today, OffsetDateTime.parse("2026-06-27T00:30:00Z"), NightlyRunStatus.RUNNING)
        val jobId = jobs.add(run.id, "alpha", "lint", "Lint")
        jobs.markRunning(jobId, "SF-500", OffsetDateTime.parse("2026-06-27T00:31:00Z"))
        gateway.outcomes["SF-500"] = outcome(NightlyOutcomeStatus.RUNNING)

        scheduler.runOnce()

        assertEquals(1, runs.all.size)              // geen tweede run
        assertTrue(gateway.startedStories.isEmpty()) // geen nieuwe story voor de al-lopende job
        assertEquals("SF-500", jobs.forRun(run.id).first().storyKey)
    }

    @Test
    fun `sends exactly one digest after the summary time and then ends the run`() {
        gateway.jobs = listOf(njob("alpha", "lint"))
        scheduler.runOnce(); scheduler.runOnce()
        val run = runs.forDate(today)!!
        val job = jobs.forRun(run.id).first()
        gateway.outcomes[job.storyKey!!] = outcome(NightlyOutcomeStatus.DONE, costUsd = 2.5)
        scheduler.runOnce() // markeert done

        clock.now = afterSummary
        scheduler.runOnce() // ná summary → digest
        scheduler.runOnce() // herhaalde tick → geen tweede digest

        assertEquals(1, gateway.digests.size)
        assertTrue(gateway.digests.first().contains("alpha"))
        assertNotNull(runs.forDate(today)!!.summarySentAt)
        assertEquals(NightlyRunStatus.ENDED, runs.forDate(today)!!.status)
    }

    @Test
    fun `empty run still sends a no-jobs digest after summary time`() {
        gateway.jobs = emptyList()
        scheduler.runOnce() // run aangemaakt, geen jobs
        clock.now = afterSummary
        scheduler.runOnce()

        assertEquals(1, gateway.digests.size)
        assertTrue(gateway.digests.first().contains("Geen nachtelijke jobs"))
        assertEquals(NightlyRunStatus.ENDED, runs.forDate(today)!!.status)
    }

    // ---- helpers & fakes ----------------------------------------------------

    private fun njob(project: String, name: String, enabled: Boolean = true) =
        NightlyJob(project, name, "Title $name", enabled, silent = true, aiSupplier = null, aiModel = null, priority = null)

    private fun outcome(status: NightlyOutcomeStatus, costUsd: Double = 0.0, error: String? = null) =
        NightlyStoryOutcome(status, OffsetDateTime.parse("2026-06-27T01:00:00Z"), OffsetDateTime.parse("2026-06-27T01:30:00Z"), costUsd, error)

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private inner class FakeSettingsRepository(private val value: NightlySettings) :
        NightlySettingsRepository(JdbcTemplate(), secrets) {
        override fun read(): NightlySettings = value
    }

    private inner class FakeRunRepository : NightlyRunRepository(JdbcTemplate(), secrets) {
        val all = mutableListOf<NightlyRunRecord>()
        private var seq = 0L

        override fun create(runDate: LocalDate, startedAt: OffsetDateTime, status: String): NightlyRunRecord {
            forDate(runDate)?.let { return it } // idempotent op run_date
            val rec = NightlyRunRecord(++seq, runDate, startedAt, null, status, null)
            all += rec
            return rec
        }

        override fun forDate(runDate: LocalDate): NightlyRunRecord? = all.firstOrNull { it.runDate == runDate }
        override fun activeRun(): NightlyRunRecord? = all.firstOrNull { it.status != NightlyRunStatus.ENDED }
        override fun latestRun(): NightlyRunRecord? = all.lastOrNull()
        override fun get(runId: Long): NightlyRunRecord? = all.firstOrNull { it.id == runId }

        override fun updateStatus(runId: Long, status: String, endedAt: OffsetDateTime?) {
            replace(runId) { it.copy(status = status, endedAt = endedAt ?: it.endedAt) }
        }

        override fun markSummarySent(runId: Long, at: OffsetDateTime, summaryText: String?) {
            replace(runId) { it.copy(summarySentAt = at, summaryText = summaryText) }
        }

        private fun replace(runId: Long, transform: (NightlyRunRecord) -> NightlyRunRecord) {
            val i = all.indexOfFirst { it.id == runId }
            if (i >= 0) all[i] = transform(all[i])
        }
    }

    private inner class FakeJobRepository : NightlyRunJobRepository(JdbcTemplate(), secrets) {
        private val store = mutableListOf<NightlyRunJobRecord>()
        private var seq = 0L

        override fun add(runId: Long, project: String, jobName: String, title: String): Long {
            val id = ++seq
            store += NightlyRunJobRecord(id, runId, project, jobName, title, NightlyJobStatus.PENDING, null, null, null, null)
            return id
        }

        override fun forRun(runId: Long): List<NightlyRunJobRecord> =
            store.filter { it.runId == runId }.sortedBy { it.id }

        override fun get(jobId: Long): NightlyRunJobRecord? = store.firstOrNull { it.id == jobId }

        override fun markRunning(jobId: Long, storyKey: String, startedAt: OffsetDateTime) {
            replace(jobId) { it.copy(status = NightlyJobStatus.RUNNING, storyKey = storyKey, startedAt = startedAt) }
        }

        override fun markTerminal(jobId: Long, status: String, endedAt: OffsetDateTime, error: String?) {
            replace(jobId) { it.copy(status = status, endedAt = endedAt, error = error) }
        }

        private fun replace(jobId: Long, transform: (NightlyRunJobRecord) -> NightlyRunJobRecord) {
            val i = store.indexOfFirst { it.id == jobId }
            if (i >= 0) store[i] = transform(store[i])
        }
    }

    private class FakeGateway : NightlyGateway {
        var jobs: List<NightlyJob> = emptyList()
        val outcomes = mutableMapOf<String, NightlyStoryOutcome>()
        val startedStories = mutableListOf<Pair<String, String>>()
        val digests = mutableListOf<String>()
        private var seq = 0

        override fun allJobs(): List<NightlyJob> = jobs

        override fun startStory(project: String, jobName: String): String {
            val key = "SF-${900 + seq++}"
            startedStories += project to jobName
            outcomes[key] = NightlyStoryOutcome(NightlyOutcomeStatus.RUNNING, null, null, 0.0)
            return key
        }

        override fun storyOutcome(storyKey: String): NightlyStoryOutcome =
            outcomes[storyKey] ?: NightlyStoryOutcome(NightlyOutcomeStatus.RUNNING, null, null, 0.0)

        override fun storyLink(storyKey: String): String = "https://dash/stories/$storyKey"

        override fun sendDigest(text: String): Boolean { digests += text; return true }
    }
}
