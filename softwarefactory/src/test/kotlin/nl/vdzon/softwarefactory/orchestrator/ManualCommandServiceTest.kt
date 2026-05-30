package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.orchestrator.models.*
import nl.vdzon.softwarefactory.orchestrator.services.*

import nl.vdzon.softwarefactory.orchestrator.*
import nl.vdzon.softwarefactory.orchestrator.*
import nl.vdzon.softwarefactory.orchestrator.*

import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.github.PullRequestInfo
import nl.vdzon.softwarefactory.docs.DeploymentConfig
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ManualCommandServiceTest {
    private val now = OffsetDateTime.parse("2026-05-24T12:00:00Z")
    private val clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC)

    @Test
    fun `resume and level commands update fields once`() {
        val issueTracker = FakeYouTrackApi()
        val store = InMemoryProcessedCommentStore()
        val service = service(issueTracker, store = store)
        val issue = issue(
            paused = true,
            error = "budget exceeded",
            comments = listOf(comment("10", "@factory:command:resume\nLEVEL=7\nSUPPLIER=copilot")),
        )

        val applied = service.apply(issue)
        val again = service.apply(issue)

        assertNull(applied.stopResult)
        assertFalse(applied.issue.fields.paused)
        assertNull(applied.issue.fields.error)
        assertEquals(7, applied.issue.fields.aiLevel)
        assertEquals("copilot", applied.issue.fields.aiSupplier)
        assertEquals(issue, again.issue)
        assertEquals(
            listOf(
                mapOf(TrackerField.PAUSED to false, TrackerField.ERROR to null),
                mapOf(TrackerField.AI_LEVEL to 7),
                mapOf(TrackerField.AI_SUPPLIER to "copilot"),
            ),
            issueTracker.updates.getValue("KAN-1").map { it.values },
        )
        assertTrue(store.isProcessed("KAN-1", "10", AgentRole.ORCHESTRATOR))
    }

    @Test
    fun `resume on developer loopback cap clears error and increases story limit by five`() {
        val issueTracker = FakeYouTrackApi()
        val service = service(issueTracker)
        val issue = issue(
            error = "[ORCHESTRATOR] Developer-loopback cap bereikt (5x). Handmatige triage nodig.",
            comments = listOf(comment("17", "@factory:command:resume")),
        )

        val applied = service.apply(issue)

        assertNull(applied.stopResult)
        assertFalse(applied.issue.fields.paused)
        assertNull(applied.issue.fields.error)
        assertEquals(10, applied.issue.fields.aiMaxDeveloperLoopbacks)
        assertEquals(
            mapOf(
                TrackerField.PAUSED to false,
                TrackerField.ERROR to null,
                TrackerField.AI_MAX_DEVELOPER_LOOPBACKS to 10,
            ),
            issueTracker.lastUpdate("KAN-1").values,
        )
    }

    @Test
    fun `resume on developer loopback cap increments existing story limit`() {
        val issueTracker = FakeYouTrackApi()
        val service = service(issueTracker)
        val issue = issue(
            maxDeveloperLoopbacks = 12,
            error = "[ORCHESTRATOR] Developer-loopback cap bereikt (12x). Handmatige triage nodig.",
            comments = listOf(comment("18", "@factory:command:resume")),
        )

        val applied = service.apply(issue)

        assertEquals(17, applied.issue.fields.aiMaxDeveloperLoopbacks)
        assertEquals(17, issueTracker.lastUpdate("KAN-1").values[TrackerField.AI_MAX_DEVELOPER_LOOPBACKS])
    }

    @Test
    fun `pause and kill stop further orchestration`() {
        val issueTracker = FakeYouTrackApi()
        val runtime = FakeAgentRuntime()
        val service = service(issueTracker, runtime = runtime)
        val issue = issue(comments = listOf(comment("11", "@factory:command:kill")))

        val applied = service.apply(issue)

        assertEquals(IssueProcessResult.Skipped("KAN-1", "killed"), applied.stopResult)
        assertEquals(listOf("KAN-1"), runtime.killedStories)
        assertEquals(true, issueTracker.lastUpdate("KAN-1").values[TrackerField.PAUSED])
    }

    @Test
    fun `delete closes PR branch preview run and transitions to Done`() {
        val issueTracker = FakeYouTrackApi()
        val runtime = FakeAgentRuntime()
        val storyRuns = InMemoryStoryRunRepository().withPullRequest()
        val pullRequests = FakeGitHubApi()
        val previewCleaner = FakePreviewEnvironmentCleaner()
        val service = service(
            issueTracker = issueTracker,
            runtime = runtime,
            storyRuns = storyRuns,
            pullRequests = pullRequests,
            previewCleaner = previewCleaner,
        )
        val issue = issue(comments = listOf(comment("12", "@factory:command:delete")))

        val applied = service.apply(issue)

        assertEquals(IssueProcessResult.Skipped("KAN-1", "deleted"), applied.stopResult)
        assertEquals(listOf("KAN-1"), runtime.killedStories)
        assertEquals(listOf(42), pullRequests.closedPrs)
        assertEquals(listOf("ai/KAN-1"), pullRequests.deletedBranches)
        assertEquals(listOf("app-pr-42"), previewCleaner.cleanedNamespaces)
        assertEquals(listOf(1L to "deleted"), storyRuns.closed)
        assertEquals("(CANCELLED) Story KAN-1", issueTracker.summaryUpdates.single().second)
        assertEquals("Done", issueTracker.transitions.single().second)
    }

    @Test
    fun `merge squashes PR cleans preview closes run and transitions to Done`() {
        val issueTracker = FakeYouTrackApi()
        val storyRuns = InMemoryStoryRunRepository().withPullRequest()
        val pullRequests = FakeGitHubApi()
        val previewCleaner = FakePreviewEnvironmentCleaner()
        val service = service(
            issueTracker = issueTracker,
            storyRuns = storyRuns,
            pullRequests = pullRequests,
            previewCleaner = previewCleaner,
        )
        val issue = issue(comments = listOf(comment("13", "@factory:command:merge")))

        val applied = service.apply(issue)

        assertEquals(IssueProcessResult.Merged("KAN-1", 42), applied.stopResult)
        assertEquals(listOf(42), pullRequests.mergedPrs)
        assertEquals(listOf("app-pr-42"), previewCleaner.cleanedNamespaces)
        assertEquals(listOf(1L to "merged"), storyRuns.closed)
        assertEquals("Done", issueTracker.transitions.single().second)
    }

    @Test
    fun `re implement closes resources clears fields and deletes agent comments`() {
        val issueTracker = FakeYouTrackApi()
        val storyRuns = InMemoryStoryRunRepository().withPullRequest()
        val pullRequests = FakeGitHubApi()
        val previewCleaner = FakePreviewEnvironmentCleaner()
        val service = service(
            issueTracker = issueTracker,
            storyRuns = storyRuns,
            pullRequests = pullRequests,
            previewCleaner = previewCleaner,
        )
        val issue = issue(
            phase = "tested-with-feedback-for-developer",
            error = "bad run",
            maxDeveloperLoopbacks = 12,
            agentStartedAt = OffsetDateTime.parse("2026-05-24T10:00:00Z"),
            comments = listOf(comment("14", "@factory:command:re-implement")),
        )

        val applied = service.apply(issue)

        assertEquals(IssueProcessResult.Skipped("KAN-1", "re-implement"), applied.stopResult)
        assertEquals(listOf(42), pullRequests.closedPrs)
        assertEquals(listOf("ai/KAN-1"), pullRequests.deletedBranches)
        assertEquals(listOf("app-pr-42"), previewCleaner.cleanedNamespaces)
        assertEquals(listOf("KAN-1"), issueTracker.deletedAgentComments)
        assertEquals(listOf(1L to "re-implement"), storyRuns.closed)
        val lastUpdate = issueTracker.lastUpdate("KAN-1").values
        assertFalse(lastUpdate.containsKey(TrackerField.AI_SUPPLIER))
        assertTrue(lastUpdate.containsKey(TrackerField.AI_PHASE))
        assertNull(lastUpdate[TrackerField.AI_PHASE])
        assertFalse(lastUpdate.containsKey(TrackerField.AI_LEVEL))
        assertTrue(lastUpdate.containsKey(TrackerField.AI_MAX_DEVELOPER_LOOPBACKS))
        assertNull(lastUpdate[TrackerField.AI_MAX_DEVELOPER_LOOPBACKS])
        assertFalse(lastUpdate.containsKey(TrackerField.AI_TOKEN_BUDGET))
        assertTrue(lastUpdate.containsKey(TrackerField.AI_TOKENS_USED))
        assertNull(lastUpdate[TrackerField.AI_TOKENS_USED])
        assertTrue(lastUpdate.containsKey(TrackerField.AGENT_STARTED_AT))
        assertNull(lastUpdate[TrackerField.AGENT_STARTED_AT])
        assertEquals(false, lastUpdate[TrackerField.PAUSED])
        assertNull(lastUpdate[TrackerField.ERROR])
    }

    @Test
    fun `clear error only clears the error field`() {
        val issueTracker = FakeYouTrackApi()
        val service = service(issueTracker)
        val issue = issue(
            phase = "reviewing",
            error = "manual check needed",
            comments = listOf(comment("15", "@factory:command:clear-error")),
        )

        val applied = service.apply(issue)

        assertNull(applied.stopResult)
        assertNull(applied.issue.fields.error)
        assertEquals("reviewing", applied.issue.fields.aiPhase)
        assertEquals(mapOf(TrackerField.ERROR to null), issueTracker.lastUpdate("KAN-1").values)
    }

    @Test
    fun `retry current step kills active agent and returns active phase to previous stable phase`() {
        val issueTracker = FakeYouTrackApi()
        val runtime = FakeAgentRuntime()
        val service = service(issueTracker, runtime = runtime)
        val startedAt = OffsetDateTime.parse("2026-05-24T10:00:00Z")
        val issue = issue(
            phase = "testing",
            paused = true,
            error = "[ORCHESTRATOR] Hard timeout",
            agentStartedAt = startedAt,
            comments = listOf(comment("16", "@factory:command:retry-current-step")),
        )

        val applied = service.apply(issue)

        assertEquals(IssueProcessResult.Skipped("KAN-1", "retry-current-step"), applied.stopResult)
        assertEquals(listOf("KAN-1"), runtime.killedStories)
        val lastUpdate = issueTracker.lastUpdate("KAN-1").values
        assertEquals("review-finished", lastUpdate[TrackerField.AI_PHASE])
        assertNull(lastUpdate[TrackerField.AGENT_STARTED_AT])
        assertEquals(false, lastUpdate[TrackerField.PAUSED])
        assertNull(lastUpdate[TrackerField.ERROR])
        assertEquals("review-finished", applied.issue.fields.aiPhase)
        assertNull(applied.issue.fields.agentStartedAt)
        assertFalse(applied.issue.fields.paused)
        assertNull(applied.issue.fields.error)
    }

    @Test
    fun `sync command commits pushes updates PR metadata and resumes story`() {
        val issueTracker = FakeYouTrackApi()
        val storyRuns = InMemoryStoryRunRepository().withPullRequest()
        val workspaceService = FakeStoryWorkspaceService()
        val service = service(
            issueTracker = issueTracker,
            storyRuns = storyRuns,
            storyWorkspaceService = workspaceService,
        )
        val issue = issue(paused = true, comments = listOf(comment("19", "@factory:command:sync")))

        val applied = service.apply(issue)

        assertNull(applied.stopResult)
        assertEquals(listOf(AgentRole.DEVELOPER), workspaceService.syncedRoles)
        assertEquals("ai/KAN-1", storyRuns.pullRequestUpdates.single().branchName)
        assertEquals(43, storyRuns.pullRequestUpdates.single().prNumber)
        assertEquals(false, issueTracker.lastUpdate("KAN-1").values[TrackerField.PAUSED])
        assertNull(issueTracker.lastUpdate("KAN-1").values[TrackerField.ERROR])
        assertFalse(applied.issue.fields.paused)
    }

    private fun service(
        issueTracker: FakeYouTrackApi,
        store: InMemoryProcessedCommentStore = InMemoryProcessedCommentStore(),
        runtime: FakeAgentRuntime = FakeAgentRuntime(),
        storyRuns: InMemoryStoryRunRepository = InMemoryStoryRunRepository(),
        pullRequests: FakeGitHubApi = FakeGitHubApi(),
        previewCleaner: FakePreviewEnvironmentCleaner = FakePreviewEnvironmentCleaner(),
        storyWorkspaceService: StoryWorkspaceApi? = null,
    ): ManualCommandService =
        ManualCommandService(
            issueTrackerClient = issueTracker,
            processedCommentService = ProcessedCommentService(issueTracker, store),
            agentRuntime = runtime,
            storyRunRepository = storyRuns,
            pullRequestClient = pullRequests,
            previewApi = previewCleaner,
            storyWorkspaceService = storyWorkspaceService,
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
        phase: String? = null,
        paused: Boolean = false,
        error: String? = null,
        maxDeveloperLoopbacks: Int? = null,
        agentStartedAt: OffsetDateTime? = null,
        comments: List<TrackerComment> = emptyList(),
    ): TrackerIssue =
        TrackerIssue(
            key = "KAN-1",
            summary = "Story KAN-1",
            status = "Develop",
            fields = TrackerIssueFields(
                targetRepo = "git@github.com:robbertvdzon/sample-build-project.git",
                aiSupplier = "claude",
                aiPhase = phase,
                aiLevel = 5,
                aiMaxDeveloperLoopbacks = maxDeveloperLoopbacks,
                aiTokenBudget = 40000,
                aiTokensUsed = 0,
                agentStartedAt = agentStartedAt,
                paused = paused,
                error = error,
            ),
            comments = comments,
        )

    private fun comment(id: String, body: String): TrackerComment =
        TrackerComment(id, "user", "User", body, null)

    private class FakeYouTrackApi : YouTrackApi {
        val updates = mutableMapOf<String, MutableList<TrackerFieldUpdate>>()
        val transitions = mutableListOf<Pair<String, String>>()
        val summaryUpdates = mutableListOf<Pair<String, String>>()
        val deletedAgentComments = mutableListOf<String>()

        override fun findAiIssues(projectKey: String, maxResults: Int): List<TrackerIssue> = emptyList()

        override fun getIssue(issueKey: String): TrackerIssue =
            throw UnsupportedOperationException()

        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
            updates.getOrPut(issueKey) { mutableListOf() } += update
        }

        override fun updateIssueSummary(issueKey: String, summary: String) {
            summaryUpdates += issueKey to summary
        }

        override fun transitionIssue(issueKey: String, statusName: String) {
            transitions += issueKey to statusName
        }

        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment =
            throw UnsupportedOperationException()

        override fun hasProcessedCommentMarker(commentId: String, role: AgentRole): Boolean =
            false

        override fun markCommentProcessed(commentId: String, role: AgentRole): Boolean =
            false

        override fun deleteAgentComments(issueKey: String): Int {
            deletedAgentComments += issueKey
            return 1
        }

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

    private class FakeAgentRuntime : AgentRuntime {
        val killedStories = mutableListOf<String>()

        override fun dispatch(request: AgentDispatchRequest): AgentDispatchResult =
            throw UnsupportedOperationException()

        override fun isAgentRunning(storyKey: String, role: AgentRole): Boolean = false

        override fun isContainerRunning(containerName: String): Boolean = false

        override fun isAnyAgentRunningForStory(storyKey: String): Boolean = false

        override fun runningCount(role: AgentRole?): Int = 0

        override fun killForStory(storyKey: String): Int {
            killedStories += storyKey
            return 1
        }
    }

    private class InMemoryStoryRunRepository : StoryRunRepository {
        private val runs = mutableMapOf<String, StoryRunRecord>()
        val closed = mutableListOf<Pair<Long, String>>()
        val pullRequestUpdates = mutableListOf<PullRequestUpdate>()

        fun withPullRequest(): InMemoryStoryRunRepository {
            runs["KAN-1"] = StoryRunRecord(
                id = 1,
                storyKey = "KAN-1",
                targetRepo = "git@github.com:robbertvdzon/sample-build-project.git",
                branchName = "ai/KAN-1",
                prNumber = 42,
                prUrl = "https://github.com/robbertvdzon/sample-build-project/pull/42",
                previewNamespaceTemplate = "app-pr-{pr_num}",
            )
            return this
        }

        override fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord =
            runs.getOrPut(storyKey) { StoryRunRecord(1, storyKey, targetRepo) }

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
            pullRequestUpdates += PullRequestUpdate(storyRunId, branchName, prNumber, prUrl)
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

    private class FakeGitHubApi : GitHubApi {
        val closedPrs = mutableListOf<Int>()
        val deletedBranches = mutableListOf<String>()
        val mergedPrs = mutableListOf<Int>()

        override fun ensurePullRequest(repoRoot: Path, branchName: String, baseBranch: String, title: String, body: String): PullRequestInfo =
            PullRequestInfo(number = 1, url = "https://github.example/pr/1")

        override fun isMerged(targetRepo: String, prNumber: Int): Boolean = false

        override fun unprocessedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> = emptyList()

        override fun claimedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> = emptyList()

        override fun markCommentClaimed(targetRepo: String, commentId: Long) = Unit

        override fun markCommentDone(targetRepo: String, commentId: Long) = Unit

        override fun markCommentFailed(targetRepo: String, commentId: Long) = Unit

        override fun closePullRequest(targetRepo: String, prNumber: Int) {
            closedPrs += prNumber
        }

        override fun deleteBranch(targetRepo: String, branchName: String) {
            deletedBranches += branchName
        }

        override fun mergePullRequest(targetRepo: String, prNumber: Int) {
            mergedPrs += prNumber
        }
    }

    private data class PullRequestUpdate(
        val storyRunId: Long,
        val branchName: String,
        val prNumber: Int?,
        val prUrl: String?,
    )

    private class FakeStoryWorkspaceService : StoryWorkspaceApi {
        val syncedRoles = mutableListOf<AgentRole>()

        override fun prepare(storyRun: StoryRunRecord, role: AgentRole): PreparedStoryWorkspace =
            throw UnsupportedOperationException()

        override fun syncAfterAgent(storyRun: StoryRunRecord, role: AgentRole): RepositorySyncResult {
            syncedRoles += role
            return RepositorySyncResult(
                workspacePath = Path.of("/tmp/story-workspace"),
                repoRoot = Path.of("/tmp/story-workspace/repo"),
                branchName = "ai/${storyRun.storyKey}",
                baseBranch = "main",
                branchPrefix = "ai/",
                deploymentConfig = DeploymentConfig(previewNamespaceTemplate = "app-pr-{pr_num}"),
                committed = true,
                pushed = true,
                prNumber = 43,
                prUrl = "https://github.example/pr/43",
            )
        }

        override fun cleanup(storyKey: String): Boolean = true
    }

    private class FakePreviewEnvironmentCleaner : PreviewApi {
        override fun render(template: String?, prNumber: Int?): String? = PreviewApi.renderTemplate(template, prNumber)

        val cleanedNamespaces = mutableListOf<String>()

        override fun cleanup(namespace: String): Boolean {
            cleanedNamespaces += namespace
            return true
        }
    }
}
