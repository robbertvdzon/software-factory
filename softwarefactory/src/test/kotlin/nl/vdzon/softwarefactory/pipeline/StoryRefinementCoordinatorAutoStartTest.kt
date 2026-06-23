package nl.vdzon.softwarefactory.pipeline

import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.core.AgentDispatchRequest
import nl.vdzon.softwarefactory.core.AgentDispatchResult
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.AgentRunRecord
import nl.vdzon.softwarefactory.core.AgentRunRepository
import nl.vdzon.softwarefactory.core.AgentRuntime
import nl.vdzon.softwarefactory.core.CostMonitor
import nl.vdzon.softwarefactory.core.CostMonitorCheckResult
import nl.vdzon.softwarefactory.core.DeploymentConfig
import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.core.OrchestratorSettings
import nl.vdzon.softwarefactory.core.PreparedStoryWorkspace
import nl.vdzon.softwarefactory.core.RepositorySyncResult
import nl.vdzon.softwarefactory.core.StoryPhase
import nl.vdzon.softwarefactory.core.StoryRunRecord
import nl.vdzon.softwarefactory.core.StoryRunRepository
import nl.vdzon.softwarefactory.core.StoryWorkspaceApi
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerIssueFields
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.github.PullRequestInfo
import nl.vdzon.softwarefactory.pipeline.service.AgentDispatcher
import nl.vdzon.softwarefactory.pipeline.service.StoryRefinementCoordinator
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.youtrack.ProcessedCommentsApi
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.youtrack.repositories.ProcessedCommentStore
import nl.vdzon.softwarefactory.youtrack.services.ProcessedCommentService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class StoryRefinementCoordinatorAutoStartTest {

    private val now = OffsetDateTime.parse("2026-01-01T10:00:00Z")
    private val fixedClock = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"), ZoneOffset.UTC)
    private val settings = OrchestratorSettings(
        pollInterval = Duration.ofSeconds(1),
        maxParallelRefiner = 1,
        maxParallelDeveloper = 1,
        maxParallelReviewer = 1,
        maxParallelTester = 1,
        maxParallelTotal = 4,
        maxDeveloperLoopbacks = 3,
        maxTransientRetries = 2,
        hardTimeout = Duration.ofMinutes(60),
        costMonitorInterval = Duration.ofMinutes(5),
        creditsPauseDefault = Duration.ofMinutes(30),
    )

    @Test
    fun `processStoryRefinement auto-starts first subtask and sets story to in-progress when autoApprove is true`() {
        val subtask = subtaskIssue("SF-2", subtaskPhase = null)
        val tracker = FakeTracker(subtasks = listOf(subtask))
        val coordinator = createCoordinator(tracker)
        val story = storyIssue("SF-1", StoryPhase.PLANNING_APPROVED, autoApprove = true)

        val result = coordinator.processStoryRefinement(story)

        assertTrue(result is IssueProcessResult.Recovered, "Verwacht Recovered maar was $result")
        assertEquals(
            StoryPhase.IN_PROGRESS.trackerValue,
            tracker.lastFieldUpdateFor("SF-1", TrackerField.STORY_PHASE),
        )
        assertEquals(
            SubtaskPhase.START.trackerValue,
            tracker.lastFieldUpdateFor("SF-2", TrackerField.SUBTASK_PHASE),
        )
    }

    @Test
    fun `processStoryRefinement skips when development already started (idempotent)`() {
        val subtask = subtaskIssue("SF-2", subtaskPhase = SubtaskPhase.DEVELOPING.trackerValue)
        val tracker = FakeTracker(subtasks = listOf(subtask))
        val coordinator = createCoordinator(tracker)
        val story = storyIssue("SF-1", StoryPhase.PLANNING_APPROVED, autoApprove = true)

        val result = coordinator.processStoryRefinement(story)

        assertTrue(result is IssueProcessResult.Skipped, "Verwacht Skipped maar was $result")
        assertEquals("development-already-started", (result as IssueProcessResult.Skipped).reason)
        assertTrue(tracker.allUpdates.isEmpty(), "Geen updates verwacht bij idempotente skip")
    }

    @Test
    fun `processStoryRefinement skips without auto-starting when autoApprove is false`() {
        val subtask = subtaskIssue("SF-2", subtaskPhase = null)
        val tracker = FakeTracker(subtasks = listOf(subtask))
        val coordinator = createCoordinator(tracker)
        val story = storyIssue("SF-1", StoryPhase.PLANNING_APPROVED, autoApprove = false)

        val result = coordinator.processStoryRefinement(story)

        assertTrue(result is IssueProcessResult.Skipped, "Verwacht Skipped maar was $result")
        assertEquals("refinement-done", (result as IssueProcessResult.Skipped).reason)
        assertTrue(tracker.allUpdates.isEmpty(), "Geen updates verwacht wanneer autoApprove=false")
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private fun createCoordinator(tracker: YouTrackApi): StoryRefinementCoordinator {
        val storyRunRepo = InMemoryStoryRunRepository()
        val agentRunRepo = InMemoryAgentRunRepository()
        val agentRuntime = StubAgentRuntime()
        val processedCommentStore = StubProcessedCommentStore()
        val dispatcher = AgentDispatcher(
            issueTrackerClient = tracker,
            agentRuntime = agentRuntime,
            storyRunRepository = storyRunRepo,
            agentRunRepository = agentRunRepo,
            pullRequestClient = StubGitHubApi(),
            processedCommentService = ProcessedCommentService(tracker, processedCommentStore),
            previewApi = StubPreviewApi(),
            storyWorkspaceService = StubStoryWorkspaceApi(),
            costMonitor = StubCostMonitor(),
            projectRepoResolver = ProjectRepoResolver(emptyMap()),
            settings = settings,
            clock = fixedClock,
        )
        return StoryRefinementCoordinator(tracker, agentRuntime, storyRunRepo, agentRunRepo, settings, fixedClock, dispatcher)
    }

    private fun storyIssue(key: String, phase: StoryPhase, autoApprove: Boolean): TrackerIssue =
        TrackerIssue(
            key = key,
            summary = "Story $key",
            description = null,
            status = "",
            comments = emptyList(),
            fields = TrackerIssueFields(
                targetRepo = null,
                aiPhase = null,
                aiLevel = null,
                aiTokenBudget = null,
                aiTokensUsed = null,
                agentStartedAt = null,
                paused = false,
                error = null,
                storyPhase = phase.trackerValue,
                autoApprove = autoApprove,
            ),
        )

    private fun subtaskIssue(key: String, subtaskPhase: String?): TrackerIssue =
        TrackerIssue(
            key = key,
            summary = "Subtask $key",
            description = null,
            status = "",
            comments = emptyList(),
            fields = TrackerIssueFields(
                targetRepo = null,
                aiPhase = null,
                aiLevel = null,
                aiTokenBudget = null,
                aiTokensUsed = null,
                agentStartedAt = null,
                paused = false,
                error = null,
                type = "Task",
                subtaskPhase = subtaskPhase,
                subtaskType = "development",
            ),
        )

    // ── stubs ─────────────────────────────────────────────────────────────────────

    private class FakeTracker(
        private val subtasks: List<TrackerIssue> = emptyList(),
    ) : YouTrackApi {
        val allUpdates: MutableList<Triple<String, TrackerField, String?>> = mutableListOf()

        override fun subtasksOf(parentKey: String): List<TrackerIssue> = subtasks
        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
            update.values.forEach { (field, value) -> allUpdates += Triple(issueKey, field, value?.toString()) }
        }
        override fun getIssue(issueKey: String): TrackerIssue =
            subtasks.firstOrNull { it.key == issueKey } ?: throw NoSuchElementException(issueKey)
        override fun transitionIssue(issueKey: String, statusName: String) = Unit
        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment =
            throw UnsupportedOperationException()

        fun lastFieldUpdateFor(issueKey: String, field: TrackerField): String? =
            allUpdates.lastOrNull { it.first == issueKey && it.second == field }?.third
    }

    private class StubAgentRuntime : AgentRuntime {
        override fun dispatch(request: AgentDispatchRequest): AgentDispatchResult =
            AgentDispatchResult(containerName = "stub", startedAt = OffsetDateTime.now())
        override fun isContainerRunning(containerName: String): Boolean = false
        override fun isAgentRunning(storyKey: String, role: AgentRole): Boolean = false
        override fun isAnyAgentRunningForStory(storyKey: String): Boolean = false
        override fun runningCount(role: AgentRole?): Int = 0
        override fun killForStory(storyKey: String): Int = 0
    }

    private class InMemoryStoryRunRepository : StoryRunRepository {
        private val runs = mutableMapOf<String, StoryRunRecord>()
        private var nextId = 1L

        override fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord =
            runs.getOrPut(storyKey) { StoryRunRecord(nextId++, storyKey, targetRepo) }
        override fun get(storyRunId: Long): StoryRunRecord? = runs.values.firstOrNull { it.id == storyRunId }
        override fun updatePullRequest(storyRunId: Long, branchName: String, prNumber: Int?, prUrl: String?, baseBranch: String?, branchPrefix: String?, previewUrlTemplate: String?, previewNamespaceTemplate: String?, previewDbSecretRecipe: String?) = Unit
        override fun activePullRequests(): List<StoryRunRecord> = emptyList()
        override fun activeRuns(): List<StoryRunRecord> = runs.values.toList()
        override fun close(storyRunId: Long, finalStatus: String, endedAt: OffsetDateTime) {
            runs.entries.removeIf { it.value.id == storyRunId }
        }
    }

    private class InMemoryAgentRunRepository : AgentRunRepository {
        private val runs = mutableListOf<AgentRunRecord>()
        private var nextId = 1L

        override fun recordStarted(storyRunId: Long, role: AgentRole, containerName: String, model: String?, effort: String?, level: Int?, workspacePath: String?, subtaskKey: String?): Long {
            val id = nextId++
            runs += AgentRunRecord(id = id, storyRunId = storyRunId, role = role, containerName = containerName, model = model, effort = effort, level = level, workspacePath = workspacePath, startedAt = OffsetDateTime.now(), endedAt = null, outcome = null, summaryText = null)
            return id
        }
        override fun latestForRole(storyRunId: Long, role: AgentRole): AgentRunRecord? =
            runs.filter { it.storyRunId == storyRunId && it.role == role }.maxByOrNull { it.startedAt }
        override fun recentForRole(storyRunId: Long, role: AgentRole, limit: Int): List<AgentRunRecord> =
            runs.filter { it.storyRunId == storyRunId && it.role == role }.sortedByDescending { it.startedAt }.take(limit)
        override fun complete(containerName: String, completion: nl.vdzon.softwarefactory.core.AgentRunCompletionRecord, endedAt: OffsetDateTime): nl.vdzon.softwarefactory.core.CompletedAgentRun? = null
        override fun addUsageToStoryRun(storyRunId: Long, completion: nl.vdzon.softwarefactory.core.AgentRunCompletionRecord) = Unit
        override fun activeRuns(): List<AgentRunRecord> = runs.filter { it.endedAt == null }
        override fun countForRole(storyRunId: Long, role: AgentRole): Int = runs.count { it.storyRunId == storyRunId && it.role == role }
        override fun countForRoleAndSubtask(storyRunId: Long, role: AgentRole, subtaskKey: String): Int = 0
    }

    private class StubProcessedCommentStore : ProcessedCommentStore {
        override fun isProcessed(storyKey: String, commentId: String, role: AgentRole): Boolean = false
        override fun markProcessed(storyKey: String, commentId: String, role: AgentRole) = Unit
    }

    private class StubGitHubApi : GitHubApi {
        override fun ensurePullRequest(repoRoot: java.nio.file.Path, branchName: String, baseBranch: String, title: String, body: String): PullRequestInfo = PullRequestInfo(1, "url")
        override fun isMerged(targetRepo: String, prNumber: Int): Boolean = false
        override fun unprocessedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> = emptyList()
        override fun claimedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> = emptyList()
        override fun markCommentClaimed(targetRepo: String, commentId: Long) = Unit
        override fun markCommentDone(targetRepo: String, commentId: Long) = Unit
        override fun markCommentFailed(targetRepo: String, commentId: Long) = Unit
        override fun closePullRequest(targetRepo: String, prNumber: Int) = Unit
        override fun deleteBranch(targetRepo: String, branchName: String) = Unit
        override fun mergePullRequest(targetRepo: String, prNumber: Int) = Unit
    }

    private class StubPreviewApi : PreviewApi {
        override fun render(template: String?, prNumber: Int?): String? = null
        override fun cleanup(namespace: String): Boolean = false
    }

    private class StubStoryWorkspaceApi : StoryWorkspaceApi {
        override fun prepare(storyRun: StoryRunRecord, role: AgentRole): PreparedStoryWorkspace =
            PreparedStoryWorkspace(
                workspacePath = Path.of("/tmp/test"),
                repoRoot = Path.of("/tmp/test/repo"),
                branchName = "ai/test",
                baseBranch = "main",
                branchPrefix = "ai/",
                deploymentConfig = DeploymentConfig(defaultBaseBranch = "main", branchPrefix = "ai/"),
            )
        override fun syncAfterAgent(storyRun: StoryRunRecord, role: AgentRole): RepositorySyncResult =
            error("Not used in these tests")
        override fun cleanup(storyKey: String): Boolean = true
    }

    private class StubCostMonitor : CostMonitor {
        override fun applyBudgetTriggers(issue: TrackerIssue): TrackerIssue = issue
        override fun checkBudget(issue: TrackerIssue, storyRun: StoryRunRecord): CostMonitorCheckResult =
            CostMonitorCheckResult(0, 100000, false, emptyList())
        override fun checkCompletedRun(storyKey: String, storyRun: StoryRunRecord) = Unit
    }
}
