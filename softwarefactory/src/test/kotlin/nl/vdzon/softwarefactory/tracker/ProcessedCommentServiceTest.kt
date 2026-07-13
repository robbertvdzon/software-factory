package nl.vdzon.softwarefactory.tracker

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.testsupport.InMemoryProcessedCommentStore
import nl.vdzon.softwarefactory.tracker.clients.*
import nl.vdzon.softwarefactory.tracker.repositories.*
import nl.vdzon.softwarefactory.tracker.services.*

import nl.vdzon.softwarefactory.tracker.*
import nl.vdzon.softwarefactory.core.contracts.*
import nl.vdzon.softwarefactory.tracker.*
import nl.vdzon.softwarefactory.tracker.*
import nl.vdzon.softwarefactory.tracker.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProcessedCommentServiceTest {
    @Test
    fun `uses tracker marker when issue tracker accepts processed marker`() {
        val issueTrackerClient = FakeTrackerApi(markSucceeds = true)
        val store = InMemoryProcessedCommentStore()
        val service = ProcessedCommentService(issueTrackerClient, store)

        val marker = service.markProcessed("KAN-69", "10001", AgentRole.REFINER)

        assertEquals(ProcessedCommentMarker.TRACKER_COMMENT_MARKER, marker)
        assertTrue(store.isProcessed("KAN-69", "10001", AgentRole.REFINER))
    }

    @Test
    fun `falls back to database when tracker marker is unavailable`() {
        val issueTrackerClient = FakeTrackerApi(markSucceeds = false)
        val store = InMemoryProcessedCommentStore()
        val service = ProcessedCommentService(issueTrackerClient, store)

        val marker = service.markProcessed("KAN-69", "10001", AgentRole.DEVELOPER)

        assertEquals(ProcessedCommentMarker.DATABASE_FALLBACK, marker)
        assertTrue(store.isProcessed("KAN-69", "10001", AgentRole.DEVELOPER))
        assertTrue(service.isProcessed("KAN-69", "10001", AgentRole.DEVELOPER))
    }

    @Test
    fun `isProcessed checks the local store first and skips the tracker reaction call`() {
        val issueTrackerClient = FakeTrackerApi(markSucceeds = false)
        val store = InMemoryProcessedCommentStore()
        store.markProcessed("KAN-69", "10001", AgentRole.COST_MONITOR)
        val service = ProcessedCommentService(issueTrackerClient, store)

        assertTrue(service.isProcessed("KAN-69", "10001", AgentRole.COST_MONITOR))
        // Lokale DB kent de marker → géén dure tracker-reactie-lookup.
        assertEquals(0, issueTrackerClient.markerLookups)
    }

    @Test
    fun `isProcessed backfills the local store after a tracker hit so it is queried only once`() {
        val issueTrackerClient = FakeTrackerApi(markSucceeds = true) // Tracker kent de marker, DB nog niet
        val store = InMemoryProcessedCommentStore()
        val service = ProcessedCommentService(issueTrackerClient, store)

        assertTrue(service.isProcessed("KAN-69", "10001", AgentRole.ORCHESTRATOR))
        assertTrue(service.isProcessed("KAN-69", "10001", AgentRole.ORCHESTRATOR))

        // Eerste call vult de DB; de tweede komt uit de DB → maar één tracker-lookup totaal.
        assertEquals(1, issueTrackerClient.markerLookups)
        assertTrue(store.isProcessed("KAN-69", "10001", AgentRole.ORCHESTRATOR))
    }

    private class FakeTrackerApi(
        private val markSucceeds: Boolean,
    ) : TrackerApi {
        override fun findAiIssues(maxResults: Int, includeFinished: Boolean): List<TrackerIssue> = emptyList()

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
