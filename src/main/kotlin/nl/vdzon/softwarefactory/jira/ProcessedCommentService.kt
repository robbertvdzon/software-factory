package nl.vdzon.softwarefactory.jira

import org.springframework.stereotype.Service

@Service
class ProcessedCommentService(
    private val jiraClient: JiraClient,
    private val processedCommentStore: ProcessedCommentStore,
) {
    fun isProcessed(storyKey: String, commentId: String, role: AgentRole): Boolean =
        jiraClient.hasProcessedCommentMarker(commentId, role) ||
            processedCommentStore.isProcessed(storyKey, commentId, role)

    fun markProcessed(storyKey: String, commentId: String, role: AgentRole): ProcessedCommentMarker {
        val markedInJira = jiraClient.markCommentProcessed(commentId, role)
        return if (markedInJira) {
            ProcessedCommentMarker.JIRA_COMMENT_MARKER
        } else {
            processedCommentStore.markProcessed(storyKey, commentId, role)
            ProcessedCommentMarker.DATABASE_FALLBACK
        }
    }
}

enum class ProcessedCommentMarker {
    JIRA_COMMENT_MARKER,
    DATABASE_FALLBACK,
}
