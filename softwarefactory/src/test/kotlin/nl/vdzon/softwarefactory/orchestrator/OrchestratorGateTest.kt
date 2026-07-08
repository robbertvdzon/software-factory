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
 * Pickup-/gate-gedrag van de orchestrator: paused/error-skips, repo-resolutie,
 * workspace-comments, dispatch-context, budget-/credits-gates en fase-recovery van stories.
 *
 * Afgesplitst uit de voormalige OrchestratorServiceTest; wiring en fakes staan in
 * `nl.vdzon.softwarefactory.testsupport` ([OrchestratorTestHarness]).
 */
class OrchestratorGateTest : OrchestratorTestHarness() {
    @Test
    fun `poll skips paused and errored issues and dispatches empty phase to refiner`() {
        val issueTracker = FakeTrackerApi(
            listOf(
                issue("KAN-1", paused = true),
                issue("KAN-2", error = "blocked"),
                issue("KAN-3", phase = null),
            ),
        )
        val runtime = FakeAgentRuntime(now)
        val agentRuns = InMemoryAgentRunRepository()
        val service = service(issueTracker, runtime = runtime, agentRuns = agentRuns)

        val result = service.pollOnce()

        assertEquals(
            listOf(
                IssueProcessResult.Skipped("KAN-1", "paused"),
                IssueProcessResult.Skipped("KAN-2", "error"),
                IssueProcessResult.Dispatched("KAN-3", AgentRole.REFINER, "factory-KAN-3-refiner"),
            ),
            result.issueResults,
        )
        // v2 fase 2a: een verse story (geen AI Phase) start de refine-flow op het Story Phase-veld.
        assertEquals("refining", issueTracker.lastUpdate("KAN-3").values[TrackerField.STORY_PHASE])
        assertEquals(now, issueTracker.lastUpdate("KAN-3").values[TrackerField.AGENT_STARTED_AT])
        assertEquals("KAN-3", runtime.dispatches.single().labels["story-key"])
        assertEquals("refiner", runtime.dispatches.single().labels["role"])
        assertEquals(5, runtime.dispatches.single().aiLevel)
        assertEquals("claude-sonnet-5", runtime.dispatches.single().aiModel)
        assertEquals("medium", runtime.dispatches.single().aiEffort)
        assertEquals(listOf("factory-KAN-3-refiner" to 1L), runtime.logCaptures)
        assertEquals(1, agentRuns.countForRole(1, AgentRole.REFINER))
    }

    @Test
    fun `dispatching an agent moves the issue to the In progress lane`() {
        val issueTracker = FakeTrackerApi(listOf(issue("KAN-21", phase = null)))
        val service = service(issueTracker)

        val result = service.pollOnce()

        assertTrue(result.issueResults.single() is IssueProcessResult.Dispatched)
        assertEquals(listOf("KAN-21" to "In Progress"), issueTracker.transitions)
    }

    @Test
    fun `story with an empty repo field gets an error and is not dispatched`() {
        val issueTracker = FakeTrackerApi(listOf(issue("KAN-22", phase = null, repo = null)))
        val runtime = FakeAgentRuntime(now)

        val result = service(issueTracker, runtime = runtime).pollOnce()

        val res = result.issueResults.single()
        assertTrue(res is IssueProcessResult.Errored, "Verwacht Errored, kreeg $res")
        assertTrue((res as IssueProcessResult.Errored).message.contains("Repo"))
        assertTrue(runtime.dispatches.isEmpty(), "Geen dispatch zonder repo")
        assertTrue(issueTracker.transitions.isEmpty(), "Geen lane-transitie zonder repo")
    }

    @Test
    fun `a repo-field value not in the config is used directly as the repo url`() {
        val literalRepo = "git@github.com:robbertvdzon/direct.git"
        val issueTracker = FakeTrackerApi(listOf(issue("KAN-23", phase = null, repo = literalRepo)))
        val runtime = FakeAgentRuntime(now)

        val result = service(issueTracker, runtime = runtime).pollOnce()

        assertTrue(result.issueResults.single() is IssueProcessResult.Dispatched)
        assertEquals(literalRepo, runtime.dispatches.single().targetRepo)
    }

    @Test
    fun `posts workspace link when story workspace is created`() {
        val issueTracker = FakeTrackerApi(listOf(issue("KAN-21", phase = null)))
        val service = service(issueTracker)

        val result = service.pollOnce()

        assertEquals(IssueProcessResult.Dispatched("KAN-21", AgentRole.REFINER, "factory-KAN-21-refiner"), result.issueResults.single())
        val comment = issueTracker.postedComments.single()
        assertEquals("KAN-21", comment.first)
        assertTrue(comment.second.contains("Work folder aangemaakt"))
        assertTrue(comment.second.contains("/tmp/software-factory-test-workspaces/KAN-21/repo"))
        assertTrue(comment.second.contains("open -a \"IntelliJ IDEA\""))
    }

