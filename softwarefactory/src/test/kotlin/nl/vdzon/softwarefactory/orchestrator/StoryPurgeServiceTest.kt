package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.github.PullRequestInfo
import nl.vdzon.softwarefactory.orchestrator.services.StoryPurgeService
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.youtrack.AgentRole
import nl.vdzon.softwarefactory.youtrack.TrackerComment
import nl.vdzon.softwarefactory.youtrack.TrackerFieldUpdate
import nl.vdzon.softwarefactory.youtrack.TrackerIssue
import nl.vdzon.softwarefactory.youtrack.TrackerIssueFields
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.OffsetDateTime

class StoryPurgeServiceTest {

    @Test
    fun `purge with active run removes pr branch preview workspace run and youtrack issues`() {
        val issueTracker = FakeYouTrackApi().apply {
            subtasks = listOf(subtask("KAN-2"), subtask("KAN-3"))
        }
        val runtime = FakeAgentRuntime()
        val storyRuns = InMemoryStoryRunRepository().withPullRequest()
        val pullRequests = FakeGitHubApi()
        val preview = FakePreviewEnvironmentCleaner()
        val workspace = FakeStoryWorkspaceService()
        val service = StoryPurgeService(
            issueTrackerClient = issueTracker,
            agentRuntime = runtime,
            storyRunRepository = storyRuns,
            pullRequestClient = pullRequests,
            previewApi = preview,
            storyWorkspaceService = workspace,
        )

        service.purgeStory("KAN-1")

        assertEquals(listOf("KAN-1"), runtime.killedStories)
        assertEquals(listOf(42), pullRequests.closedPrs)
        assertEquals(listOf("ai/KAN-1"), pullRequests.deletedBranches)
        assertEquals(listOf("app-pr-42"), preview.cleanedNamespaces)
        assertEquals(listOf("KAN-1"), workspace.cleanedStoryKeys)
        assertEquals(listOf(1L), storyRuns.deleted)
        // Subtaken eerst, daarna de story zelf.
        assertEquals(listOf("KAN-2", "KAN-3", "KAN-1"), issueTracker.deletedIssues)
        assertTrue(storyRuns.activeRuns().isEmpty())
    }

    @Test
    fun `purge without run only cleans workspace and deletes youtrack issues`() {
        val issueTracker = FakeYouTrackApi().apply {
            subtasks = listOf(subtask("KAN-2"))
        }
        val runtime = FakeAgentRuntime()
        val storyRuns = InMemoryStoryRunRepository()
        val pullRequests = FakeGitHubApi()
        val preview = FakePreviewEnvironmentCleaner()
        val workspace = FakeStoryWorkspaceService()
        val service = StoryPurgeService(
            issueTrackerClient = issueTracker,
            agentRuntime = runtime,
            storyRunRepository = storyRuns,
            pullRequestClient = pullRequests,
            previewApi = preview,
            storyWorkspaceService = workspace,
        )

        service.purgeStory("KAN-1")

        assertEquals(listOf("KAN-1"), runtime.killedStories)
        assertEquals(emptyList<Int>(), pullRequests.closedPrs)
        assertEquals(emptyList<String>(), pullRequests.deletedBranches)
        assertEquals(emptyList<String>(), preview.cleanedNamespaces)
        assertEquals(listOf("KAN-1"), workspace.cleanedStoryKeys)
        assertEquals(emptyList<Long>(), storyRuns.deleted)
        assertEquals(listOf("KAN-2", "KAN-1"), issueTracker.deletedIssues)
    }

