package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.core.contracts.IssueProcessResult
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.testsupport.FakeAgentRuntime
import nl.vdzon.softwarefactory.testsupport.FakeTrackerApi
import nl.vdzon.softwarefactory.testsupport.OrchestratorTestHarness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SF-1460 — recovery van een onderbroken `Story Phase` (refining/planning): geen draaiende agent,
 * geen (voltooide) agent-run, `activePhaseRecoveryDelay` verstreken. Een onderbroken `refining`
 * moet terugvallen naar `start` (niet leeg — een lege fase blokkeert de start-next-wachtrij
 * onopgemerkt), symmetrisch met de bestaande `planning` -> `refined-approved`-terugval.
 */
class StoryPhaseRecoveryTest : OrchestratorTestHarness() {

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
