package nl.vdzon.softwarefactory.youtrack

import nl.vdzon.softwarefactory.youtrack.clients.*
import nl.vdzon.softwarefactory.youtrack.parsers.*
import nl.vdzon.softwarefactory.youtrack.repositories.*
import nl.vdzon.softwarefactory.youtrack.services.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProcessedCommentServiceTest {
    @Test
    fun `uses tracker marker when issue tracker accepts processed marker`() {
        val issueTrackerClient = FakeYouTrackApi(markSucceeds = true)
        val store = InMemoryProcessedCommentStore()
        val service = ProcessedCommentService(issueTrackerClient, store)

        val marker = service.markProcessed("KAN-69", "10001", AgentRole.REFINER)

        assertEquals(ProcessedCommentMarker.TRACKER_COMMENT_MARKER, marker)
        assertTrue(store.isProcessed("KAN-69", "10001", AgentRole.REFINER))
    }

    @Test
    fun `falls back to database when tracker marker is unavailable`() {
        val issueTrackerClient = FakeYouTrackApi(markSucceeds = false)
        val store = InMemoryProcessedCommentStore()
        val service = ProcessedCommentService(issueTrackerClient, store)

        val marker = service.markProcessed("KAN-69", "10001", AgentRole.DEVELOPER)

        assertEquals(ProcessedCommentMarker.DATABASE_FALLBACK, marker)
        assertTrue(store.isProcessed("KAN-69", "10001", AgentRole.DEVELOPER))
        assertTrue(service.isProcessed("KAN-69", "10001", AgentRole.DEVELOPER))
    }

    private class FakeYouTrackApi(
        private val markSucceeds: Boolean,
    ) : YouTrackApi {
        override fun findAiIssues(projectKey: String, maxResults: Int): List<TrackerIssue> = emptyList()

        override fun getIssue(issueKey: String): TrackerIssue =
            throw UnsupportedOperationException()

        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
            throw UnsupportedOperationException()
        }

        override fun transitionIssue(issueKey: String, statusName: String) {
            throw UnsupportedOperationException()
        }

        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment =
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
