package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.github.PullRequestClient
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.github.PullRequestInfo
import nl.vdzon.softwarefactory.jira.AgentRole
import nl.vdzon.softwarefactory.jira.JiraClient
import nl.vdzon.softwarefactory.jira.JiraComment
import nl.vdzon.softwarefactory.jira.JiraFieldUpdate
import nl.vdzon.softwarefactory.jira.JiraIssue
import nl.vdzon.softwarefactory.jira.JiraIssueFields
import nl.vdzon.softwarefactory.jira.JiraKnownField
import nl.vdzon.softwarefactory.jira.ProcessedCommentService
import nl.vdzon.softwarefactory.jira.ProcessedCommentStore
import nl.vdzon.softwarefactory.preview.PreviewEnvironmentCleaner
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
        val jira = FakeJiraClient()
        val store = InMemoryProcessedCommentStore()
        val service = service(jira, store = store)
        val issue = issue(
            paused = true,
            error = "budget exceeded",
            comments = listOf(comment("10", "@factory:command:resume\nLEVEL=7")),
        )

        val applied = service.apply(issue)
        val again = service.apply(issue)

        assertNull(applied.stopResult)
        assertFalse(applied.issue.fields.paused)
        assertNull(applied.issue.fields.error)
        assertEquals(7, applied.issue.fields.aiLevel)
        assertEquals(issue, again.issue)
        assertEquals(
            listOf(
                mapOf(JiraKnownField.PAUSED to false, JiraKnownField.ERROR to null),
                mapOf(JiraKnownField.AI_LEVEL to 7),
            ),
            jira.updates.getValue("KAN-1").map { it.values },
        )
        assertTrue(store.isProcessed("KAN-1", "10", AgentRole.ORCHESTRATOR))
    }

    @Test
    fun `pause and kill stop further orchestration`() {
        val jira = FakeJiraClient()
        val runtime = FakeAgentRuntime()
        val service = service(jira, runtime = runtime)
        val issue = issue(comments = listOf(comment("11", "@factory:command:kill")))

        val applied = service.apply(issue)

        assertEquals(IssueProcessResult.Skipped("KAN-1", "killed"), applied.stopResult)
        assertEquals(listOf("KAN-1"), runtime.killedStories)
        assertEquals(true, jira.lastUpdate("KAN-1").values[JiraKnownField.PAUSED])
    }

    @Test
    fun `delete closes PR branch preview run and transitions to Done`() {
        val jira = FakeJiraClient()
        val runtime = FakeAgentRuntime()
        val storyRuns = InMemoryStoryRunRepository().withPullRequest()
        val pullRequests = FakePullRequestClient()
        val previewCleaner = FakePreviewEnvironmentCleaner()
        val service = service(
            jira = jira,
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
        assertEquals("(CANCELLED) Story KAN-1", jira.summaryUpdates.single().second)
        assertEquals("Done", jira.transitions.single().second)
    }

    @Test
    fun `merge squashes PR cleans preview closes run and transitions to Done`() {
        val jira = FakeJiraClient()
        val storyRuns = InMemoryStoryRunRepository().withPullRequest()
        val pullRequests = FakePullRequestClient()
        val previewCleaner = FakePreviewEnvironmentCleaner()
        val service = service(
            jira = jira,
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
        assertEquals("Done", jira.transitions.single().second)
    }

    @Test
    fun `re implement closes resources clears fields and deletes agent comments`() {
        val jira = FakeJiraClient()
        val storyRuns = InMemoryStoryRunRepository().withPullRequest()
        val pullRequests = FakePullRequestClient()
        val previewCleaner = FakePreviewEnvironmentCleaner()
        val service = service(
            jira = jira,
            storyRuns = storyRuns,
            pullRequests = pullRequests,
            previewCleaner = previewCleaner,
        )
        val issue = issue(phase = "tested-with-feedback-for-developer", error = "bad run", comments = listOf(comment("14", "@factory:command:re-implement")))

        val applied = service.apply(issue)

        assertEquals(IssueProcessResult.Skipped("KAN-1", "re-implement"), applied.stopResult)
        assertEquals(listOf(42), pullRequests.closedPrs)
        assertEquals(listOf("ai/KAN-1"), pullRequests.deletedBranches)
        assertEquals(listOf("app-pr-42"), previewCleaner.cleanedNamespaces)
        assertEquals(listOf("KAN-1"), jira.deletedAgentComments)
        assertEquals(listOf(1L to "re-implement"), storyRuns.closed)
        val lastUpdate = jira.lastUpdate("KAN-1").values
        assertTrue(lastUpdate.containsKey(JiraKnownField.AI_PHASE))
        assertNull(lastUpdate[JiraKnownField.AI_PHASE])
        assertEquals(false, lastUpdate[JiraKnownField.PAUSED])
        assertNull(lastUpdate[JiraKnownField.ERROR])
    }

    private fun service(
        jira: FakeJiraClient,
        store: InMemoryProcessedCommentStore = InMemoryProcessedCommentStore(),
        runtime: FakeAgentRuntime = FakeAgentRuntime(),
        storyRuns: InMemoryStoryRunRepository = InMemoryStoryRunRepository(),
        pullRequests: FakePullRequestClient = FakePullRequestClient(),
        previewCleaner: FakePreviewEnvironmentCleaner = FakePreviewEnvironmentCleaner(),
    ): ManualCommandService =
        ManualCommandService(
            jiraClient = jira,
            processedCommentService = ProcessedCommentService(jira, store),
            agentRuntime = runtime,
            storyRunRepository = storyRuns,
            pullRequestClient = pullRequests,
            previewEnvironmentCleaner = previewCleaner,
            clock = clock,
        )

    private fun issue(
        phase: String? = null,
        paused: Boolean = false,
        error: String? = null,
        comments: List<JiraComment> = emptyList(),
    ): JiraIssue =
        JiraIssue(
            key = "KAN-1",
            summary = "Story KAN-1",
            status = "AI",
            fields = JiraIssueFields(
                targetRepo = "git@github.com:robbertvdzon/sample-build-project.git",
                aiPhase = phase,
                aiLevel = 5,
                aiTokenBudget = 40000,
                aiTokensUsed = 0,
                agentStartedAt = null,
                paused = paused,
                error = error,
            ),
            comments = comments,
        )

    private fun comment(id: String, body: String): JiraComment =
        JiraComment(id, "user", "User", body, null)

    private class FakeJiraClient : JiraClient {
        val updates = mutableMapOf<String, MutableList<JiraFieldUpdate>>()
        val transitions = mutableListOf<Pair<String, String>>()
        val summaryUpdates = mutableListOf<Pair<String, String>>()
        val deletedAgentComments = mutableListOf<String>()

        override fun findAiIssues(projectKey: String, maxResults: Int): List<JiraIssue> = emptyList()

        override fun getIssue(issueKey: String): JiraIssue =
            throw UnsupportedOperationException()

        override fun updateIssueFields(issueKey: String, update: JiraFieldUpdate) {
            updates.getOrPut(issueKey) { mutableListOf() } += update
        }

        override fun updateIssueSummary(issueKey: String, summary: String) {
            summaryUpdates += issueKey to summary
        }

        override fun transitionIssue(issueKey: String, statusName: String) {
            transitions += issueKey to statusName
        }

        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): JiraComment =
            throw UnsupportedOperationException()

        override fun hasProcessedCommentMarker(commentId: String, role: AgentRole): Boolean =
            false

        override fun markCommentProcessed(commentId: String, role: AgentRole): Boolean =
            false

        override fun deleteAgentComments(issueKey: String): Int {
            deletedAgentComments += issueKey
            return 1
        }

        fun lastUpdate(issueKey: String): JiraFieldUpdate =
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
            prNumber: Int,
            prUrl: String?,
            baseBranch: String?,
            branchPrefix: String?,
            previewUrlTemplate: String?,
            previewNamespaceTemplate: String?,
            previewDbSecretRecipe: String?,
        ) = Unit

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

    private class FakePullRequestClient : PullRequestClient {
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

    private class FakePreviewEnvironmentCleaner : PreviewEnvironmentCleaner {
        val cleanedNamespaces = mutableListOf<String>()

        override fun cleanup(namespace: String): Boolean {
            cleanedNamespaces += namespace
            return true
        }
    }
}