    @Test
    fun `purge continues when a step fails`() {
        val issueTracker = FakeYouTrackApi().apply { subtasks = listOf(subtask("KAN-2")) }
        val runtime = FakeAgentRuntime()
        val storyRuns = InMemoryStoryRunRepository().withPullRequest()
        // Branch-verwijdering faalt; de rest moet alsnog doorgaan.
        val pullRequests = FakeGitHubApi(failDeleteBranch = true)
        val preview = FakePreviewEnvironmentCleaner()
        val workspace = FakeStoryWorkspaceService()
        val service = StoryPurgeService(
            issueTrackerClient = issueTracker,
            agentRuntime = runtime,
            storyRunRepository = storyRuns,
            pullRequestClient = pullRequests,
            previewApi = preview,
            storyWorkspaceService = workspace,
        )

        service.purgeStory("KAN-1")

        assertEquals(listOf("app-pr-42"), preview.cleanedNamespaces)
        assertEquals(listOf("KAN-1"), workspace.cleanedStoryKeys)
        assertEquals(listOf(1L), storyRuns.deleted)
        assertEquals(listOf("KAN-2", "KAN-1"), issueTracker.deletedIssues)
    }

    private fun subtask(key: String): TrackerIssue =
        TrackerIssue(
            key = key,
            summary = "Subtask $key",
            status = "Develop",
            fields = TrackerIssueFields(
                targetRepo = "git@github.com:robbertvdzon/sample-build-project.git",
                aiPhase = null,
                aiLevel = null,
                aiTokenBudget = null,
                aiTokensUsed = null,
                agentStartedAt = null,
                paused = false,
                error = null,
                type = "Task",
            ),
            comments = emptyList(),
        )

    private class FakeYouTrackApi : YouTrackApi {
        var subtasks: List<TrackerIssue> = emptyList()
        val deletedIssues = mutableListOf<String>()

        override fun subtasksOf(parentKey: String): List<TrackerIssue> = subtasks

        override fun deleteIssue(issueKey: String) {
            deletedIssues += issueKey
        }

        override fun getIssue(issueKey: String): TrackerIssue = throw UnsupportedOperationException()

        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) = Unit

        override fun transitionIssue(issueKey: String, statusName: String) = Unit

        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment =
            throw UnsupportedOperationException()
    }

    private class FakeAgentRuntime : AgentRuntime {
        val killedStories = mutableListOf<String>()

        override fun dispatch(request: AgentDispatchRequest): AgentDispatchResult = throw UnsupportedOperationException()

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
        val deleted = mutableListOf<Long>()

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

        override fun get(storyRunId: Long): StoryRunRecord? = runs.values.firstOrNull { it.id == storyRunId }

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
        ) = Unit

        override fun activePullRequests(): List<StoryRunRecord> = runs.values.filter { it.prNumber != null }

        override fun activeRuns(): List<StoryRunRecord> = runs.values.toList()

        override fun close(storyRunId: Long, finalStatus: String, endedAt: OffsetDateTime) = Unit

        override fun delete(storyRunId: Long) {
            deleted += storyRunId
            val entry = runs.entries.first { it.value.id == storyRunId }
            runs.remove(entry.key)
        }
    }

    private class FakeGitHubApi(private val failDeleteBranch: Boolean = false) : GitHubApi {
        val closedPrs = mutableListOf<Int>()
        val deletedBranches = mutableListOf<String>()

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
            if (failDeleteBranch) error("branch delete failed")
            deletedBranches += branchName
        }

        override fun mergePullRequest(targetRepo: String, prNumber: Int) = Unit
    }

    private class FakeStoryWorkspaceService : StoryWorkspaceApi {
        val cleanedStoryKeys = mutableListOf<String>()

        override fun prepare(storyRun: StoryRunRecord, role: AgentRole): PreparedStoryWorkspace =
            throw UnsupportedOperationException()

        override fun syncAfterAgent(storyRun: StoryRunRecord, role: AgentRole): RepositorySyncResult =
            throw UnsupportedOperationException()

        override fun cleanup(storyKey: String): Boolean {
            cleanedStoryKeys += storyKey
            return true
        }
    }

    private class FakePreviewEnvironmentCleaner : PreviewApi {
        val cleanedNamespaces = mutableListOf<String>()

        override fun render(template: String?, prNumber: Int?): String? = PreviewApi.renderTemplate(template, prNumber)

        override fun cleanup(namespace: String): Boolean {
            cleanedNamespaces += namespace
            return true
        }
    }
}
