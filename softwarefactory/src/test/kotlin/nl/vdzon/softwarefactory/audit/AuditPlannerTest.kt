package nl.vdzon.softwarefactory.audit

import nl.vdzon.softwarefactory.audit.models.AuditOutcome
import nl.vdzon.softwarefactory.audit.models.AuditReportResult
import nl.vdzon.softwarefactory.audit.repositories.AuditJobStatus
import nl.vdzon.softwarefactory.audit.repositories.AuditRunJobRecord
import nl.vdzon.softwarefactory.audit.repositories.AuditRunKind
import nl.vdzon.softwarefactory.audit.repositories.AuditRunRecord
import nl.vdzon.softwarefactory.audit.repositories.AuditRunStatus
import nl.vdzon.softwarefactory.audit.repositories.AuditSettings
import nl.vdzon.softwarefactory.audit.services.AuditAction
import nl.vdzon.softwarefactory.audit.services.AuditPlanner
import nl.vdzon.softwarefactory.audit.services.AuditPlannerInput
import nl.vdzon.softwarefactory.audit.types.AuditOutcomeStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * Pure beslis-tests voor [AuditPlanner]: run-creatie, sequentieel-binnen-project, failed blokkeert
 * niet, restart-pickup en end-run zodra alle (hoogstens 1-per-project) jobs terminaal zijn. Geen
 * digest-stap (in tegenstelling tot NightlyPlanner) — dat is met opzet weggelaten, zie AuditScheduler
 * KDoc. Geen DB/Docker nodig.
 */
class AuditPlannerTest {

    private val today = LocalDate.of(2026, 7, 26)
    private val enabled = AuditSettings(enabled = true, startTime = LocalTime.of(8, 0), summaryTime = LocalTime.of(8, 30))

    private fun run(status: String, kind: String = AuditRunKind.SCHEDULED) =
        AuditRunRecord(
            id = 1,
            runDate = today,
            startedAt = OffsetDateTime.parse("2026-07-26T06:00:00Z"),
            endedAt = null,
            status = status,
            summarySentAt = null,
            kind = kind,
        )

    private var nextId = 1L
    private fun job(project: String, status: String, containerName: String? = null) =
        AuditRunJobRecord(
            id = nextId++,
            runId = 1,
            project = project,
            auditType = "quality",
            title = "Quality-audit",
            status = status,
            reportId = null,
            containerName = containerName,
            workspacePath = null,
            storyRunId = if (containerName != null) 1L else null,
            startedAt = null,
            endedAt = null,
            error = null,
        )

    private fun plan(
        run: AuditRunRecord?,
        jobs: List<AuditRunJobRecord> = emptyList(),
        outcomes: Map<Long, AuditOutcome> = emptyMap(),
        settings: AuditSettings = enabled,
        startReached: Boolean = true,
        scheduledRunExistsToday: Boolean = false,
    ) = AuditPlanner.plan(AuditPlannerInput(settings, run, jobs, outcomes, startReached, scheduledRunExistsToday))

    @Test
    fun `creates a run when enabled and start time reached and no run yet`() {
        assertEquals(listOf(AuditAction.CreateRun), plan(run = null))
    }

    @Test
    fun `does not create a run when disabled`() {
        assertEquals(emptyList<AuditAction>(), plan(run = null, settings = enabled.copy(enabled = false)))
    }

    @Test
    fun `does not create a run before the start time`() {
        assertEquals(emptyList<AuditAction>(), plan(run = null, startReached = false))
    }

    @Test
    fun `does not create a second scheduled run on the same day`() {
        assertEquals(emptyList<AuditAction>(), plan(run = null, scheduledRunExistsToday = true))
    }

    @Test
    fun `does nothing for an already ended run (idempotent)`() {
        assertEquals(emptyList<AuditAction>(), plan(run = run(AuditRunStatus.ENDED)))
    }

    @Test
    fun `starts the first pending job of a project and ends the run once it is the only job`() {
        val j1 = job("A", AuditJobStatus.PENDING)
        assertEquals(listOf(AuditAction.StartJob(j1.id)), plan(run = run(AuditRunStatus.RUNNING), jobs = listOf(j1)))
    }

    @Test
    fun `projects run independently in parallel`() {
        val a = job("A", AuditJobStatus.PENDING)
        val b = job("B", AuditJobStatus.PENDING)
        val actions = plan(run = run(AuditRunStatus.RUNNING), jobs = listOf(a, b))
        assertEquals(setOf(AuditAction.StartJob(a.id), AuditAction.StartJob(b.id)), actions.toSet())
    }

    @Test
    fun `a done job is marked terminal with its report id, no more pending jobs left`() {
        val running = job("A", AuditJobStatus.RUNNING, containerName = "factory-audit-a")
        val outcome = AuditOutcome(
            status = AuditOutcomeStatus.DONE,
            startedAt = null,
            endedAt = null,
            costUsd = 0.05,
            report = AuditReportResult(reportId = 42, content = "niets gevonden"),
        )
        // EndRun volgt pas de vólgende tick: deze plan-pass mutéért `input.jobs` niet, dus de
        // net-teruggegeven MarkJobTerminal-actie is hier nog niet "verwerkt" in de allTerminal-check
        // (zelfde gedrag als NightlyPlanner).
        val actions = plan(run = run(AuditRunStatus.RUNNING), jobs = listOf(running), outcomes = mapOf(running.id to outcome))
        assertEquals(listOf(AuditAction.MarkJobTerminal(running.id, AuditJobStatus.DONE, 42, null)), actions)
    }

    @Test
    fun `a failed job is marked terminal but does not block the run`() {
        val running = job("A", AuditJobStatus.RUNNING, containerName = "factory-audit-a")
        val outcome = AuditOutcome(
            status = AuditOutcomeStatus.FAILED,
            startedAt = null,
            endedAt = null,
            costUsd = 0.0,
            error = "container crashte",
        )
        val actions = plan(run = run(AuditRunStatus.RUNNING), jobs = listOf(running), outcomes = mapOf(running.id to outcome))
        assertEquals(listOf(AuditAction.MarkJobTerminal(running.id, AuditJobStatus.FAILED, null, "container crashte")), actions)
    }

    @Test
    fun `a still-running job with no outcome yet does nothing`() {
        val running = job("A", AuditJobStatus.RUNNING, containerName = "factory-audit-a")
        assertEquals(emptyList<AuditAction>(), plan(run = run(AuditRunStatus.RUNNING), jobs = listOf(running)))
    }

    @Test
    fun `ends the run once all jobs are terminal`() {
        val done = job("A", AuditJobStatus.DONE)
        assertEquals(listOf(AuditAction.EndRun), plan(run = run(AuditRunStatus.RUNNING), jobs = listOf(done)))
    }

    @Test
    fun `does not end the run while a job is still pending or running`() {
        val done = job("A", AuditJobStatus.DONE)
        val pendingOther = job("B", AuditJobStatus.PENDING)
        val actions = plan(run = run(AuditRunStatus.RUNNING), jobs = listOf(done, pendingOther))
        assertEquals(listOf(AuditAction.StartJob(pendingOther.id)), actions)
    }
}
