package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.orchestrator.*
import nl.vdzon.softwarefactory.orchestrator.services.*

import nl.vdzon.softwarefactory.orchestrator.*
import nl.vdzon.softwarefactory.orchestrator.*
import nl.vdzon.softwarefactory.orchestrator.*

import nl.vdzon.softwarefactory.youtrack.AgentRole
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.youtrack.TrackerComment
import nl.vdzon.softwarefactory.youtrack.TrackerFieldUpdate
import nl.vdzon.softwarefactory.youtrack.TrackerIssue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

class CreditsPauseServiceTest {
    private val now: OffsetDateTime = OffsetDateTime.parse("2026-05-23T20:00:00Z")
    private val clock: Clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC)

    @Test
    fun `credits exhausted writes system pause and posts orchestrator comment`() {
        val state = InMemorySystemStateRepository()
        val issueTracker = FakeYouTrackApi()
        val service = CreditsPauseService(state, issueTracker, settings(), clock)

        service.handleCreditsExhausted("KAN-69", "HTTP 429 credit exhausted")

        assertEquals(now.plusMinutes(30), state.current().creditsPausedUntil)
        assertTrue(state.current().creditsPausedReason?.contains("KAN-69") == true)
        assertEquals(AgentRole.ORCHESTRATOR, issueTracker.postedComments.single().second)
        assertTrue(issueTracker.postedComments.single().third.contains("AI-credits uitgeput"))
    }

    @Test
    fun `active pause expires after paused until`() {
        val state = InMemorySystemStateRepository().apply {
            pauseCredits(now.plusMinutes(10), "manual")
        }
        val service = CreditsPauseService(state, FakeYouTrackApi(), settings(), clock)

        assertEquals(now.plusMinutes(10), service.activePause(now)?.until)
        assertNull(service.activePause(now.plusMinutes(11)))
    }

    private fun settings(): OrchestratorSettings =
        OrchestratorSettings(
            pollInterval = Duration.ofSeconds(15),
            maxParallelRefiner = 1,
            maxParallelDeveloper = 2,
            maxParallelReviewer = 2,
            maxParallelTester = 1,
            maxParallelTotal = 4,
            maxDeveloperLoopbacks = 5,
            maxTransientRetries = 2,
            hardTimeout = Duration.ofMinutes(60),
            costMonitorInterval = Duration.ofMinutes(5),
            creditsPauseDefault = Duration.ofMinutes(30),
        )

    private class InMemorySystemStateRepository : SystemStateRepository {
        private var state = SystemStateRecord(null, null)

        override fun current(): SystemStateRecord =
            state

        override fun pauseCredits(until: OffsetDateTime, reason: String) {
            state = SystemStateRecord(until, reason)
        }

        override fun resumeCredits() {
            state = SystemStateRecord(null, null)
        }
    }

    private class FakeYouTrackApi : YouTrackApi {
        val postedComments = mutableListOf<Triple<String, AgentRole, String>>()

        override fun findAiIssues(projectKey: String, maxResults: Int): List<TrackerIssue> = emptyList()

        override fun getIssue(issueKey: String): TrackerIssue =
            throw UnsupportedOperationException()

        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) = Unit

        override fun transitionIssue(issueKey: String, statusName: String) = Unit

        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment {
            val body = "${role.commentPrefix} $message"
            postedComments += Triple(issueKey, role, body)
            return TrackerComment("posted", "factory", "Factory", body, null)
        }

        override fun hasProcessedCommentMarker(commentId: String, role: AgentRole): Boolean = false

        override fun markCommentProcessed(commentId: String, role: AgentRole): Boolean = false
    }
}
