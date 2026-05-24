package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.jira.AgentRole
import nl.vdzon.softwarefactory.jira.JiraClient
import nl.vdzon.softwarefactory.jira.JiraComment
import nl.vdzon.softwarefactory.jira.JiraFieldUpdate
import nl.vdzon.softwarefactory.jira.JiraIssue
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
        val jira = FakeJiraClient()
        val service = CreditsPauseService(state, jira, settings(), clock)

        service.handleCreditsExhausted("KAN-69", "HTTP 429 credit exhausted")

        assertEquals(now.plusMinutes(30), state.current().creditsPausedUntil)
        assertTrue(state.current().creditsPausedReason?.contains("KAN-69") == true)
        assertEquals(AgentRole.ORCHESTRATOR, jira.postedComments.single().second)
        assertTrue(jira.postedComments.single().third.contains("AI-credits uitgeput"))
    }

    @Test
    fun `active pause expires after paused until`() {
        val state = InMemorySystemStateRepository().apply {
            pauseCredits(now.plusMinutes(10), "manual")
        }
        val service = CreditsPauseService(state, FakeJiraClient(), settings(), clock)

        assertEquals(now.plusMinutes(10), service.activePause(now)?.until)
        assertNull(service.activePause(now.plusMinutes(11)))
    }

    private fun settings(): OrchestratorSettings =
        OrchestratorSettings(
            pollingEnabled = true,
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

    private class FakeJiraClient : JiraClient {
        val postedComments = mutableListOf<Triple<String, AgentRole, String>>()

        override fun findAiIssues(projectKey: String, maxResults: Int): List<JiraIssue> = emptyList()

        override fun getIssue(issueKey: String): JiraIssue =
            throw UnsupportedOperationException()

        override fun updateIssueFields(issueKey: String, update: JiraFieldUpdate) = Unit

        override fun transitionIssue(issueKey: String, statusName: String) = Unit

        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): JiraComment {
            val body = "${role.commentPrefix} $message"
            postedComments += Triple(issueKey, role, body)
            return JiraComment("posted", "factory", "Factory", body, null)
        }

        override fun hasProcessedCommentMarker(commentId: String, role: AgentRole): Boolean = false

        override fun markCommentProcessed(commentId: String, role: AgentRole): Boolean = false
    }
}
