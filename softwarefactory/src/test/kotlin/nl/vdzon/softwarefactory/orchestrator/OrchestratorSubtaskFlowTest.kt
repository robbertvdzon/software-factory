package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.core.contracts.AgentDispatchRequest
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.CreditsPause
import nl.vdzon.softwarefactory.core.contracts.ErrorCategory
import nl.vdzon.softwarefactory.core.contracts.IssueProcessResult
import nl.vdzon.softwarefactory.core.contracts.TrackerComment
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
 * Subtaak-fase-flow: dispatch van developer/reviewer/documenter, wachten op
 * goedkeuring, auto-approve/silent via de parent en subtaak-guards (concurrency, paused parent).
 *
 * Afgesplitst uit de voormalige OrchestratorServiceTest; wiring en fakes staan in
 * `nl.vdzon.softwarefactory.testsupport` ([OrchestratorTestHarness]).
 */
class OrchestratorSubtaskFlowTest : OrchestratorTestHarness() {
    @Test
    fun `developed subtask waits for human approval`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development", subtaskPhase = "developed")
        val issueTracker = FakeTrackerApi(listOf(sub), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Skipped("PF-7", "waiting-for-approval"), result)
        assertEquals(emptyList<Pair<String, String>>(), issueTracker.addedTags)
    }

    @Test
    fun `development subtask starts developer agent`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development")
        val issueTracker = FakeTrackerApi(listOf(sub), parentKey = "PF-1")
        val runtime = FakeAgentRuntime(now)

        val result = service(issueTracker, runtime = runtime).processIssue(sub)

        assertEquals(AgentRole.DEVELOPER, (result as IssueProcessResult.Dispatched).role)
        val dispatch = runtime.dispatches.single()
        assertEquals(AgentRole.DEVELOPER, dispatch.role)
        assertEquals("developing", dispatch.phase)
        assertEquals("PF-7", dispatch.storyKey)
    }

    @Test
    fun `development subtask after dev-approval starts reviewer`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development", subtaskPhase = "development-approved")
        val runtime = FakeAgentRuntime(now)

        val result = service(FakeTrackerApi(listOf(sub), parentKey = "PF-1"), runtime = runtime).processIssue(sub)

        assertEquals(AgentRole.REVIEWER, (result as IssueProcessResult.Dispatched).role)
        assertEquals("reviewing", runtime.dispatches.single().phase)
    }

    @Test
    fun `review-rejected starts a fix developer`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development", subtaskPhase = "review-rejected")
        val runtime = FakeAgentRuntime(now)

        val result = service(FakeTrackerApi(listOf(sub), parentKey = "PF-1"), runtime = runtime).processIssue(sub)

        assertEquals(AgentRole.DEVELOPER, (result as IssueProcessResult.Dispatched).role)
        assertEquals("developing", runtime.dispatches.single().phase)
    }

    @Test
    fun `review subtask re-reviews after a fix without separate dev approval`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "review", subtaskPhase = "developed")
        val runtime = FakeAgentRuntime(now)

        val result = service(FakeTrackerApi(listOf(sub), parentKey = "PF-1"), runtime = runtime).processIssue(sub)

        assertEquals(AgentRole.REVIEWER, (result as IssueProcessResult.Dispatched).role)
    }

    @Test
    fun `manual subtask without phase moves to awaiting-human`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "manual")
        val issueTracker = FakeTrackerApi(listOf(sub), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Recovered("PF-7", "awaiting-human"), result)
    }

    @Test
    fun `subtask inherits AI-supplier from parent when its own is empty`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development", aiSupplier = "")
        val parent = issue("PF-1", aiSupplier = "claude")
        val issueTracker = FakeTrackerApi(listOf(sub, parent), parentKey = "PF-1")
        val runtime = FakeAgentRuntime(now)

        val result = service(issueTracker, runtime = runtime).processIssue(sub)

        assertEquals(AgentRole.DEVELOPER, (result as IssueProcessResult.Dispatched).role)
        assertEquals("claude", runtime.dispatches.single().aiSupplier)
    }

    @Test
    fun `subtask dispatch is serialized on the parent branch`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development")
        val issueTracker = FakeTrackerApi(listOf(sub), parentKey = "PF-1")
        val runtime = FakeAgentRuntime(now, runningStories = setOf("PF-1"))

        val result = service(issueTracker, runtime = runtime).processIssue(sub)

        assertEquals(IssueProcessResult.Skipped("PF-7", "concurrency-cap"), result)
        assertEquals(emptyList<AgentDispatchRequest>(), runtime.dispatches)
    }

    @Test
    fun `paused parent story halts subtask dispatch`() {
        val parent = issue("PF-1", paused = true)
        val sub = issue("PF-7", type = "Task", subtaskType = "development")
        val issueTracker = FakeTrackerApi(listOf(sub, parent), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Skipped("PF-7", "parent-paused"), result)
    }

    @Test
    fun `summarized subtask waits for approval`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "summary", subtaskPhase = "summarized")
        val issueTracker = FakeTrackerApi(listOf(sub), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Skipped("PF-7", "waiting-for-approval"), result)
    }

    @Test
    fun `auto-approve on parent advances developed subtask to development-approved`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development", subtaskPhase = "developed")
        val parent = issue("PF-1", autoApprove = true)
        val issueTracker = FakeTrackerApi(listOf(sub, parent), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Recovered("PF-7", "development-approved"), result)
        assertEquals("development-approved", issueTracker.lastUpdate("PF-7").values[TrackerField.SUBTASK_PHASE])
    }

    @Test
    fun `auto-approve on parent advances summarized subtask to summary-approved`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "summary", subtaskPhase = "summarized")
        val parent = issue("PF-1", autoApprove = true)
        val issueTracker = FakeTrackerApi(listOf(sub, parent), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Recovered("PF-7", "summary-approved"), result)
        assertEquals("summary-approved", issueTracker.lastUpdate("PF-7").values[TrackerField.SUBTASK_PHASE])
    }

    @Test
    fun `silent parent makes developed-with-questions subtask error instead of waiting`() {
        val sub = issue(
            "PF-7",
            type = "Task",
            subtaskType = "development",
            subtaskPhase = "developed-with-questions",
            comments = listOf(TrackerComment("c-1", null, "Factory", "[DEVELOPER] Welke endpoint moet ik gebruiken?", null)),
        )
        val parent = issue("PF-1", silent = true)
        val issueTracker = FakeTrackerApi(listOf(sub, parent), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertTrue(result is IssueProcessResult.Errored, "verwacht Errored, was $result")
        val error = issueTracker.lastUpdate("PF-7").values[TrackerField.ERROR] as String
        assertEquals(ErrorCategory.CLARIFICATION, ErrorCategory.of(error))
    }

    @Test
    fun `silent parent advances developed subtask to development-approved`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development", subtaskPhase = "developed")
        val parent = issue("PF-1", silent = true)
        val issueTracker = FakeTrackerApi(listOf(sub, parent), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Recovered("PF-7", "development-approved"), result)
        assertEquals("development-approved", issueTracker.lastUpdate("PF-7").values[TrackerField.SUBTASK_PHASE])
    }

    @Test
    fun `documentation start dispatches the documenter`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "documentation", subtaskPhase = "start", aiSupplier = "claude")
        val issueTracker = FakeTrackerApi(listOf(sub), parentKey = "PF-1")
        val runtime = FakeAgentRuntime(now)

        val result = service(issueTracker, runtime = runtime).processIssue(sub)

        assertEquals(AgentRole.DOCUMENTER, (result as IssueProcessResult.Dispatched).role)
        assertEquals("documenting", runtime.dispatches.single().phase)
    }

    @Test
    fun `documented subtask waits for approval`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "documentation", subtaskPhase = "documented")
        val issueTracker = FakeTrackerApi(listOf(sub), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Skipped("PF-7", "waiting-for-approval"), result)
    }

    @Test
    fun `auto-approve on parent advances documented subtask to documentation-approved`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "documentation", subtaskPhase = "documented")
        val parent = issue("PF-1", autoApprove = true)
        val issueTracker = FakeTrackerApi(listOf(sub, parent), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Recovered("PF-7", "documentation-approved"), result)
        assertEquals("documentation-approved", issueTracker.lastUpdate("PF-7").values[TrackerField.SUBTASK_PHASE])
    }

    @Test
    fun `auto-approve does not advance a developed-with-questions subtask`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development", subtaskPhase = "developed-with-questions")
        val parent = issue("PF-1", autoApprove = true)
        val issueTracker = FakeTrackerApi(listOf(sub, parent), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Skipped("PF-7", "waiting-for-user"), result)
    }
}
