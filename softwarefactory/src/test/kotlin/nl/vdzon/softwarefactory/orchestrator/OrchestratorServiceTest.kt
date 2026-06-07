package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.orchestrator.models.*
import nl.vdzon.softwarefactory.orchestrator.services.*

import nl.vdzon.softwarefactory.orchestrator.*
import nl.vdzon.softwarefactory.orchestrator.*
import nl.vdzon.softwarefactory.orchestrator.*

import nl.vdzon.softwarefactory.orchestrator.AgentRuntime
import nl.vdzon.softwarefactory.orchestrator.StoryRunRepository
import nl.vdzon.softwarefactory.orchestrator.AgentRunRepository
import nl.vdzon.softwarefactory.orchestrator.CostMonitor
import nl.vdzon.softwarefactory.orchestrator.CreditsPauseCoordinator
import nl.vdzon.softwarefactory.orchestrator.services.ManualCommandProcessor
import nl.vdzon.softwarefactory.orchestrator.models.OrchestratorSettings
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.github.PullRequestInfo
import nl.vdzon.softwarefactory.youtrack.AgentRole
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.youtrack.TrackerComment
import nl.vdzon.softwarefactory.youtrack.TrackerFieldUpdate
import nl.vdzon.softwarefactory.youtrack.TrackerIssue
import nl.vdzon.softwarefactory.youtrack.TrackerIssueFields
import nl.vdzon.softwarefactory.youtrack.TrackerField
import nl.vdzon.softwarefactory.youtrack.services.ProcessedCommentService
import nl.vdzon.softwarefactory.youtrack.repositories.ProcessedCommentStore
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.docs.DeploymentConfig
import nl.vdzon.softwarefactory.orchestrator.services.OrchestratorService
import nl.vdzon.softwarefactory.orchestrator.PreparedStoryWorkspace
import nl.vdzon.softwarefactory.orchestrator.RepositorySyncResult
import nl.vdzon.softwarefactory.orchestrator.StoryWorkspaceApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.nio.file.Path

