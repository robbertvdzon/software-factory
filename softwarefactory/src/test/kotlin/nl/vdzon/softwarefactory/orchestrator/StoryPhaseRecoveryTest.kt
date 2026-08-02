package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.core.contracts.IssueProcessResult
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.testsupport.FakeAgentRuntime
import nl.vdzon.softwarefactory.testsupport.FakeTrackerApi
import nl.vdzon.softwarefactory.testsupport.OrchestratorTestHarness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SF-1460 — recovery van een onderbroken `Story Phase` (refining/planning): geen draaiende agent,
 * geen (voltooide) agent-run, `activePhaseRecoveryDelay` verstreken. Een onderbroken `refining`
 * moet terugvallen naar `start` (niet leeg — een lege fase blokkeert de start-next-wachtrij
 * onopgemerkt), symmetrisch met de bestaande `planning` -> `refined-approved`-terugval.
 */
class StoryPhaseRecoveryTest : OrchestratorTestHarness() {

    @Test
    fun `story quota wait precedes hard timeout and resumes same refiner at deadline`() {
        val waiting = issue(
            "KAN-59",
            storyPhase = "refining",
            agentStartedAt = now.minusHours(2),
            retryAfter = now.plusMinutes(1),
        )
        val waitingTracker = FakeTrackerApi(listOf(waiting))
        val waitingRuntime = FakeAgentRuntime(now)
        val waitingResult = service(waitingTracker, runtime = waitingRuntime).processIssue(waiting)

        assertEquals(IssueProcessResult.Skipped("KAN-59", "claude-quota-until:${now.plusMinutes(1)}"), waitingResult)
        assertTrue(waitingRuntime.dispatches.isEmpty())
        assertTrue(waitingTracker.updates.values.flatten().none { TrackerField.ERROR in it.values })

        val due = waiting.copy(fields = waiting.fields.copy(retryAfter = now))
        val dueTracker = FakeTrackerApi(listOf(due))
        val dueRuntime = FakeAgentRuntime(now)
        val dueResult = service(dueTracker, runtime = dueRuntime).processIssue(due)

        assertTrue(dueResult is IssueProcessResult.Dispatched)
        assertEquals(AgentRole.REFINER, dueRuntime.dispatches.single().role)
        assertTrue(dueTracker.updates.values.flatten().any { it.values[TrackerField.AGENT_STARTED_AT] == now })
    }

    @Test
    fun `interrupted refining recovers to start instead of empty`() {
        val story = issue("KAN-60", storyPhase = "refining", agentStartedAt = now.minusMinutes(5))
        val issueTracker = FakeTrackerApi(listOf(story))
        val service = service(issueTracker, runtime = FakeAgentRuntime(now))

        val result = service.processIssue(issueTracker.getIssue("KAN-60"))

        assertEquals(IssueProcessResult.Recovered("KAN-60", "start"), result)
        assertEquals("start", issueTracker.lastUpdate("KAN-60").values[TrackerField.STORY_PHASE])
    }

    @Test
    fun `interrupted planning still recovers to refined-approved`() {
        val story = issue("KAN-61", storyPhase = "planning", agentStartedAt = now.minusMinutes(5))
        val issueTracker = FakeTrackerApi(listOf(story))
        val service = service(issueTracker, runtime = FakeAgentRuntime(now))

        val result = service.processIssue(issueTracker.getIssue("KAN-61"))

        assertEquals(IssueProcessResult.Recovered("KAN-61", "refined-approved"), result)
        assertEquals("refined-approved", issueTracker.lastUpdate("KAN-61").values[TrackerField.STORY_PHASE])
    }
}
