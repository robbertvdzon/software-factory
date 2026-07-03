package nl.vdzon.softwarefactory.youtrack

import nl.vdzon.softwarefactory.testsupport.InMemoryProcessedCommentStore
import nl.vdzon.softwarefactory.youtrack.clients.*
import nl.vdzon.softwarefactory.youtrack.repositories.*
import nl.vdzon.softwarefactory.youtrack.services.*

import nl.vdzon.softwarefactory.youtrack.*
import nl.vdzon.softwarefactory.core.*
import nl.vdzon.softwarefactory.youtrack.*
import nl.vdzon.softwarefactory.youtrack.*
import nl.vdzon.softwarefactory.youtrack.*

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

    @Test
    fun `isProcessed checks the local store first and skips the tracker reaction call`() {
        val issueTrackerClient = FakeYouTrackApi(markSucceeds = false)
        val store = InMemoryProcessedCommentStore()
        store.markProcessed("KAN-69", "10001", AgentRole.COST_MONITOR)
        val service = ProcessedCommentService(issueTrackerClient, store)

        assertTrue(service.isProcessed("KAN-69", "10001", AgentRole.COST_MONITOR))
        // Lokale DB kent de marker → géén dure YouTrack-reactie-lookup.
        assertEquals(0, issueTrackerClient.markerLookups)
    }

    @Test
    fun `isProcessed backfills the local store after a tracker hit so it is queried only once`() {
        val issueTrackerClient = FakeYouTrackApi(markSucceeds = true) // YouTrack kent de marker, DB nog niet
        val store = InMemoryProcessedCommentStore()
        val service = ProcessedCommentService(issueTrackerClient, store)

        assertTrue(service.isProcessed("KAN-69", "10001", AgentRole.ORCHESTRATOR))
        assertTrue(service.isProcessed("KAN-69", "10001", AgentRole.ORCHESTRATOR))

        // Eerste call vult de DB; de tweede komt uit de DB → maar één YouTrack-lookup totaal.
        assertEquals(1, issueTrackerClient.markerLookups)
        assertTrue(store.isProcessed("KAN-69", "10001", AgentRole.ORCHESTRATOR))
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

        var markerLookups = 0

        override fun hasProcessedCommentMarker(commentId: String, role: AgentRole): Boolean {
            markerLookups++
            return markSucceeds
        }

        override fun markCommentProcessed(commentId: String, role: AgentRole): Boolean =
            markSucceeds
    }
}
