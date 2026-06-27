package nl.vdzon.softwarefactory.nightly

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * Pure beslis-tests voor [NightlyPlanner]: idempotente run-creatie, sequentieel-binnen-project +
 * parallel-tussen-projecten, failed blokkeert de nacht niet, restart-pickup zonder dubbele stories en
 * digest-idempotentie. Geen DB/gateway nodig.
 */
class NightlyPlannerTest {

    private val today = LocalDate.of(2026, 6, 27)
    private val enabled = NightlySettings(enabled = true, startTime = LocalTime.of(2, 0), summaryTime = LocalTime.of(7, 0))

    private fun run(status: String, summarySentAt: OffsetDateTime? = null) =
        NightlyRunRecord(
            id = 1,
            runDate = today,
            startedAt = OffsetDateTime.parse("2026-06-27T00:00:00Z"),
            endedAt = null,
            status = status,
            summarySentAt = summarySentAt,
        )

    private var nextId = 1L
    private fun job(project: String, status: String, storyKey: String? = null) =
        NightlyRunJobRecord(
            id = nextId++,
            runId = 1,
            project = project,
            jobName = "job$nextId",
            title = "Job $nextId",
            status = status,
            storyKey = storyKey,
            startedAt = null,
            endedAt = null,
            error = null,
        )

    private fun plan(
        run: NightlyRunRecord?,
        jobs: List<NightlyRunJobRecord> = emptyList(),
        outcomes: Map<Long, NightlyStoryOutcome> = emptyMap(),
        settings: NightlySettings = enabled,
        startReached: Boolean = true,
        summaryReached: Boolean = false,
    ) = NightlyPlanner.plan(
        NightlyPlannerInput(settings, run, jobs, outcomes, startReached, summaryReached),
    )

    @Test
    fun `creates a run when enabled and start time reached and no run yet`() {
        assertEquals(listOf(NightlyAction.CreateRun), plan(run = null))
    }

    @Test
    fun `does not create a run when disabled`() {
        assertEquals(emptyList<NightlyAction>(), plan(run = null, settings = enabled.copy(enabled = false)))
    }

    @Test
    fun `does not create a run before the start time`() {
        assertEquals(emptyList<NightlyAction>(), plan(run = null, startReached = false))
    }

    @Test
    fun `does nothing for an already ended run (idempotent)`() {
        assertEquals(emptyList<NightlyAction>(), plan(run = run(NightlyRunStatus.ENDED)))
    }

    @Test
    fun `starts the first pending job of a project`() {
        val j1 = job("A", NightlyJobStatus.PENDING)
        assertEquals(listOf(NightlyAction.StartJob(j1.id)), plan(run = run(NightlyRunStatus.RUNNING), jobs = listOf(j1)))
    }

    @Test
    fun `within a project jobs are strictly sequential`() {
        val running = job("A", NightlyJobStatus.RUNNING, storyKey = "SF-100")
        val pending = job("A", NightlyJobStatus.PENDING)
        // De lopende story is nog niet terminaal → niets starten.
        val actions = plan(
            run = run(NightlyRunStatus.RUNNING),
            jobs = listOf(running, pending),
            outcomes = mapOf(running.id to outcome(NightlyOutcomeStatus.RUNNING)),
        )
        assertEquals(emptyList<NightlyAction>(), actions)
    }

    @Test
    fun `done job is marked terminal and the next job of the project starts`() {
        val running = job("A", NightlyJobStatus.RUNNING, storyKey = "SF-100")
        val pending = job("A", NightlyJobStatus.PENDING)
        val actions = plan(
            run = run(NightlyRunStatus.RUNNING),
            jobs = listOf(running, pending),
            outcomes = mapOf(running.id to outcome(NightlyOutcomeStatus.DONE)),
        )
        assertEquals(
            listOf(
                NightlyAction.MarkJobTerminal(running.id, NightlyJobStatus.DONE, null),
                NightlyAction.StartJob(pending.id),
            ),
            actions,
        )
    }

    @Test
    fun `failed job does not block the rest of the project`() {
        val running = job("A", NightlyJobStatus.RUNNING, storyKey = "SF-100")
        val pending = job("A", NightlyJobStatus.PENDING)
        val actions = plan(
            run = run(NightlyRunStatus.RUNNING),
            jobs = listOf(running, pending),
            outcomes = mapOf(running.id to outcome(NightlyOutcomeStatus.FAILED, error = "boom")),
        )
        assertEquals(
            listOf(
                NightlyAction.MarkJobTerminal(running.id, NightlyJobStatus.FAILED, "boom"),
                NightlyAction.StartJob(pending.id),
            ),
            actions,
        )
    }

    @Test
    fun `projects run in parallel - one job started per project`() {
        val a = job("A", NightlyJobStatus.PENDING)
        val b = job("B", NightlyJobStatus.PENDING)
        val actions = plan(run = run(NightlyRunStatus.RUNNING), jobs = listOf(a, b))
        assertTrue(actions.contains(NightlyAction.StartJob(a.id)))
        assertTrue(actions.contains(NightlyAction.StartJob(b.id)))
        assertEquals(2, actions.size)
    }

    @Test
    fun `restart pickup - a running job is not restarted and no duplicate story is created`() {
        val running = job("A", NightlyJobStatus.RUNNING, storyKey = "SF-100")
        val actions = plan(
            run = run(NightlyRunStatus.RUNNING),
            jobs = listOf(running),
            outcomes = mapOf(running.id to outcome(NightlyOutcomeStatus.RUNNING)),
        )
        assertTrue(actions.none { it is NightlyAction.StartJob })
        assertEquals(emptyList<NightlyAction>(), actions)
    }

    @Test
    fun `sends digest after summary time and ends the run when all jobs terminal`() {
        val done = job("A", NightlyJobStatus.DONE)
        val actions = plan(
            run = run(NightlyRunStatus.RUNNING),
            jobs = listOf(done),
            summaryReached = true,
        )
        assertEquals(listOf(NightlyAction.SendDigest, NightlyAction.EndRun), actions)
    }

    @Test
    fun `does not resend the digest once summary_sent_at is set`() {
        val done = job("A", NightlyJobStatus.DONE)
        val actions = plan(
            run = run(NightlyRunStatus.RUNNING, summarySentAt = OffsetDateTime.parse("2026-06-27T07:00:00Z")),
            jobs = listOf(done),
            summaryReached = true,
        )
        // Geen tweede digest; run mag wel eindigen.
        assertTrue(actions.none { it == NightlyAction.SendDigest })
        assertTrue(actions.contains(NightlyAction.EndRun))
    }

    @Test
    fun `does not end the run before the digest is handled`() {
        val done = job("A", NightlyJobStatus.DONE)
        val actions = plan(run = run(NightlyRunStatus.RUNNING), jobs = listOf(done), summaryReached = false)
        assertTrue(actions.none { it == NightlyAction.EndRun })
        assertTrue(actions.none { it == NightlyAction.SendDigest })
    }

    @Test
    fun `empty run sends a digest and ends after summary time`() {
        val actions = plan(run = run(NightlyRunStatus.RUNNING), jobs = emptyList(), summaryReached = true)
        assertEquals(listOf(NightlyAction.SendDigest, NightlyAction.EndRun), actions)
    }

    private fun outcome(status: NightlyOutcomeStatus, error: String? = null) =
        NightlyStoryOutcome(status = status, startedAt = null, endedAt = null, costUsd = 0.0, error = error)
}
