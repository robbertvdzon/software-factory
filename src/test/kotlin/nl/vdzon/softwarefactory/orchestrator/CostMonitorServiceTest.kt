package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.jira.AgentRole
import nl.vdzon.softwarefactory.jira.JiraClient
import nl.vdzon.softwarefactory.jira.JiraComment
import nl.vdzon.softwarefactory.jira.JiraFieldUpdate
import nl.vdzon.softwarefactory.jira.JiraIssue
import nl.vdzon.softwarefactory.jira.JiraIssueFields
import nl.vdzon.softwarefactory.jira.JiraKnownField
import nl.vdzon.softwarefactory.jira.ProcessedCommentService
import nl.vdzon.softwarefactory.jira.ProcessedCommentStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class CostMonitorServiceTest {
    @Test
    fun `posts threshold comments idempotently and syncs token totals`() {
        val jira = FakeJiraClient()
        val service = service(jira)
        val issue = issue(
            comments = listOf(comment("1", "[COST-MONITOR] 75% bereikt: 760/1000 tokens.")),
            budget = 1000,
            tokensUsed = 760,
        )

        val result = service.checkBudget(issue, storyRun(totalInputTokens = 950))

        assertEquals(listOf(90), result.postedThresholds)
        assertEquals("[COST-MONITOR] 90% bereikt: 950/1000 tokens.", jira.postedComments.single().third)
        assertEquals(950L, jira.lastUpdate("KAN-1").values[JiraKnownField.AI_TOKENS_USED])
        assertFalse(jira.lastUpdate("KAN-1").values.containsKey(JiraKnownField.PAUSED))
    }

    @Test
    fun `pauses the ticket when budget reaches 100 percent`() {
        val jira = FakeJiraClient()
        val service = service(jira)
        val issue = issue(
            comments = listOf(
                comment("1", "[COST-MONITOR] 75% bereikt: 760/1000 tokens."),
                comment("2", "[COST-MONITOR] 90% bereikt: 950/1000 tokens."),
            ),
            budget = 1000,
            tokensUsed = 950,
        )

        val result = service.checkBudget(issue, storyRun(totalInputTokens = 1000))

        assertTrue(result.paused)
        assertEquals(listOf(100), result.postedThresholds)
        assertEquals(true, jira.lastUpdate("KAN-1").values[JiraKnownField.PAUSED])
        assertEquals(1000L, jira.lastUpdate("KAN-1").values[JiraKnownField.AI_TOKENS_USED])
    }

    @Test
    fun `applies budget and continue triggers once`() {
        val jira = FakeJiraClient()
        val store = InMemoryProcessedCommentStore()
        val service = service(jira, store)
        val budgetIssue = issue(
            paused = true,
            budget = 1000,
            comments = listOf(comment("10", "BUDGET=2000")),
        )
        val continueIssue = issue(
            paused = true,
            budget = 2000,
            comments = listOf(comment("11", "CONTINUE")),
        )

        val budgetUpdated = service.applyBudgetTriggers(budgetIssue)
        val continueUpdated = service.applyBudgetTriggers(continueIssue)
        val again = service.applyBudgetTriggers(budgetIssue)

        assertEquals(2000L, budgetUpdated.fields.aiTokenBudget)
        assertFalse(budgetUpdated.fields.paused)
        assertEquals(3000L, continueUpdated.fields.aiTokenBudget)
        assertFalse(continueUpdated.fields.paused)
        assertEquals(budgetIssue, again)
        assertEquals(listOf(2000L, 3000L), jira.updates.getValue("KAN-1").map { it.values[JiraKnownField.AI_TOKEN_BUDGET] })
        assertTrue(store.isProcessed("KAN-1", "10", AgentRole.COST_MONITOR))
        assertTrue(store.isProcessed("KAN-1", "11", AgentRole.COST_MONITOR))
    }

    private fun service(
        jira: FakeJiraClient,
        store: InMemoryProcessedCommentStore = InMemoryProcessedCommentStore(),
    ): CostMonitorService =
        CostMonitorService(
            jiraClient = jira,
            storyRunRepository = FakeStoryRunRepository(),
            processedCommentService = ProcessedCommentService(jira, store),
        )

    private fun issue(
        comments: List<JiraComment> = emptyList(),
        budget: Long = 40000,
        tokensUsed: Long = 0,
        paused: Boolean = false,
    ): JiraIssue =
        JiraIssue(
            key = "KAN-1",
            summary = "Story KAN-1",
            status = "AI",
            fields = JiraIssueFields(
                targetRepo = "git@example/repo.git",
                aiPhase = null,
                aiLevel = 5,
                aiTokenBudget = budget,
                aiTokensUsed = tokensUsed,
                agentStartedAt = null,
                paused = paused,
                error = null,
            ),
            comments = comments,
        )

    private fun storyRun(totalInputTokens: Long): StoryRunRecord =
        StoryRunRecord(
            id = 1,
            storyKey = "KAN-1",
            targetRepo = "git@example/repo.git",
            totalInputTokens = totalInputTokens,
        )

    private fun comment(id: String, body: String): JiraComment =
        JiraComment(id, "user", "User", body, null)

    private class FakeJiraClient : JiraClient {
        val updates: MutableMap<String, MutableList<JiraFieldUpdate>> = mutableMapOf()
        val postedComments = mutableListOf<Triple<String, AgentRole, String>>()

        override fun findAiIssues(projectKey: String, maxResults: Int): List<JiraIssue> = emptyList()

        override fun getIssue(issueKey: String): JiraIssue =
            throw UnsupportedOperationException()

        override fun updateIssueFields(issueKey: String, update: JiraFieldUpdate) {
            updates.getOrPut(issueKey) { mutableListOf() } += update
        }

        override fun transitionIssue(issueKey: String, statusName: String) = Unit

        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): JiraComment {
            val body = "${role.commentPrefix} $message"
            postedComments += Triple(issueKey, role, body)
            return JiraComment("posted-${postedComments.size}", "factory", "Factory", body, null)
        }

        override fun hasProcessedCommentMarker(commentId: String, role: AgentRole): Boolean =
            false

        override fun markCommentProcessed(commentId: String, role: AgentRole): Boolean =
            false

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

    private class FakeStoryRunRepository : StoryRunRepository {
        override fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord =
            throw UnsupportedOperationException()

        override fun get(storyRunId: Long): StoryRunRecord? = null

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

        override fun activePullRequests(): List<StoryRunRecord> = emptyList()

        override fun activeRuns(): List<StoryRunRecord> = emptyList()

        override fun close(storyRunId: Long, finalStatus: String, endedAt: OffsetDateTime) = Unit
    }
}
