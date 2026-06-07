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
    fun `PR factory comment is claimed and creates a development subtask`() {
        val issueTracker = FakeYouTrackApi(listOf(issue("KAN-12", storyPhase = "planning-approved")))
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
        // v2: PR-feedback wordt een nieuwe development-subtask, getagd voor de keten.
        assertEquals(
            nl.vdzon.softwarefactory.youtrack.SubtaskType.DEVELOPMENT,
            issueTracker.createdSubtasks.single().type,
        )
        assertEquals(listOf("KAN-12-sub1" to "ai-development"), issueTracker.addedTags)
        assertEquals(9001, pullRequests.claimedComments.single())
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
        val s1 = issue("PF-7", type = "Task", subtaskType = "manual", subtaskPhase = "manual-action-done")
        val s2 = issue("PF-8", type = "Task", subtaskType = "development", subtaskPhase = null)
        val s3 = issue("PF-9", type = "Task", subtaskType = "development", subtaskPhase = "review-approved")
        val issueTracker = FakeYouTrackApi(listOf(s1, s2, s3), parentKey = "PF-1", subtasks = listOf(s1, s2, s3))

        val result = service(issueTracker).processIssue(s1)

        assertEquals(IssueProcessResult.Chained("PF-7", "PF-8"), result)
        assertEquals(listOf("PF-8" to "ai-development"), issueTracker.addedTags)
        assertEquals(listOf("PF-7" to "ai-development"), issueTracker.removedTags)
    }

    @Test
    fun `last terminal subtask untags itself and chains to nothing`() {
        val only = issue("PF-9", type = "Task", subtaskType = "summary", subtaskPhase = "summary-approved")
        val issueTracker = FakeYouTrackApi(listOf(only), parentKey = "PF-1", subtasks = listOf(only))

        val result = service(issueTracker).processIssue(only)

        assertEquals(IssueProcessResult.Chained("PF-9", null), result)
        assertEquals(emptyList<Pair<String, String>>(), issueTracker.addedTags)
        assertEquals(listOf("PF-9" to "ai-development"), issueTracker.removedTags)
    }

    @Test
    fun `developed subtask waits for human approval`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development", subtaskPhase = "developed")
        val issueTracker = FakeYouTrackApi(listOf(sub), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Skipped("PF-7", "waiting-for-approval"), result)
        assertEquals(emptyList<Pair<String, String>>(), issueTracker.addedTags)
    }

    @Test
    fun `development subtask starts developer agent`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development")
        val issueTracker = FakeYouTrackApi(listOf(sub), parentKey = "PF-1")
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

        val result = service(FakeYouTrackApi(listOf(sub), parentKey = "PF-1"), runtime = runtime).processIssue(sub)

        assertEquals(AgentRole.REVIEWER, (result as IssueProcessResult.Dispatched).role)
        assertEquals("reviewing", runtime.dispatches.single().phase)
    }

    @Test
    fun `review-rejected starts a fix developer`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development", subtaskPhase = "review-rejected")
        val runtime = FakeAgentRuntime(now)

        val result = service(FakeYouTrackApi(listOf(sub), parentKey = "PF-1"), runtime = runtime).processIssue(sub)

        assertEquals(AgentRole.DEVELOPER, (result as IssueProcessResult.Dispatched).role)
        assertEquals("developing", runtime.dispatches.single().phase)
    }

    @Test
    fun `review subtask re-reviews after a fix without separate dev approval`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "review", subtaskPhase = "developed")
        val runtime = FakeAgentRuntime(now)

        val result = service(FakeYouTrackApi(listOf(sub), parentKey = "PF-1"), runtime = runtime).processIssue(sub)

        assertEquals(AgentRole.REVIEWER, (result as IssueProcessResult.Dispatched).role)
    }

    @Test
    fun `manual subtask without phase moves to awaiting-human`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "manual")
        val issueTracker = FakeYouTrackApi(listOf(sub), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Recovered("PF-7", "awaiting-human"), result)
    }

    @Test
    fun `subtask dispatch is serialized on the parent branch`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "development")
        val issueTracker = FakeYouTrackApi(listOf(sub), parentKey = "PF-1")
        val runtime = FakeAgentRuntime(now, runningStories = setOf("PF-1"))

        val result = service(issueTracker, runtime = runtime).processIssue(sub)

        assertEquals(IssueProcessResult.Skipped("PF-7", "concurrency-cap"), result)
        assertEquals(emptyList<AgentDispatchRequest>(), runtime.dispatches)
    }

    @Test
    fun `paused parent story halts subtask dispatch`() {
        val parent = issue("PF-1", paused = true)
        val sub = issue("PF-7", type = "Task", subtaskType = "development")
        val issueTracker = FakeYouTrackApi(listOf(sub, parent), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Skipped("PF-7", "parent-paused"), result)
    }

    @Test
    fun `summarized subtask waits for approval`() {
        val sub = issue("PF-7", type = "Task", subtaskType = "summary", subtaskPhase = "summarized")
        val issueTracker = FakeYouTrackApi(listOf(sub), parentKey = "PF-1")

        val result = service(issueTracker).processIssue(sub)

        assertEquals(IssueProcessResult.Skipped("PF-7", "waiting-for-approval"), result)
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
        subtaskType: String? = null,
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
                subtaskType = subtaskType,
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

        val createdSubtasks: MutableList<nl.vdzon.softwarefactory.youtrack.SubtaskSpec> = mutableListOf()

        override fun createSubtask(
            parentKey: String,
            spec: nl.vdzon.softwarefactory.youtrack.SubtaskSpec,
            supplier: String?,
        ): TrackerIssue {
            createdSubtasks += spec
            return TrackerIssue(
                key = "$parentKey-sub${createdSubtasks.size}",
                summary = spec.title,
                description = spec.description,
                status = "Develop",
                fields = TrackerIssueFields(
                    targetRepo = null,
                    aiSupplier = supplier,
                    aiPhase = null,
                    aiLevel = null,
                    aiTokenBudget = null,
                    aiTokensUsed = null,
                    agentStartedAt = null,
                    paused = false,
                    error = null,
                    type = "Task",
                    subtaskType = spec.type.trackerValue,
                ),
                comments = emptyList(),
            )
        }

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
        private val runningStories: Set<String> = emptySet(),
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
            storyKey in runningStories

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
            subtaskKey: String?,
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