class OrchestratorServiceTest {
    private val now: OffsetDateTime = OffsetDateTime.parse("2026-05-23T20:00:00Z")
    private val clock: Clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC)

    @Test
    fun `poll skips paused and errored issues and dispatches empty phase to refiner`() {
        val issueTracker = FakeYouTrackApi(
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
        assertEquals("claude-haiku-4-5", runtime.dispatches.single().aiModel)
        assertEquals("medium", runtime.dispatches.single().aiEffort)
        assertEquals(listOf("factory-KAN-3-refiner" to 1L), runtime.logCaptures)
        assertEquals(1, agentRuns.countForRole(1, AgentRole.REFINER))
    }

    @Test
    fun `fase 2a story refine flow waits and dispatches on the Story Phase field`() {
        val issueTracker = FakeYouTrackApi(
            listOf(
                issue("KAN-30", storyPhase = "refined-with-questions"),
                issue("KAN-31", storyPhase = "refined"),
                issue("KAN-33", storyPhase = "questions-answered"),
                issue("KAN-34", storyPhase = "refined-rejected"),
            ),
        )
        val runtime = FakeAgentRuntime(now)
        val service = service(issueTracker, runtime = runtime)

        val result = service.pollOnce()

        assertEquals(
            listOf(
                IssueProcessResult.Skipped("KAN-30", "waiting-for-user"),
                IssueProcessResult.Skipped("KAN-31", "waiting-for-approval"),
                IssueProcessResult.Dispatched("KAN-33", AgentRole.REFINER, "factory-KAN-33-refiner"),
                IssueProcessResult.Dispatched("KAN-34", AgentRole.REFINER, "factory-KAN-34-refiner"),
            ),
            result.issueResults,
        )
        // Re-dispatch (questions-answered / refined-rejected) zet de actieve status op het Story Phase-veld.
        assertEquals("refining", issueTracker.lastUpdate("KAN-33").values[TrackerField.STORY_PHASE])
        assertEquals("refining", issueTracker.lastUpdate("KAN-34").values[TrackerField.STORY_PHASE])
        assertEquals(listOf("refiner", "refiner"), runtime.dispatches.map { it.labels["role"] })
    }

    @Test
    fun `fase 2b story plan flow dispatches planner and is terminal on planning-approved`() {
        val issueTracker = FakeYouTrackApi(
            listOf(
                issue("KAN-40", storyPhase = "refined-approved"),
                issue("KAN-41", storyPhase = "planned-with-questions"),
                issue("KAN-42", storyPhase = "planned"),
                issue("KAN-43", storyPhase = "planning-approved"),
                issue("KAN-44", storyPhase = "planning-rejected"),
            ),
        )
        val runtime = FakeAgentRuntime(now)
        val service = service(issueTracker, runtime = runtime)

        val result = service.pollOnce()

        assertEquals(
            listOf(
                IssueProcessResult.Dispatched("KAN-40", AgentRole.PLANNER, "factory-KAN-40-planner"),
                IssueProcessResult.Skipped("KAN-41", "waiting-for-user"),
                IssueProcessResult.Skipped("KAN-42", "waiting-for-approval"),
                IssueProcessResult.Skipped("KAN-43", "refinement-done"),
                IssueProcessResult.Dispatched("KAN-44", AgentRole.PLANNER, "factory-KAN-44-planner"),
            ),
            result.issueResults,
        )
        // refined-approved / planning-rejected starten de planner op het Story Phase-veld.
        assertEquals("planning", issueTracker.lastUpdate("KAN-40").values[TrackerField.STORY_PHASE])
        assertEquals("planning", issueTracker.lastUpdate("KAN-44").values[TrackerField.STORY_PHASE])
        assertEquals(listOf("planner", "planner"), runtime.dispatches.map { it.labels["role"] })
    }

    @Test
    fun `posts workspace link when story workspace is created`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-21", phase = null)))
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
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-22", phase = null)))
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
    fun `dispatches completed phases to the next role and respects concurrency caps`() {
        val issueTracker = FakeYouTrackApi(
            listOf(
                issue("KAN-4", phase = "developed"),
                issue("KAN-5", phase = "review-finished"),
                issue("KAN-6", phase = "refined-finished"),
                issue("KAN-7", phase = "tested-successfully"),
            ),
        )
        val runtime = FakeAgentRuntime(now).apply {
            runningByRole[AgentRole.DEVELOPER] = 2
        }
        val service = service(issueTracker, runtime = runtime)

        val result = service.pollOnce()

        assertEquals(IssueProcessResult.Dispatched("KAN-4", AgentRole.REVIEWER, "factory-KAN-4-reviewer"), result.issueResults[0])
        assertEquals(IssueProcessResult.Dispatched("KAN-5", AgentRole.TESTER, "factory-KAN-5-tester"), result.issueResults[1])
        assertEquals(IssueProcessResult.Skipped("KAN-6", "concurrency-cap"), result.issueResults[2])
        assertEquals(IssueProcessResult.Dispatched("KAN-7", AgentRole.SUMMARIZER, "factory-KAN-7-summarizer"), result.issueResults[3])
        assertEquals(listOf(AgentRole.REVIEWER, AgentRole.TESTER, AgentRole.SUMMARIZER), runtime.dispatches.map { it.role })
    }

    @Test
    fun `recovers active phase forward when DB already has a successful run`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-7", phase = "reviewing")))
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("KAN-7", "git@example/repo.git")
        val agentRuns = InMemoryAgentRunRepository().apply {
            addEnded(storyRun.id, AgentRole.REVIEWER, outcome = "review-finished", summary = "OK")
        }
        val service = service(issueTracker, storyRuns = storyRuns, agentRuns = agentRuns)

        val result = service.pollOnce()

        assertEquals(listOf(IssueProcessResult.Recovered("KAN-7", "review-finished")), result.issueResults)
        assertEquals("review-finished", issueTracker.lastUpdate("KAN-7").values[TrackerField.AI_PHASE])
    }

    @Test
    fun `retries transient failure by returning to the previous completed phase`() {
        val issueTracker = FakeYouTrackApi(
            listOf(issue("KAN-8", phase = "testing", agentStartedAt = now.minusMinutes(5))),
        )
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("KAN-8", "git@example/repo.git")
        val agentRuns = InMemoryAgentRunRepository().apply {
            addEnded(storyRun.id, AgentRole.TESTER, outcome = "failed", summary = "timeout while waiting")
        }
        val service = service(issueTracker, storyRuns = storyRuns, agentRuns = agentRuns)

        val result = service.pollOnce()

        assertEquals(listOf(IssueProcessResult.Recovered("KAN-8", "review-finished")), result.issueResults)
        assertEquals("review-finished", issueTracker.lastUpdate("KAN-8").values[TrackerField.AI_PHASE])
    }

    @Test
    fun `active phase without container waits briefly before retrying previous phase`() {
        val issueTracker = FakeYouTrackApi(
            listOf(
                issue("KAN-17", phase = "developing", agentStartedAt = now.minusSeconds(30)),
                issue("KAN-18", phase = "developing", agentStartedAt = now.minusMinutes(2)),
            ),
        )
        val storyRuns = InMemoryStoryRunRepository()
        storyRuns.openOrCreate("KAN-17", "git@example/repo.git")
        storyRuns.openOrCreate("KAN-18", "git@example/repo.git")
        val service = service(issueTracker, storyRuns = storyRuns)

        val result = service.pollOnce()

        assertEquals(IssueProcessResult.Skipped("KAN-17", "waiting-for-active-phase-recovery"), result.issueResults[0])
        assertEquals(IssueProcessResult.Recovered("KAN-18", "refined-finished"), result.issueResults[1])
        assertFalse(issueTracker.updates.containsKey("KAN-17"))
        assertEquals("refined-finished", issueTracker.lastUpdate("KAN-18").values[TrackerField.AI_PHASE])
    }

    @Test
    fun `active phase with open DB run waits for result file poller instead of writing error`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-19", phase = "developing", agentStartedAt = now.minusMinutes(5))))
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("KAN-19", "git@example/repo.git")
        val agentRuns = InMemoryAgentRunRepository().apply {
            recordStarted(storyRun.id, AgentRole.DEVELOPER, "factory-KAN-19-developer", null, null, 5, "/tmp/workspace")
        }
        val service = service(issueTracker, storyRuns = storyRuns, agentRuns = agentRuns)

        val result = service.pollOnce()

        assertEquals(listOf(IssueProcessResult.Skipped("KAN-19", "awaiting-agent-completion")), result.issueResults)
        assertFalse(issueTracker.updates.containsKey("KAN-19"))
    }

    @Test
    fun `recovers old missing container issue error by returning to previous phase`() {
        val issueTracker = FakeYouTrackApi(
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
    fun `writes Error for hard timeout and developer loopback cap`() {
        val issueTracker = FakeYouTrackApi(
            listOf(
                issue("KAN-9", phase = "developing", agentStartedAt = now.minusMinutes(61)),
                issue("KAN-10", phase = "reviewed-with-feedback-for-developer"),
            ),
        )
        val storyRuns = InMemoryStoryRunRepository()
        val cappedRun = storyRuns.openOrCreate("KAN-10", "git@example/repo.git")
        val agentRuns = InMemoryAgentRunRepository().apply {
            repeat(6) { addEnded(cappedRun.id, AgentRole.DEVELOPER, outcome = "developed", summary = "done") }
        }
        val service = service(issueTracker, storyRuns = storyRuns, agentRuns = agentRuns)

        val result = service.pollOnce()

        assertTrue(result.issueResults[0] is IssueProcessResult.Errored)
        assertTrue(issueTracker.lastUpdate("KAN-9").values[TrackerField.ERROR].toString().contains("Hard timeout"))
        assertTrue(result.issueResults[1] is IssueProcessResult.Errored)
        assertTrue(issueTracker.lastUpdate("KAN-10").values[TrackerField.ERROR].toString().contains("Developer-loopback cap"))
    }

    @Test
    fun `uses story developer loopback override before writing cap error`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-10", phase = "reviewed-with-feedback-for-developer", maxDeveloperLoopbacks = 7)))
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
    fun `detects merged PR transitions issue tracker to Done and closes story run`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-11", phase = "summary-finished")))
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("KAN-11", "git@github.com:robbertvdzon/sample-build-project.git")
        storyRuns.updatePullRequest(
            storyRun.id,
            "ai/KAN-11",
            123,
            "https://github.com/robbertvdzon/sample-build-project/pull/123",
            "main",
            "ai/",
            "https://sample-pr-{pr_num}.example.com",
            "sample-pr-{pr_num}",
            null,
        )
        val pullRequests = FakeGitHubApi(mergedPrs = setOf(123))
        val previewCleaner = FakePreviewEnvironmentCleaner()
        val service = service(issueTracker, storyRuns = storyRuns, pullRequests = pullRequests, previewCleaner = previewCleaner)

        val result = service.pollOnce()

        assertEquals(IssueProcessResult.Skipped("KAN-11", "summary-finished"), result.issueResults[0])
        assertEquals(IssueProcessResult.Merged("KAN-11", 123), result.issueResults[1])
        assertEquals(listOf("sample-pr-123"), previewCleaner.cleanedNamespaces)
        assertEquals("Done", issueTracker.transitions.single().second)
        assertEquals("merged", storyRuns.closed.single().second)
    }

    @Test
    fun `PR factory comment is claimed and routes story back to developer feedback phase`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-12", phase = "summary-finished")))
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
        assertEquals("tested-with-feedback-for-developer", issueTracker.lastUpdate("KAN-12").values[TrackerField.AI_PHASE])
        assertEquals(9001, pullRequests.claimedComments.single())
    }

    @Test
    fun `claimed PR comments are passed to developer as comment mode task bundle`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-16", phase = "tested-with-feedback-for-developer")))
        val runtime = FakeAgentRuntime(now)
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("KAN-16", "git@github.com:robbertvdzon/sample-build-project.git")
        storyRuns.updatePullRequest(
            storyRun.id,
            "ai/KAN-16",
            126,
            "https://github.com/robbertvdzon/sample-build-project/pull/126",
            "main",
            "ai/",
            null,
            null,
            null,
        )
        val pullRequests = FakeGitHubApi(
            claimedCommentsByPr = mapOf(126 to listOf(PullRequestComment(9901, "@factory maak de knop duidelijker"))),
        )
        val service = service(issueTracker, runtime = runtime, storyRuns = storyRuns, pullRequests = pullRequests)

        val result = service.pollOnce()

        assertEquals(IssueProcessResult.Dispatched("KAN-16", AgentRole.DEVELOPER, "factory-KAN-16-developer"), result.issueResults.single())
        val dispatch = runtime.dispatches.single()
        assertEquals("comment", dispatch.agentMode)
        assertTrue(dispatch.prCommentContext.orEmpty().contains("PR comment 9901"))
        assertTrue(dispatch.prCommentContext.orEmpty().contains("maak de knop duidelijker"))
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
        val issueTracker = FakeYouTrackApi(listOf(issue))
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
    fun `tester dispatch receives rendered preview context from PR metadata`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-13", phase = "review-finished")))
        val runtime = FakeAgentRuntime(now)
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("KAN-13", "git@github.com:robbertvdzon/sample-build-project.git")
        storyRuns.updatePullRequest(
            storyRun.id,
            "ai/KAN-13",
            77,
            "https://github.com/robbertvdzon/sample-build-project/pull/77",
            "main",
            "ai/",
            "https://sample-pr-{pr_num}.example.com",
            "sample-pr-{pr_num}",
            "printf db-url",
        )
        val service = service(issueTracker, runtime = runtime, storyRuns = storyRuns)

        val result = service.pollOnce()

        assertEquals(IssueProcessResult.Dispatched("KAN-13", AgentRole.TESTER, "factory-KAN-13-tester"), result.issueResults.single())
        val dispatch = runtime.dispatches.single()
        assertEquals(77, dispatch.prNumber)
        assertEquals("main", dispatch.baseBranch)
        assertEquals("ai/", dispatch.branchPrefix)
        assertEquals("https://sample-pr-77.example.com", dispatch.previewUrl)
        assertEquals("sample-pr-77", dispatch.previewNamespace)
    }

    @Test
    fun `system credits pause prevents new dispatches`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-14", phase = null)))
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
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-15", phase = null)))
        val runtime = FakeAgentRuntime(now)
        val costMonitor = FakeCostMonitor().apply { paused = true }
        val service = service(issueTracker, runtime = runtime, costMonitor = costMonitor)

        val result = service.pollOnce()

        assertEquals(listOf(IssueProcessResult.Skipped("KAN-15", "budget-exceeded")), result.issueResults)
        assertEquals(emptyList<AgentDispatchRequest>(), runtime.dispatches)
    }

    @Test
    fun `terminal subtask chains to next not-done sibling`() {
        val s1 = issue("PF-7", type = "Task", subtaskPhase = "manual-action-done")
        val s2 = issue("PF-8", type = "Task", subtaskPhase = null)
        val s3 = issue("PF-9", type = "Task", subtaskPhase = "review-approved")
        val issueTracker = FakeYouTrackApi(listOf(s1, s2, s3), parentKey = "PF-1", subtasks = listOf(s1, s2, s3))

        val result = service(issueTracker).processIssue(s1)

        assertEquals(IssueProcessResult.Chained("PF-7", "PF-8"), result)
        assertEquals(listOf("PF-8" to "ai-development"), issueTracker.addedTags)
        assertEquals(listOf("PF-7" to "ai-development"), issueTracker.removedTags)
    }

    @Test
    fun `last terminal subtask untags itself and chains to nothing`() {
        val only = issue("PF-9", type = "Task", subtaskPhase = "summary-approved")
        val issueTracker = FakeYouTrackApi(listOf(only), parentKey = "PF-1", subtasks = listOf(only))

        val result = service(issueTracker).processIssue(only)

        assertEquals(IssueProcessResult.Chained("PF-9", null), result)
        assertEquals(emptyList<Pair<String, String>>(), issueTracker.addedTags)
        assertEquals(listOf("PF-9" to "ai-development"), issueTracker.removedTags)
    }

    @Test
    fun `non-terminal subtask is skipped pending execution`() {
        val sub = issue("PF-7", type = "Task", subtaskPhase = "developing")
        val issueTracker = FakeYouTrackApi(listOf(sub))

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Skipped("PF-7", "subtask-execution-not-yet-implemented"), result)
        assertEquals(emptyList<Pair<String, String>>(), issueTracker.addedTags)
        assertEquals(emptyList<Pair<String, String>>(), issueTracker.removedTags)
    }

    private fun service(
        issueTracker: FakeYouTrackApi,
        runtime: FakeAgentRuntime = FakeAgentRuntime(now),
        storyRuns: InMemoryStoryRunRepository = InMemoryStoryRunRepository(),
        agentRuns: InMemoryAgentRunRepository = InMemoryAgentRunRepository(),
        pullRequests: FakeGitHubApi = FakeGitHubApi(),
        processedCommentStore: InMemoryProcessedCommentStore = InMemoryProcessedCommentStore(),
        previewCleaner: FakePreviewEnvironmentCleaner = FakePreviewEnvironmentCleaner(),
        storyWorkspaceService: StoryWorkspaceApi = FakeStoryWorkspaceService(),
        costMonitor: FakeCostMonitor = FakeCostMonitor(),
        creditsPauseCoordinator: FakeCreditsPauseCoordinator = FakeCreditsPauseCoordinator(),
        manualCommandProcessor: ManualCommandProcessor = NoopManualCommandProcessor(),
    ): OrchestratorService =
        OrchestratorService(
            issueTrackerClient = issueTracker,
            agentRuntime = runtime,
            storyRunRepository = storyRuns,
            agentRunRepository = agentRuns,
            pullRequestClient = pullRequests,
            processedCommentService = ProcessedCommentService(issueTracker, processedCommentStore),
            previewApi = previewCleaner,
            storyWorkspaceService = storyWorkspaceService,
            costMonitor = costMonitor,
            creditsPauseCoordinator = creditsPauseCoordinator,
            manualCommandProcessor = manualCommandProcessor,
            settings = OrchestratorSettings(
                pollInterval = java.time.Duration.ofSeconds(15),
                maxParallelRefiner = 1,
                maxParallelDeveloper = 2,
                maxParallelReviewer = 2,
                maxParallelTester = 1,
                maxParallelTotal = 4,
                maxDeveloperLoopbacks = 5,
                maxTransientRetries = 2,
                hardTimeout = java.time.Duration.ofMinutes(60),
                costMonitorInterval = java.time.Duration.ofMinutes(5),
                creditsPauseDefault = java.time.Duration.ofMinutes(30),
            ),
            clock = clock,
        )

    private fun issue(
        key: String,
        phase: String? = null,
        storyPhase: String? = null,
        paused: Boolean = false,
        error: String? = null,
        targetRepo: String? = "git@example/repo.git",
        agentStartedAt: OffsetDateTime? = null,
        description: String? = "Beschrijving voor $key",
        comments: List<TrackerComment> = emptyList(),
        aiSupplier: String = "claude",
        maxDeveloperLoopbacks: Int? = null,
        type: String? = null,
        subtaskPhase: String? = null,
    ): TrackerIssue =
        TrackerIssue(
            key = key,
            summary = "Story $key",
            description = description,
            status = "Develop",
            fields = TrackerIssueFields(
                targetRepo = targetRepo,
                aiSupplier = aiSupplier,
                aiPhase = phase,
                aiLevel = 5,
                aiMaxDeveloperLoopbacks = maxDeveloperLoopbacks,
                aiTokenBudget = 100000,
                aiTokensUsed = 0,
                agentStartedAt = agentStartedAt,
                paused = paused,
                error = error,
                storyPhase = storyPhase,
                type = type,
                subtaskPhase = subtaskPhase,
            ),
            comments = comments,
        )

    private class FakeYouTrackApi(
        private val issues: List<TrackerIssue>,
        private val parentKey: String? = null,
        private val subtasks: List<TrackerIssue> = emptyList(),
    ) : YouTrackApi {
        val updates: MutableMap<String, MutableList<TrackerFieldUpdate>> = mutableMapOf()
        val transitions: MutableList<Pair<String, String>> = mutableListOf()
        val postedComments: MutableList<Pair<String, String>> = mutableListOf()
        val addedTags: MutableList<Pair<String, String>> = mutableListOf()
        val removedTags: MutableList<Pair<String, String>> = mutableListOf()

        override fun findAiIssues(projectKey: String, maxResults: Int): List<TrackerIssue> =
            issues

        override fun getIssue(issueKey: String): TrackerIssue =
            issues.first { it.key == issueKey }

        override fun parentStoryKey(subtaskKey: String): String? = parentKey

        override fun subtasksOf(parentKey: String): List<TrackerIssue> = subtasks

        override fun addTag(issueKey: String, tag: String) {
            addedTags += issueKey to tag
        }

        override fun removeTag(issueKey: String, tag: String) {
            removedTags += issueKey to tag
        }

        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
            updates.getOrPut(issueKey) { mutableListOf() } += update
        }

        override fun transitionIssue(issueKey: String, statusName: String) {
            transitions += issueKey to statusName
        }

        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment =
            throw UnsupportedOperationException()

        override fun postComment(issueKey: String, message: String): TrackerComment {
            postedComments += issueKey to message
            return TrackerComment("posted-${postedComments.size}", null, "Factory", message, null)
        }

        override fun hasProcessedCommentMarker(commentId: String, role: AgentRole): Boolean =
            false

        override fun markCommentProcessed(commentId: String, role: AgentRole): Boolean =
            false

        fun lastUpdate(issueKey: String): TrackerFieldUpdate =
            updates.getValue(issueKey).last()
    }

    private class InMemoryProcessedCommentStore : ProcessedCommentStore {
        private val processed = mutableSetOf<Triple<String, String, AgentRole>>()

        override fun isProcessed(storyKey: String, commentId: String, role: AgentRole): Boolean =
            Triple(storyKey, commentId, role) in processed

        override fun markProcessed(storyKey: String, commentId: String, role: AgentRole) {
            processed += Triple(storyKey, commentId, role)
        }
    }

    private class FakeAgentRuntime(
        private val now: OffsetDateTime,
    ) : AgentRuntime {
        val dispatches: MutableList<AgentDispatchRequest> = mutableListOf()
        val logCaptures: MutableList<Pair<String, Long>> = mutableListOf()
        val runningByRole: MutableMap<AgentRole, Int> = mutableMapOf()

        override fun dispatch(request: AgentDispatchRequest): AgentDispatchResult {
            dispatches += request
            return AgentDispatchResult(
                containerName = "factory-${request.storyKey}-${request.role.markerKeyPart}",
                startedAt = now,
            )
        }

        override fun captureLogs(containerName: String, agentRunId: Long) {
            logCaptures += containerName to agentRunId
        }

        override fun isAgentRunning(storyKey: String, role: AgentRole): Boolean =
            false

        override fun isContainerRunning(containerName: String): Boolean =
            false

        override fun isAnyAgentRunningForStory(storyKey: String): Boolean =
            false

        override fun runningCount(role: AgentRole?): Int =
            if (role == null) runningByRole.values.sum() else runningByRole[role] ?: 0

        override fun killForStory(storyKey: String): Int =
            0
    }

    private class InMemoryStoryRunRepository : StoryRunRepository {
        private val runs = mutableMapOf<String, StoryRunRecord>()
        private var nextId = 1L
        val closed = mutableListOf<Pair<Long, String>>()

        override fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord =
            runs.getOrPut(storyKey) { StoryRunRecord(nextId++, storyKey, targetRepo) }

        override fun get(storyRunId: Long): StoryRunRecord? =
            runs.values.firstOrNull { it.id == storyRunId }

        override fun updatePullRequest(
            storyRunId: Long,
            branchName: String,
            prNumber: Int?,
            prUrl: String?,
            baseBranch: String?,
            branchPrefix: String?,
            previewUrlTemplate: String?,
            previewNamespaceTemplate: String?,
            previewDbSecretRecipe: String?,
        ) {
            val entry = runs.entries.first { it.value.id == storyRunId }
            entry.setValue(
                entry.value.copy(
                    branchName = branchName,
                    prNumber = prNumber,
                    prUrl = prUrl,
                    baseBranch = baseBranch,
                    branchPrefix = branchPrefix,
                    previewUrlTemplate = previewUrlTemplate,
                    previewNamespaceTemplate = previewNamespaceTemplate,
                    previewDbSecretRecipe = previewDbSecretRecipe,
                ),
            )
        }

        override fun updateWorkspace(
            storyRunId: Long,
            workspacePath: String,
            branchName: String,
            baseBranch: String?,
            branchPrefix: String?,
            previewUrlTemplate: String?,
            previewNamespaceTemplate: String?,
            previewDbSecretRecipe: String?,
        ) {
            val entry = runs.entries.first { it.value.id == storyRunId }
            entry.setValue(
                entry.value.copy(
                    workspacePath = workspacePath,
                    branchName = branchName,
                    baseBranch = baseBranch,
                    branchPrefix = branchPrefix,
                    previewUrlTemplate = previewUrlTemplate,
                    previewNamespaceTemplate = previewNamespaceTemplate,
                    previewDbSecretRecipe = previewDbSecretRecipe,
                ),
            )
        }

        override fun activePullRequests(): List<StoryRunRecord> =
            runs.values.filter { it.prNumber != null }

        override fun activeRuns(): List<StoryRunRecord> =
            runs.values.toList()

        override fun close(storyRunId: Long, finalStatus: String, endedAt: OffsetDateTime) {
            closed += storyRunId to finalStatus
            val entry = runs.entries.first { it.value.id == storyRunId }
            runs.remove(entry.key)
        }
    }

    private class InMemoryAgentRunRepository : AgentRunRepository {
        private val runs = mutableListOf<AgentRunRecord>()
        private var nextId = 1L

        override fun recordStarted(
            storyRunId: Long,
            role: AgentRole,
            containerName: String,
            model: String?,
            effort: String?,
            level: Int?,
            workspacePath: String?,
        ): Long {
            val id = nextId++
            runs += AgentRunRecord(
                id = id,
                storyRunId = storyRunId,
                role = role,
                containerName = containerName,
                startedAt = OffsetDateTime.now(),
                endedAt = null,
                outcome = null,
                summaryText = null,
                model = model,
                effort = effort,
                level = level,
                workspacePath = workspacePath,
            )
            return id
        }

        override fun complete(
            containerName: String,
            completion: AgentRunCompletionRecord,
            endedAt: OffsetDateTime,
        ): CompletedAgentRun? = null

        override fun addUsageToStoryRun(storyRunId: Long, completion: AgentRunCompletionRecord) = Unit

        override fun activeRuns(): List<AgentRunRecord> =
            runs.filter { it.endedAt == null }

        override fun latestForRole(storyRunId: Long, role: AgentRole): AgentRunRecord? =
            recentForRole(storyRunId, role, limit = 1).firstOrNull()

        override fun recentForRole(storyRunId: Long, role: AgentRole, limit: Int): List<AgentRunRecord> =
            runs.filter { it.storyRunId == storyRunId && it.role == role }
                .sortedByDescending { it.id }
                .take(limit)

        override fun countForRole(storyRunId: Long, role: AgentRole): Int =
            runs.count { it.storyRunId == storyRunId && it.role == role }

        fun addEnded(storyRunId: Long, role: AgentRole, outcome: String, summary: String) {
            runs += AgentRunRecord(
                id = nextId++,
                storyRunId = storyRunId,
                role = role,
                containerName = "factory-test-ended-${nextId}",
                startedAt = OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC),
                endedAt = OffsetDateTime.now(),
                outcome = outcome,
                summaryText = summary,
            )
        }
    }

    private class FakeGitHubApi(
        private val mergedPrs: Set<Int> = emptySet(),
        private val commentsByPr: Map<Int, List<PullRequestComment>> = emptyMap(),
        private val claimedCommentsByPr: Map<Int, List<PullRequestComment>> = emptyMap(),
    ) : GitHubApi {
        val claimedComments = mutableListOf<Long>()

        override fun ensurePullRequest(
            repoRoot: java.nio.file.Path,
            branchName: String,
            baseBranch: String,
            title: String,
            body: String,
        ): PullRequestInfo =
            PullRequestInfo(number = 1, url = "https://github.example/pr/1")

        override fun isMerged(targetRepo: String, prNumber: Int): Boolean =
            prNumber in mergedPrs

        override fun unprocessedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> =
            commentsByPr[prNumber].orEmpty()

        override fun claimedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> =
            claimedCommentsByPr[prNumber].orEmpty()

        override fun markCommentClaimed(targetRepo: String, commentId: Long) {
            claimedComments += commentId
        }

        override fun markCommentDone(targetRepo: String, commentId: Long) = Unit

        override fun markCommentFailed(targetRepo: String, commentId: Long) = Unit

        override fun closePullRequest(targetRepo: String, prNumber: Int) = Unit

        override fun deleteBranch(targetRepo: String, branchName: String) = Unit

        override fun mergePullRequest(targetRepo: String, prNumber: Int) = Unit
    }

    private class FakePreviewEnvironmentCleaner : PreviewApi {
        override fun render(template: String?, prNumber: Int?): String? = PreviewApi.renderTemplate(template, prNumber)

        val cleanedNamespaces = mutableListOf<String>()

        override fun cleanup(namespace: String): Boolean {
            cleanedNamespaces += namespace
            return true
        }
    }

    private class FakeStoryWorkspaceService : StoryWorkspaceApi {
        override fun prepare(storyRun: StoryRunRecord, role: AgentRole): PreparedStoryWorkspace {
            val workspace = Path.of("/tmp/software-factory-test-workspaces/${storyRun.storyKey}")
            return PreparedStoryWorkspace(
                workspacePath = workspace,
                repoRoot = workspace.resolve("repo"),
                branchName = storyRun.branchName ?: "ai/${storyRun.storyKey}",
                baseBranch = storyRun.baseBranch ?: "main",
                branchPrefix = storyRun.branchPrefix ?: "ai/",
                deploymentConfig = DeploymentConfig(
                    defaultBaseBranch = storyRun.baseBranch ?: "main",
                    branchPrefix = storyRun.branchPrefix ?: "ai/",
                    previewUrlTemplate = storyRun.previewUrlTemplate,
                    previewNamespaceTemplate = storyRun.previewNamespaceTemplate,
                    previewDbSecretRecipe = storyRun.previewDbSecretRecipe,
                ),
            )
        }

        override fun syncAfterAgent(storyRun: StoryRunRecord, role: AgentRole): RepositorySyncResult =
            error("Not used by these tests")

        override fun cleanup(storyKey: String): Boolean =
            true
    }

    private class FakeCostMonitor : CostMonitor {
        var paused = false

        override fun applyBudgetTriggers(issue: TrackerIssue): TrackerIssue =
            issue

        override fun checkBudget(issue: TrackerIssue, storyRun: StoryRunRecord): CostMonitorCheckResult =
            CostMonitorCheckResult(storyRun.totalTokens, issue.fields.aiTokenBudget ?: 40000, paused, emptyList())

        override fun checkCompletedRun(storyKey: String, storyRun: StoryRunRecord) = Unit
    }

    private class FakeCreditsPauseCoordinator : CreditsPauseCoordinator {
        var pause: CreditsPause? = null
        val exhaustedStories = mutableListOf<String>()

        override fun activePause(now: OffsetDateTime): CreditsPause? =
            pause

        override fun handleCreditsExhausted(storyKey: String, summaryText: String?) {
            exhaustedStories += storyKey
        }
    }

    private class NoopManualCommandProcessor : ManualCommandProcessor {
        override fun apply(issue: TrackerIssue): ManualCommandApplication =
            ManualCommandApplication(issue)
    }
}
