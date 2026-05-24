package nl.vdzon.softwarefactory.youtrack

import nl.vdzon.softwarefactory.youtrack.AgentRole
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.youtrack.ProcessedCommentStore
import org.springframework.stereotype.Service

@Service
class ProcessedCommentService(
    private val issueTrackerClient: YouTrackApi,
    private val processedCommentStore: ProcessedCommentStore,
) {
    fun isProcessed(storyKey: String, commentId: String, role: AgentRole): Boolean =
        issueTrackerClient.hasProcessedCommentMarker(storyKey, commentId, role) ||
            processedCommentStore.isProcessed(storyKey, commentId, role)

    fun markProcessed(storyKey: String, commentId: String, role: AgentRole): ProcessedCommentMarker {
        val markedInTracker = issueTrackerClient.markCommentProcessed(storyKey, commentId, role)
        processedCommentStore.markProcessed(storyKey, commentId, role)
        return if (markedInTracker) {
            ProcessedCommentMarker.TRACKER_COMMENT_MARKER
        } else {
            ProcessedCommentMarker.DATABASE_FALLBACK
        }
    }
}

enum class ProcessedCommentMarker {
    TRACKER_COMMENT_MARKER,
    DATABASE_FALLBACK,
}
