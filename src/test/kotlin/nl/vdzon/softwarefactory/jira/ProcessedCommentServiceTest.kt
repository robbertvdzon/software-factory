package nl.vdzon.softwarefactory.jira

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProcessedCommentServiceTest {
    @Test
    fun `uses Jira marker when Jira accepts processed marker`() {
        val jiraClient = FakeJiraClient(markSucceeds = true)
        val store = InMemoryProcessedCommentStore()
        val service = ProcessedCommentService(jiraClient, store)

        val marker = service.markProcessed("KAN-69", "10001", AgentRole.REFINER)

        assertEquals(ProcessedCommentMarker.JIRA_COMMENT_MARKER, marker)
        assertFalse(store.isProcessed("KAN-69", "10001", AgentRole.REFINER))
    }

    @Test
    fun `falls back to database when Jira marker is unavailable`() {
        val jiraClient = FakeJiraClient(markSucceeds = false)
        val store = InMemoryProcessedCommentStore()
        val service = ProcessedCommentService(jiraClient, store)

        val marker = service.markProcessed("KAN-69", "10001", AgentRole.DEVELOPER)

        assertEquals(ProcessedCommentMarker.DATABASE_FALLBACK, marker)
        assertTrue(store.isProcessed("KAN-69", "10001", AgentRole.DEVELOPER))
        assertTrue(service.isProcessed("KAN-69", "10001", AgentRole.DEVELOPER))
    }

    private class FakeJiraClient(
        private val markSucceeds: Boolean,
    ) : JiraClient {
        override fun findAiIssues(projectKey: String, maxResults: Int): List<JiraIssue> = emptyList()

        override fun getIssue(issueKey: String): JiraIssue =
            throw UnsupportedOperationException()

        override fun updateIssueFields(issueKey: String, update: JiraFieldUpdate) {
            throw UnsupportedOperationException()
        }

        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): JiraComment =
            throw UnsupportedOperationException()

        override fun hasProcessedCommentMarker(commentId: String, role: AgentRole): Boolean =
            markSucceeds

        override fun markCommentProcessed(commentId: String, role: AgentRole): Boolean =
            markSucceeds
    }

    private class InMemoryProcessedCommentStore : ProcessedCommentStore {
        private val processed = mutableSetOf<Triple<String, String, AgentRole>>()

        override fun isProcessed(storyKey: String, commentId: String, role: AgentRole): Boolean =
            Triple(storyKey, commentId, role) in processed

        override fun markProcessed(storyKey: String, commentId: String, role: AgentRole) {
            processed += Triple(storyKey, commentId, role)
        }
    }
}
