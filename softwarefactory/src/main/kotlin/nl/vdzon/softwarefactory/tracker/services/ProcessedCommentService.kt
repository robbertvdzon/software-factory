package nl.vdzon.softwarefactory.tracker.services

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.ProcessedCommentMarker
import nl.vdzon.softwarefactory.tracker.ProcessedCommentsApi
import nl.vdzon.softwarefactory.tracker.ProcessedCommentPort
import nl.vdzon.softwarefactory.tracker.repositories.ProcessedCommentStore
import org.springframework.stereotype.Service

@Service
class ProcessedCommentService(
    private val issueTrackerClient: ProcessedCommentPort,
    private val processedCommentStore: ProcessedCommentStore,
) : ProcessedCommentsApi {
    override fun isProcessed(storyKey: String, commentId: String, role: AgentRole): Boolean {
        // Lokale DB eerst (snel, in-proces).
        if (processedCommentStore.isProcessed(storyKey, commentId, role)) {
            return true
        }
        // Niet in de DB → val één keer terug op de tracker-reactie-lookup (dure cloud-call). Staat 'ie
        // daar wél, backfill dan de DB, anders blijven we deze (historisch gemarkeerde) comment elke
        // poll opnieuw bij de tracker opvragen — wat de cloud onder load laat throttelen.
        val markedInTracker = issueTrackerClient.hasProcessedCommentMarker(storyKey, commentId, role)
        if (markedInTracker) {
            processedCommentStore.markProcessed(storyKey, commentId, role)
        }
        return markedInTracker
    }

    override fun markProcessed(storyKey: String, commentId: String, role: AgentRole): ProcessedCommentMarker {
        val markedInTracker = issueTrackerClient.markCommentProcessed(storyKey, commentId, role)
        processedCommentStore.markProcessed(storyKey, commentId, role)
        return if (markedInTracker) {
            ProcessedCommentMarker.TRACKER_COMMENT_MARKER
        } else {
            ProcessedCommentMarker.DATABASE_FALLBACK
        }
    }
}
