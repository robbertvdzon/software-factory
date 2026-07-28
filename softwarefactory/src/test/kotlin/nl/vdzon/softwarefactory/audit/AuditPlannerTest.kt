package nl.vdzon.softwarefactory.audit

import nl.vdzon.softwarefactory.audit.models.AuditOutcome
import nl.vdzon.softwarefactory.audit.models.AuditQuestionResult
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * Pure beslis-tests voor [AuditPlanner]: run-creatie, per-project seeden op de eigen starttijd,
 * sequentieel-binnen-project (ook bij meerdere jobs), failed blokkeert niet, restart-pickup en
 * end-run zodra alle jobs terminaal zijn ÉN geen project meer wacht om geseed te worden. Geen
 * digest-stap (in tegenstelling tot NightlyPlanner) — dat is met opzet weggelaten, zie
 * AuditScheduler KDoc. Geen DB/Docker nodig.
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

    /**
     * `pendingProjects` default = één project ("A") dat al klaarstaat — komt overeen met de oude
     * `startReached = true`-default. Tests die al jobs meegeven (dus per constructie al geseed)
     * geven expliciet `emptyMap()` mee, zoals de executor dat ook zou berekenen.
     */
    private fun plan(
        run: AuditRunRecord?,
        jobs: List<AuditRunJobRecord> = emptyList(),
        outcomes: Map<Long, AuditOutcome> = emptyMap(),
        settings: AuditSettings = enabled,
        pendingProjects: Map<String, Boolean> = mapOf("A" to true),
        scheduledRunExistsToday: Boolean = false,
    ) = AuditPlanner.plan(AuditPlannerInput(settings, run, jobs, outcomes, scheduledRunExistsToday, pendingProjects))

    @Test
    fun `creates a run when enabled and a project's start time is reached and no run yet`() {
        assertEquals(listOf(AuditAction.CreateRun), plan(run = null))
    }

    @Test
    fun `does not create a run when disabled`() {
        assertEquals(emptyList<AuditAction>(), plan(run = null, settings = enabled.copy(enabled = false)))
    }

    @Test
    fun `does not create a run before any project's start time is reached`() {
        assertEquals(emptyList<AuditAction>(), plan(run = null, pendingProjects = mapOf("A" to false)))
    }

    @Test
    fun `creates a run as soon as one of several projects' start time is reached`() {
        assertEquals(
            listOf(AuditAction.CreateRun),
            plan(run = null, pendingProjects = mapOf("A" to false, "B" to true)),
        )
    }

    @Test
    fun `does not create a second scheduled run on the same day`() {
        assertEquals(emptyList<AuditAction>(), plan(run = null, scheduledRunExistsToday = true))
    }

    @Test
    fun `does nothing for an already ended run (idempotent)`() {
        assertEquals(emptyList<AuditAction>(), plan(run = run(AuditRunStatus.ENDED), pendingProjects = emptyMap()))
    }

    @Test
    fun `seeds a pending project once its start time is reached`() {
        val actions = plan(run = run(AuditRunStatus.RUNNING), pendingProjects = mapOf("A" to true))
        assertEquals(listOf(AuditAction.SeedProject("A")), actions)
    }

    @Test
    fun `does not seed a project before its own start time is reached`() {
        val actions = plan(run = run(AuditRunStatus.RUNNING), pendingProjects = mapOf("A" to false))
        assertEquals(emptyList<AuditAction>(), actions)
    }

    @Test
    fun `seeds multiple projects independently as each reaches its own start time`() {
        val actions = plan(
            run = run(AuditRunStatus.RUNNING),
            pendingProjects = mapOf("A" to true, "B" to false, "C" to true),
        )
        assertEquals(setOf(AuditAction.SeedProject("A"), AuditAction.SeedProject("C")), actions.toSet())
    }

    @Test
    fun `starts the first pending job of a project and ends the run once it is the only job`() {
        val j1 = job("A", AuditJobStatus.PENDING)
        assertEquals(
            listOf(AuditAction.StartJob(j1.id)),
            plan(run = run(AuditRunStatus.RUNNING), jobs = listOf(j1), pendingProjects = emptyMap()),
        )
    }

    @Test
    fun `projects run independently in parallel`() {
        val a = job("A", AuditJobStatus.PENDING)
        val b = job("B", AuditJobStatus.PENDING)
        val actions = plan(run = run(AuditRunStatus.RUNNING), jobs = listOf(a, b), pendingProjects = emptyMap())
        assertEquals(setOf(AuditAction.StartJob(a.id), AuditAction.StartJob(b.id)), actions.toSet())
    }

    @Test
    fun `multiple jobs within one project run sequentially, one at a time`() {
        val first = job("A", AuditJobStatus.PENDING)
        val second = job("A", AuditJobStatus.PENDING)
        val actions = plan(run = run(AuditRunStatus.RUNNING), jobs = listOf(first, second), pendingProjects = emptyMap())
        assertEquals(listOf(AuditAction.StartJob(first.id)), actions)
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
        val actions = plan(
            run = run(AuditRunStatus.RUNNING),
            jobs = listOf(running),
            outcomes = mapOf(running.id to outcome),
            pendingProjects = emptyMap(),
        )
        assertEquals(listOf(AuditAction.MarkJobTerminal(running.id, AuditJobStatus.DONE, 42, null)), actions)
    }

    @Test
    fun `an asked job is terminal so the run can still end and other audits keep going`() {
        // Een auditor die een vraag stelt levert geen rapport. Zou zo'n job niet-terminaal blijven,
        // dan sluit deze run nooit en wordt er ook nooit een nieuwe aangemaakt (die eist "geen run
        // actief") — één openstaande vraag zou dan alle audits van alle projecten stilleggen.
        val running = job("A", AuditJobStatus.RUNNING, containerName = "factory-audit-a")
        val next = job("A", AuditJobStatus.PENDING)
        val outcome = AuditOutcome(
            status = AuditOutcomeStatus.ASKED,
            startedAt = null,
            endedAt = null,
            costUsd = 0.05,
            question = AuditQuestionResult(questionId = 7, question = "- Valt map X binnen de scope?"),
        )

        val actions = plan(
            run = run(AuditRunStatus.RUNNING),
            jobs = listOf(running, next),
            outcomes = mapOf(running.id to outcome),
            pendingProjects = emptyMap(),
        )

        assertEquals(
            listOf(
                AuditAction.MarkJobTerminal(running.id, AuditJobStatus.ASKED, null, null),
                AuditAction.StartJob(next.id),
            ),
            actions,
        )
        assertTrue(AuditJobStatus.isTerminal(AuditJobStatus.ASKED), "asked hoort terminaal te zijn")
    }

    @Test
    fun `a run with only asked jobs ends instead of hanging on the open question`() {
        val asked = job("A", AuditJobStatus.ASKED)

        val actions = plan(
            run = run(AuditRunStatus.RUNNING),
            jobs = listOf(asked),
            outcomes = emptyMap(),
            pendingProjects = emptyMap(),
        )

        assertEquals(listOf(AuditAction.EndRun), actions)
    }

    @Test
    fun `a done job starts the next pending job in the same project (sequential N-per-project)`() {
        val running = job("A", AuditJobStatus.RUNNING, containerName = "factory-audit-a")
        val next = job("A", AuditJobStatus.PENDING)
        val outcome = AuditOutcome(
            status = AuditOutcomeStatus.DONE,
            startedAt = null,
            endedAt = null,
            costUsd = 0.05,
            report = AuditReportResult(reportId = 42, content = "niets gevonden"),
        )
        val actions = plan(
            run = run(AuditRunStatus.RUNNING),
            jobs = listOf(running, next),
            outcomes = mapOf(running.id to outcome),
            pendingProjects = emptyMap(),
        )
        assertEquals(
            listOf(AuditAction.MarkJobTerminal(running.id, AuditJobStatus.DONE, 42, null), AuditAction.StartJob(next.id)),
            actions,
        )
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
        val actions = plan(
            run = run(AuditRunStatus.RUNNING),
            jobs = listOf(running),
            outcomes = mapOf(running.id to outcome),
            pendingProjects = emptyMap(),
        )
        assertEquals(listOf(AuditAction.MarkJobTerminal(running.id, AuditJobStatus.FAILED, null, "container crashte")), actions)
    }

    @Test
    fun `a still-running job with no outcome yet does nothing`() {
        val running = job("A", AuditJobStatus.RUNNING, containerName = "factory-audit-a")
        assertEquals(
            emptyList<AuditAction>(),
            plan(run = run(AuditRunStatus.RUNNING), jobs = listOf(running), pendingProjects = emptyMap()),
        )
    }

    @Test
    fun `ends the run once all jobs are terminal and no project is still pending`() {
        val done = job("A", AuditJobStatus.DONE)
        assertEquals(
            listOf(AuditAction.EndRun),
            plan(run = run(AuditRunStatus.RUNNING), jobs = listOf(done), pendingProjects = emptyMap()),
        )
    }

    @Test
    fun `does not end the run while a job is still pending or running`() {
        val done = job("A", AuditJobStatus.DONE)
        val pendingOther = job("B", AuditJobStatus.PENDING)
        val actions = plan(run = run(AuditRunStatus.RUNNING), jobs = listOf(done, pendingOther), pendingProjects = emptyMap())
        assertEquals(listOf(AuditAction.StartJob(pendingOther.id)), actions)
    }

    @Test
    fun `does not end the run while a project with a later start time has not been seeded yet`() {
        // Project A is klaar, maar project B's starttijd is nog niet bereikt — de run mag niet
        // eindigen vóórdat B ook geseed is, anders krijgt B die dag geen kans meer (scheduledRunExistsToday
        // blokkeert een nieuwe run).
        val done = job("A", AuditJobStatus.DONE)
        val actions = plan(run = run(AuditRunStatus.RUNNING), jobs = listOf(done), pendingProjects = mapOf("B" to false))
        assertEquals(emptyList<AuditAction>(), actions)
    }

    @Test
    fun `a manual run ignores pending projects`() {
        // AuditScheduler.runOnce() geeft voor een MANUAL-run altijd pendingProjects = emptyMap() mee
        // (zie executor) — dit test 'm op planner-niveau: geen SeedProject-ruis, gedraagt zich als
        // vandaag (1 job, geen cascade).
        val running = job("A", AuditJobStatus.RUNNING, containerName = "factory-audit-a")
        val outcome = AuditOutcome(
            status = AuditOutcomeStatus.DONE,
            startedAt = null,
            endedAt = null,
            costUsd = 0.05,
            report = AuditReportResult(reportId = 7, content = "ok"),
        )
        val actions = plan(
            run = run(AuditRunStatus.RUNNING, kind = AuditRunKind.MANUAL),
            jobs = listOf(running),
            outcomes = mapOf(running.id to outcome),
            pendingProjects = emptyMap(),
        )
        assertEquals(listOf(AuditAction.MarkJobTerminal(running.id, AuditJobStatus.DONE, 7, null)), actions)
    }
}