    @Test
    fun `does not repost workspace link when story already has workspace`() {
        val issueTracker = FakeTrackerApi(listOf(issue("KAN-22", phase = null)))
        val storyRuns = InMemoryStoryRunRepository()
        storyRuns.openOrCreate("KAN-22", "git@example/repo.git")
        storyRuns.updateWorkspace(
            storyRunId = 1,
            workspacePath = "/tmp/existing-workspace",
            branchName = "ai/KAN-22",
            baseBranch = "main",
            branchPrefix = "ai/",
            previewUrlTemplate = null,
            previewNamespaceTemplate = null,
            previewDbSecretRecipe = null,
        )
        val service = service(issueTracker, storyRuns = storyRuns)

        service.pollOnce()

        assertTrue(issueTracker.postedComments.isEmpty())
    }

    @Test
    fun `recovers old missing container issue error by returning to previous phase`() {
        val issueTracker = FakeTrackerApi(
            listOf(
                issue(
                    "KAN-20",
                    phase = "developing",
                    error = "[ORCHESTRATOR] Geen actieve container gevonden voor developing; handmatige triage nodig.",
                    agentStartedAt = now.minusHours(2),
                ),
            ),
        )
        val service = service(issueTracker)

        val result = service.pollOnce()

        assertEquals(listOf(IssueProcessResult.Recovered("KAN-20", "refined-finished")), result.issueResults)
        val update = issueTracker.lastUpdate("KAN-20")
        assertEquals(null, update.values[TrackerField.ERROR])
        assertEquals("refined-finished", update.values[TrackerField.AI_PHASE])
    }

    @Test
    fun `recovery keeps the planner question instead of forcing planned`() {
        val issueTracker = FakeTrackerApi(
            listOf(issue("KAN-50", storyPhase = "planning", agentStartedAt = now.minusMinutes(2))),
        )
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("KAN-50", "git@example/repo.git")
        val agentRuns = InMemoryAgentRunRepository().apply {
            addEnded(storyRun.id, AgentRole.PLANNER, outcome = "questions", summary = "(dummy) vraag aan PO")
        }
        val runtime = FakeAgentRuntime(now) // planner draait niet meer
        val service = service(issueTracker, runtime = runtime, storyRuns = storyRuns, agentRuns = agentRuns)

        val result = service.pollOnce()

        assertEquals(listOf(IssueProcessResult.Recovered("KAN-50", "planned-with-questions")), result.issueResults)
        assertEquals(
            "planned-with-questions",
            issueTracker.lastUpdate("KAN-50").values[TrackerField.STORY_PHASE],
        )
    }

    @Test
    fun `dispatch task context includes issue tracker description and only relevant unprocessed comments`() {
        val issue = issue(
            "KAN-15",
            phase = null,
            description = "Als PO wil ik duidelijke context in task markdown.",
            comments = listOf(
                TrackerComment("user-1", null, "Robbert", "Dit antwoord moet de refiner meenemen.", null),
                TrackerComment("user-2", null, "Robbert", "Dit antwoord is al verwerkt.", null),
                TrackerComment("review-1", null, "Reviewer", "[REVIEWER] niet relevant voor refiner.", null),
            ),
        )
        val issueTracker = FakeTrackerApi(listOf(issue))
        val runtime = FakeAgentRuntime(now)
        val processed = InMemoryProcessedCommentStore().apply {
            markProcessed("KAN-15", "user-2", AgentRole.REFINER)
        }
        val service = service(issueTracker, runtime = runtime, processedCommentStore = processed)

        service.pollOnce()

        val context = runtime.dispatches.single().trackerContext.orEmpty()
        assertTrue(context.contains("Als PO wil ik duidelijke context"))
        assertTrue(context.contains("Dit antwoord moet de refiner meenemen."))
        assertFalse(context.contains("Dit antwoord is al verwerkt."))
        assertFalse(context.contains("niet relevant voor refiner"))
    }

    @Test
    fun `system credits pause prevents new dispatches`() {
        val issueTracker = FakeTrackerApi(listOf(issue("KAN-14", phase = null)))
        val runtime = FakeAgentRuntime(now)
        val credits = FakeCreditsPauseCoordinator().apply {
            pause = CreditsPause(now.plusMinutes(15), "credits exhausted")
        }
        val service = service(issueTracker, runtime = runtime, creditsPauseCoordinator = credits)

        val result = service.pollOnce()

        assertEquals(listOf(IssueProcessResult.Skipped("KAN-14", "credits-paused")), result.issueResults)
        assertEquals(emptyList<AgentDispatchRequest>(), runtime.dispatches)
    }

    @Test
    fun `budget cap prevents dispatch`() {
        val issueTracker = FakeTrackerApi(listOf(issue("KAN-15", phase = null)))
        val runtime = FakeAgentRuntime(now)
        val costMonitor = FakeCostMonitor().apply { paused = true }
        val service = service(issueTracker, runtime = runtime, costMonitor = costMonitor)

        val result = service.pollOnce()

        assertEquals(listOf(IssueProcessResult.Skipped("KAN-15", "budget-exceeded")), result.issueResults)
        assertEquals(emptyList<AgentDispatchRequest>(), runtime.dispatches)
    }
}
