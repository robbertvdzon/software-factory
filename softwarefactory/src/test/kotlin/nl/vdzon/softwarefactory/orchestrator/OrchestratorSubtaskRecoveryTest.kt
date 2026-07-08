package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.core.AgentDispatchRequest
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.CreditsPause
import nl.vdzon.softwarefactory.core.ErrorCategory
import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.core.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.testsupport.FakeAgentRuntime
import nl.vdzon.softwarefactory.testsupport.FakeCostMonitor
import nl.vdzon.softwarefactory.testsupport.FakeCreditsPauseCoordinator
import nl.vdzon.softwarefactory.testsupport.FakeGitHubApi
import nl.vdzon.softwarefactory.testsupport.FakeTrackerApi
import nl.vdzon.softwarefactory.testsupport.InMemoryAgentRunRepository
import nl.vdzon.softwarefactory.testsupport.InMemoryProcessedCommentStore
import nl.vdzon.softwarefactory.testsupport.InMemoryStoryRunRepository
import nl.vdzon.softwarefactory.testsupport.OrchestratorTestHarness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Recovery van subtaken in een actieve fase zonder draaiende container:
 * wacht-grace rond dispatch/completion versus her-dispatch van echt hangende agents.
 *
 * Afgesplitst uit de voormalige OrchestratorServiceTest; wiring en fakes staan in
 * `nl.vdzon.softwarefactory.testsupport` ([OrchestratorTestHarness]).
 */
class OrchestratorSubtaskRecoveryTest : OrchestratorTestHarness() {
    @Test
    fun `subtask recovery waits for a recently dispatched agent instead of re-dispatching`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "summary", subtaskPhase = "summarizing", agentStartedAt = now.minusSeconds(5))
        val issueTracker = FakeTrackerApi(listOf(sub), parentKey = "PF-1", subtasks = listOf(sub))
        val runtime = FakeAgentRuntime(now) // geen draaiende container
        val service = service(issueTracker, runtime = runtime)

        val result = service.pollOnce()

        // Net gedispatcht → niet meteen opnieuw starten, maar wachten op de completion.
        assertEquals(IssueProcessResult.Skipped("PF-7", "waiting-for-active-phase-recovery"), result.issueResults.single())
        assertTrue(runtime.dispatches.isEmpty())
    }

    @Test
    fun `subtask recovery re-dispatches a long-hanging agent`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "summary", subtaskPhase = "summarizing", agentStartedAt = now.minusMinutes(5))
        val issueTracker = FakeTrackerApi(listOf(sub), parentKey = "PF-1", subtasks = listOf(sub))
        val runtime = FakeAgentRuntime(now)
        val service = service(issueTracker, runtime = runtime)

        val result = service.pollOnce()

        assertTrue(result.issueResults.single() is IssueProcessResult.Dispatched)
        assertTrue(runtime.dispatches.isNotEmpty())
    }

    @Test
    fun `subtask recovery waits while a finished agent completion is still being processed`() {
        // Lang geleden gestart (tijd-grace verlopen) + geen draaiende container, MAAR de laatste
        // agent-run is nog niet afgerond (endedAt == null): de container stopte, maar de completion
        // is nog niet verwerkt. Recovery mag dan NIET herstarten (dat was de race), maar wachten.
        val sub = issue("PF-7", type = "Task", subtaskType = "summary", subtaskPhase = "summarizing", agentStartedAt = now.minusMinutes(5))
        val issueTracker = FakeTrackerApi(listOf(sub), parentKey = "PF-1", subtasks = listOf(sub))
        val runtime = FakeAgentRuntime(now) // geen draaiende container
        val storyRuns = InMemoryStoryRunRepository()
        val agentRuns = InMemoryAgentRunRepository()
        val storyRun = storyRuns.openOrCreate("PF-1", "repo")
        agentRuns.recordStarted(
            storyRunId = storyRun.id,
            role = AgentRole.SUMMARIZER,
            containerName = "factory-pf-7-summarizer",
            model = null,
            effort = null,
            level = null,
            workspacePath = null,
            subtaskKey = "PF-7",
        )

        val service = service(issueTracker, runtime = runtime, storyRuns = storyRuns, agentRuns = agentRuns)
        val result = service.pollOnce()

        assertEquals(IssueProcessResult.Skipped("PF-7", "awaiting-agent-completion"), result.issueResults.single())
        assertTrue(runtime.dispatches.isEmpty())
    }

    @Test
    fun `subtask recovery waits shortly after a run ended while completion writes the phase`() {
        // Race: de run is NET geëindigd (endedAt gezet), maar de completion schrijft de nieuwe fase
        // pas daarna naar de tracker. In dat venster is de fase nog actief; recovery mag niet herstarten.
        val sub = issue("PF-7", type = "Task", subtaskType = "summary", subtaskPhase = "summarizing", agentStartedAt = now.minusMinutes(5))
        val issueTracker = FakeTrackerApi(listOf(sub), parentKey = "PF-1", subtasks = listOf(sub))
        val runtime = FakeAgentRuntime(now)
        val storyRuns = InMemoryStoryRunRepository()
        val agentRuns = InMemoryAgentRunRepository()
        val storyRun = storyRuns.openOrCreate("PF-1", "repo")
        agentRuns.addEnded(storyRun.id, AgentRole.SUMMARIZER, outcome = "summarized", summary = "done", subtaskKey = "PF-7", endedAt = now)

        val service = service(issueTracker, runtime = runtime, storyRuns = storyRuns, agentRuns = agentRuns)
        val result = service.pollOnce()

        assertEquals(IssueProcessResult.Skipped("PF-7", "awaiting-completion-settle"), result.issueResults.single())
        assertTrue(runtime.dispatches.isEmpty())
    }

    @Test
    fun `subtask recovery re-dispatches when a run ended long ago but the phase is still active`() {
        // Completion-grace verlopen + fase nog actief → echte hang: wél herstarten.
        val sub = issue("PF-7", type = "Task", subtaskType = "summary", subtaskPhase = "summarizing", agentStartedAt = now.minusMinutes(5))
        val issueTracker = FakeTrackerApi(listOf(sub), parentKey = "PF-1", subtasks = listOf(sub))
        val runtime = FakeAgentRuntime(now)
        val storyRuns = InMemoryStoryRunRepository()
        val agentRuns = InMemoryAgentRunRepository()
        val storyRun = storyRuns.openOrCreate("PF-1", "repo")
        agentRuns.addEnded(storyRun.id, AgentRole.SUMMARIZER, outcome = "summarized", summary = "done", subtaskKey = "PF-7", endedAt = now.minusMinutes(5))

        val service = service(issueTracker, runtime = runtime, storyRuns = storyRuns, agentRuns = agentRuns)
        val result = service.pollOnce()

        assertTrue(result.issueResults.single() is IssueProcessResult.Dispatched)
    }
}
