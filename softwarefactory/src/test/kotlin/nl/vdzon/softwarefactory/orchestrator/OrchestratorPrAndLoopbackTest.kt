package nl.vdzon.softwarefactory.orchestrator

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import nl.vdzon.softwarefactory.pipeline.service.SubtaskExecutionCoordinator
import nl.vdzon.softwarefactory.testsupport.OrchestratorTestHarness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

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
        val issueTracker = FakeTrackerApi(listOf(sub), parentKey = "SF-1", subtasks = listOf(sub), parentIssue = issue("SF-1"))
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
    fun `development-rejected loopback tells the developer why (own verification-gate rejection)`() {
        // De verificatie-harness kan de developer's eigen "developed"-conclusie overrulen naar
        // development-rejected; AgentCommentContext filtert eigen-rol-comments normaal uit de
        // context van de volgende run, dus zonder deze expliciete loopbackReason ziet de developer
        // dit nooit (zie SubtaskExecutionCoordinator.developmentRejectedReason).
        val rejectionComment = TrackerComment(
            id = "c1",
            authorAccountId = null,
            authorDisplayName = "Agent",
            body = "[DEVELOPER] Alles leek al klaar.\n\n" +
                "[FACTORY VERIFICATION] Verification-command backend-maven-verify afgewezen: status=failed, exitCode=1",
            created = now.minusMinutes(5),
        )
        val sub = issue(
            "SF-8",
            type = "Task",
            subtaskType = "development",
            subtaskPhase = "development-rejected",
            comments = listOf(rejectionComment),
        )
        val issueTracker = FakeTrackerApi(listOf(sub), parentKey = "SF-1", subtasks = listOf(sub), parentIssue = issue("SF-1"))
        val storyRuns = InMemoryStoryRunRepository()
        storyRuns.openOrCreate("SF-1", "git@example/repo.git")
        val runtime = FakeAgentRuntime(now)
        val service = service(issueTracker, runtime = runtime, storyRuns = storyRuns)

        val result = service.pollOnce()

        val dispatched = result.issueResults.single()
        assertTrue(dispatched is IssueProcessResult.Dispatched, "Verwacht Dispatched, kreeg $dispatched")
        val request = runtime.dispatches.single()
        assertTrue(
            request.developerLoopbackReason.orEmpty().contains("FACTORY VERIFICATION"),
            "Verwacht dat de developer zijn eigen vorige verificatie-afwijzing te zien krijgt: ${request.developerLoopbackReason}",
        )
    }

    @Test
    fun `unreadable parent story skips the subtask instead of dispatching an agent`() {
        // SF-1560: een mislukte parent-lees mag niet als "geen parent" gelezen worden -- dan worden
        // de pauze- en foutpoort van de story overgeslagen en start er alsnog een betaalde agent.
        // parentKey is gezet, maar SF-1 zit niet in de issue-lijst, dus FakeTrackerApi.getIssue gooit.
        val sub = issue("SF-8", type = "Task", subtaskType = "development", subtaskPhase = "start")
        val issueTracker = FakeTrackerApi(listOf(sub), parentKey = "SF-1", subtasks = listOf(sub))
        val runtime = FakeAgentRuntime(now)
        val service = service(issueTracker, runtime = runtime)
        val logs = ListAppender<ILoggingEvent>().also {
            it.start()
            (LoggerFactory.getLogger(SubtaskExecutionCoordinator::class.java) as Logger).addAppender(it)
        }

        val result = service.pollOnce()

        assertEquals(IssueProcessResult.Skipped("SF-8", "parent-unavailable"), result.issueResults.single())
        assertTrue(runtime.dispatches.isEmpty(), "er mag geen agent gedispatcht worden bij een onleesbare parent")
        val warnings = logs.list.filter { it.level == Level.WARN }
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().formattedMessage.contains("SF-1"), "warn-regel hoort de parent-key te noemen")
        assertNotNull(warnings.single().throwableProxy, "warn-regel hoort de onderliggende exception mee te geven")
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
            nl.vdzon.softwarefactory.core.contracts.SubtaskType.DEVELOPMENT,
            issueTracker.createdSubtasks.single().type,
        )
        assertEquals("start", issueTracker.lastUpdate("KAN-12-sub1").values[TrackerField.SUBTASK_PHASE])
        assertEquals(emptyList<Pair<String, String>>(), issueTracker.addedTags)
        assertEquals(9001, pullRequests.claimedComments.single())
    }
}
