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
 * PR-monitor (factory-comments op de PR worden development-subtaken) en de
 * developer-loopback-cap (story-override, telling per subtaak).
 *
 * Afgesplitst uit de voormalige OrchestratorServiceTest; wiring en fakes staan in
 * `nl.vdzon.softwarefactory.testsupport` ([OrchestratorTestHarness]).
 */
class OrchestratorPrAndLoopbackTest : OrchestratorTestHarness() {
    @Test
    fun `uses story developer loopback override before writing cap error`() {
        val issueTracker = FakeTrackerApi(listOf(issue("KAN-10", phase = "reviewed-with-feedback-for-developer", maxDeveloperLoopbacks = 7)))
        val storyRuns = InMemoryStoryRunRepository()
        val cappedRun = storyRuns.openOrCreate("KAN-10", "git@example/repo.git")
        val agentRuns = InMemoryAgentRunRepository().apply {
            repeat(7) { addEnded(cappedRun.id, AgentRole.DEVELOPER, outcome = "developed", summary = "done") }
        }
        val runtime = FakeAgentRuntime(now)
        val service = service(issueTracker, runtime = runtime, storyRuns = storyRuns, agentRuns = agentRuns)

        val result = service.pollOnce()

        assertTrue(result.issueResults.single() is IssueProcessResult.Dispatched)
        assertEquals(1, runtime.dispatches.size)
    }

    @Test
    fun `developer loopback cap is counted per subtask, not story-wide`() {
        // SF-8 (development-subtaak) is in review afgekeurd -> wil een developer-fix (loopback). De story
        // heeft al 6 developer-runs van een ANDERE subtaak; story-breed zou dat de default-cap (5)
        // overschrijden. Per subtaak telt SF-8 = 0 developer-runs, dus de fix mag gewoon dispatchen.
        val sub = issue("SF-8", type = "Task", subtaskType = "development", subtaskPhase = "review-rejected")
        val issueTracker = FakeTrackerApi(listOf(sub), parentKey = "SF-1", subtasks = listOf(sub))
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("SF-1", "git@example/repo.git")
        val agentRuns = InMemoryAgentRunRepository().apply {
            repeat(6) { addEnded(storyRun.id, AgentRole.DEVELOPER, outcome = "developed", summary = "done", subtaskKey = "SF-2") }
        }
        val runtime = FakeAgentRuntime(now)
        val service = service(issueTracker, runtime = runtime, storyRuns = storyRuns, agentRuns = agentRuns)

        val result = service.pollOnce()

        val dispatched = result.issueResults.single()
        assertTrue(dispatched is IssueProcessResult.Dispatched, "Verwacht Dispatched, kreeg $dispatched")
        assertEquals(AgentRole.DEVELOPER, (dispatched as IssueProcessResult.Dispatched).role)
    }

    @Test
    fun `PR factory comment is claimed and creates a development subtask`() {
        val issueTracker = FakeTrackerApi(listOf(issue("KAN-12", storyPhase = "planning-approved")))
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("KAN-12", "git@github.com:robbertvdzon/sample-build-project.git")
        storyRuns.updatePullRequest(
            storyRun.id,
            "ai/KAN-12",
            124,
            "https://github.com/robbertvdzon/sample-build-project/pull/124",
            "main",
            "ai/",
            "https://sample-pr-{pr_num}.example.com",
            "sample-pr-{pr_num}",
            null,
        )
        val pullRequests = FakeGitHubApi(
            commentsByPr = mapOf(124 to listOf(PullRequestComment(9001, "@factory kun je deze tekst aanpassen?"))),
        )
        val service = service(issueTracker, storyRuns = storyRuns, pullRequests = pullRequests)

        val result = service.pollOnce()

        assertEquals(IssueProcessResult.PrCommentTriggered("KAN-12", 124, 1), result.issueResults[1])
        // v2: PR-feedback wordt een nieuwe development-subtask, op fase `start` voor de keten.
        assertEquals(
            nl.vdzon.softwarefactory.core.SubtaskType.DEVELOPMENT,
            issueTracker.createdSubtasks.single().type,
        )
        assertEquals("start", issueTracker.lastUpdate("KAN-12-sub1").values[TrackerField.SUBTASK_PHASE])
        assertEquals(emptyList<Pair<String, String>>(), issueTracker.addedTags)
        assertEquals(9001, pullRequests.claimedComments.single())
    }
}
