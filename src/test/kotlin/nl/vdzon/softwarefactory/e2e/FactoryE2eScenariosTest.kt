package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.tracker.AgentRole
import nl.vdzon.softwarefactory.tracker.TrackerField
import nl.vdzon.softwarefactory.orchestrator.IssueProcessResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FactoryE2eScenariosTest {
    @Test
    fun `happy path runs from empty phase to tested successfully with dummy agents`() {
        val harness = FactoryE2eHarness()
        harness.addIssue()

        harness.pollAndComplete(AgentRole.REFINER, ScriptedOutcomes.refinerOk())
        harness.pollAndComplete(AgentRole.DEVELOPER, ScriptedOutcomes.developerOk())
        harness.pollAndComplete(AgentRole.REVIEWER, ScriptedOutcomes.reviewerOk())
        harness.pollAndComplete(AgentRole.TESTER, ScriptedOutcomes.testerOk())

        val issue = harness.issueTracker.getIssue(FactoryE2eHarness.DEFAULT_STORY_KEY)
        assertEquals("tested-successfully", issue.fields.aiPhase)
        assertEquals(
            IssueProcessResult.Skipped(FactoryE2eHarness.DEFAULT_STORY_KEY, "tested-successfully"),
            harness.poll().issueResults.single(),
        )
        assertTrue(issue.comments.any { it.body.startsWith("[REFINER]") })
        assertTrue(issue.comments.any { it.body.startsWith("[DEVELOPER]") })
        assertTrue(issue.comments.any { it.body.startsWith("[REVIEWER]") })
        assertTrue(issue.comments.any { it.body.startsWith("[TESTER]") })

        val testerDispatch = harness.docker.dispatches.last { it.role == AgentRole.TESTER }
        assertEquals("https://app-pr-1.example.com", testerDispatch.previewUrl)
        assertEquals("app-pr-1", testerDispatch.previewNamespace)
        assertEquals(1, testerDispatch.prNumber)
        assertNotNull(harness.storyRuns.activeRuns().single().prNumber)
    }

    @Test
    fun `reviewer and tester feedback loop back through developer deterministically`() {
        val harness = FactoryE2eHarness()
        harness.addIssue()

        harness.pollAndComplete(AgentRole.REFINER, ScriptedOutcomes.refinerOk())
        harness.pollAndComplete(AgentRole.DEVELOPER, ScriptedOutcomes.developerOk())
        harness.pollAndComplete(AgentRole.REVIEWER, ScriptedOutcomes.reviewerFeedback())

        val reviewerLoopback = harness.pollExpectDispatch(AgentRole.DEVELOPER)
        assertTrue(reviewerLoopback.developerLoopbackReason.orEmpty().contains("[REVIEWER]"))
        harness.completeRunning(AgentRole.DEVELOPER, ScriptedOutcomes.developerOk())

        harness.pollAndComplete(AgentRole.REVIEWER, ScriptedOutcomes.reviewerOk())
        harness.pollAndComplete(AgentRole.TESTER, ScriptedOutcomes.testerBug())

        val testerLoopback = harness.pollExpectDispatch(AgentRole.DEVELOPER)
        assertTrue(testerLoopback.developerLoopbackReason.orEmpty().contains("[TESTER]"))
        harness.completeRunning(AgentRole.DEVELOPER, ScriptedOutcomes.developerOk())

        harness.pollAndComplete(AgentRole.REVIEWER, ScriptedOutcomes.reviewerOk())
        harness.pollAndComplete(AgentRole.TESTER, ScriptedOutcomes.testerOk())

        val issue = harness.issueTracker.getIssue(FactoryE2eHarness.DEFAULT_STORY_KEY)
        assertEquals("tested-successfully", issue.fields.aiPhase)
        assertEquals(3, harness.docker.dispatches.count { it.role == AgentRole.DEVELOPER })
        assertTrue(issue.comments.any { it.body.contains("feedback") })
        assertTrue(issue.comments.any { it.body.contains("bug") })
    }

    @Test
    fun `budget pause can be resumed with BUDGET comment and continues the flow`() {
        val harness = FactoryE2eHarness()
        harness.addIssue(budget = 3_000)

        harness.pollAndComplete(
            AgentRole.REFINER,
            ScriptedOutcomes.refinerOk(inputTokens = 4_000, outputTokens = 0),
        )

        val pausedIssue = harness.issueTracker.getIssue(FactoryE2eHarness.DEFAULT_STORY_KEY)
        assertTrue(pausedIssue.fields.paused)
        assertEquals(4_000L, pausedIssue.fields.aiTokensUsed)
        assertTrue(pausedIssue.comments.any { it.body.startsWith("[COST-MONITOR]") && it.body.contains("100%") })
        assertEquals(
            IssueProcessResult.Skipped(FactoryE2eHarness.DEFAULT_STORY_KEY, "paused"),
            harness.poll().issueResults.single(),
        )

        harness.addUserComment(body = "BUDGET=10000")

        val developerDispatch = harness.pollExpectDispatch(AgentRole.DEVELOPER)
        val resumedIssue = harness.issueTracker.getIssue(FactoryE2eHarness.DEFAULT_STORY_KEY)
        assertFalse(resumedIssue.fields.paused)
        assertEquals(10_000L, resumedIssue.fields.aiTokenBudget)
        assertEquals(AgentRole.DEVELOPER, developerDispatch.role)
        assertTrue(
            harness.issueTracker.fieldUpdates.any { (_, update) ->
                update.values[TrackerField.AI_TOKEN_BUDGET] == 10_000L &&
                    update.values[TrackerField.PAUSED] == false
            },
        )
    }

    @Test
    fun `PR factory comment is claimed passed to developer and marked done`() {
        val harness = FactoryE2eHarness()
        harness.addIssue()
        harness.pollAndComplete(AgentRole.REFINER, ScriptedOutcomes.refinerOk())
        harness.pollAndComplete(AgentRole.DEVELOPER, ScriptedOutcomes.developerOk())
        harness.pollAndComplete(AgentRole.REVIEWER, ScriptedOutcomes.reviewerOk())
        harness.pollAndComplete(AgentRole.TESTER, ScriptedOutcomes.testerOk())

        harness.github.addComment(1, "@factory maak de lege-state tekst duidelijker")
        val triggerResult = harness.poll().issueResults

        assertTrue(triggerResult.any { it is IssueProcessResult.PrCommentTriggered })
        val developerDispatch = harness.pollExpectDispatch(AgentRole.DEVELOPER)
        assertEquals("comment", developerDispatch.agentMode)
        assertTrue(developerDispatch.prCommentContext.orEmpty().contains("lege-state tekst"))

        harness.completeRunning(AgentRole.DEVELOPER, ScriptedOutcomes.developerOk())

        assertEquals(AgentRole.REVIEWER, harness.pollExpectDispatch(AgentRole.REVIEWER).role)
    }

    private fun FactoryE2eHarness.pollAndComplete(role: AgentRole, outcome: ScriptedAgentOutcome) {
        pollExpectDispatch(role)
        completeRunning(role, outcome)
    }

    private fun FactoryE2eHarness.pollExpectDispatch(role: AgentRole): nl.vdzon.softwarefactory.orchestrator.AgentDispatchRequest {
        val result = poll().issueResults.single()
        assertTrue(result is IssueProcessResult.Dispatched, "Expected dispatch for $role but got $result")
        val dispatched = result as IssueProcessResult.Dispatched
        assertEquals(role, dispatched.role)
        return docker.dispatches.last()
    }
}
