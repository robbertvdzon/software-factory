package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.orchestrator.*
import nl.vdzon.softwarefactory.core.*
import nl.vdzon.softwarefactory.orchestrator.services.*

import nl.vdzon.softwarefactory.orchestrator.*
import nl.vdzon.softwarefactory.orchestrator.*
import nl.vdzon.softwarefactory.orchestrator.*

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.testsupport.InMemoryProcessedCommentStore
import nl.vdzon.softwarefactory.tracker.TrackerApi
import nl.vdzon.softwarefactory.core.TrackerApiException
import nl.vdzon.softwarefactory.core.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerIssueFields
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.tracker.services.ProcessedCommentService
import nl.vdzon.softwarefactory.tracker.repositories.ProcessedCommentStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class CostMonitorServiceTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-05-24T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `syncs token totals without intermediate threshold comments`() {
        val issueTracker = FakeTrackerApi()
        val service = service(issueTracker)
        val issue = issue(
            budget = 1000,
            tokensUsed = 760,
        )

        // 95% van het budget: fase 6 → géén tussentijds comment, alleen tokensync, geen pauze.
        val result = service.checkBudget(issue, storyRun(totalInputTokens = 950))

        assertEquals(emptyList<Int>(), result.postedThresholds)
        assertTrue(issueTracker.postedComments.isEmpty())
        assertEquals(950L, issueTracker.lastUpdate("KAN-1").values[TrackerField.AI_TOKENS_USED])
        assertFalse(issueTracker.lastUpdate("KAN-1").values.containsKey(TrackerField.PAUSED))
    }

    @Test
    fun `pauses the ticket when budget reaches 100 percent`() {
        val issueTracker = FakeTrackerApi()
        val service = service(issueTracker)
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
        assertEquals(true, issueTracker.lastUpdate("KAN-1").values[TrackerField.PAUSED])
        assertEquals(1000L, issueTracker.lastUpdate("KAN-1").values[TrackerField.AI_TOKENS_USED])
    }

    @Test
    fun `never pauses or warns when no budget is configured`() {
        val issueTracker = FakeTrackerApi()
        val service = service(issueTracker)
        val issue = issue(budget = null, tokensUsed = 0)

        val result = service.checkBudget(issue, storyRun(totalInputTokens = 10_000_000))

        assertFalse(result.paused)
        assertEquals(emptyList<Int>(), result.postedThresholds)
        assertTrue(issueTracker.postedComments.isEmpty())
        assertFalse(issueTracker.lastUpdate("KAN-1").values.containsKey(TrackerField.PAUSED))
        // Verbruik wordt nog wel bijgewerkt, alleen niet afgedwongen.
        assertEquals(10_000_000L, issueTracker.lastUpdate("KAN-1").values[TrackerField.AI_TOKENS_USED])
    }

    @Test
    fun `applies budget and continue triggers once`() {
        val issueTracker = FakeTrackerApi()
        val store = InMemoryProcessedCommentStore()
        val service = service(issueTracker, store)
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
        assertEquals(listOf(2000L, 3000L), issueTracker.updates.getValue("KAN-1").map { it.values[TrackerField.AI_TOKEN_BUDGET] })
        assertTrue(store.isProcessed("KAN-1", "10", AgentRole.COST_MONITOR))
        assertTrue(store.isProcessed("KAN-1", "11", AgentRole.COST_MONITOR))
    }

    @Test
    fun `comments without budget instructions do not trigger processed marker lookups`() {
        val issueTracker = FakeTrackerApi()
        val service = service(issueTracker)
        val issue = issue(
            comments = listOf(
                comment("20", "Gewone PO-opmerking."),
                comment("21", "[DEVELOPER] Agent comment zonder budget command."),
            ),
        )

        val updated = service.applyBudgetTriggers(issue)

        assertEquals(issue, updated)
        assertTrue(issueTracker.processedMarkerChecks.isEmpty())
    }

    @Test
    fun `closes active run when tracker issue is missing`() {
        val storyRunRepository = FakeStoryRunRepository(
            activeRuns = listOf(storyRun(storyKey = "KAN-69", totalInputTokens = 12)),
        )
        val issueTracker = FakeTrackerApi { issueKey ->
            throw TrackerApiException("Tracker request GET /api/issues/$issueKey failed with status 404: Not Found")
        }
        val service = service(issueTracker, storyRunRepository = storyRunRepository)

        service.checkAllActiveStories()

        assertEquals(listOf(ClosedStoryRun(1, CostMonitorService.MISSING_TRACKER_STATUS, OffsetDateTime.now(clock))), storyRunRepository.closed)
        assertTrue(issueTracker.updates.isEmpty())
        assertTrue(issueTracker.postedComments.isEmpty())
    }

    @Test
    fun `keeps active run open when tracker lookup has transient failure`() {
        val storyRunRepository = FakeStoryRunRepository(
            activeRuns = listOf(storyRun(storyKey = "KAN-1", totalInputTokens = 12)),
        )
        val issueTracker = FakeTrackerApi {
            throw TrackerApiException("Tracker request GET /api/issues/KAN-1 failed with status 503: unavailable")
        }
        val service = service(issueTracker, storyRunRepository = storyRunRepository)

        service.checkAllActiveStories()

        assertTrue(storyRunRepository.closed.isEmpty())
        assertTrue(issueTracker.updates.isEmpty())
        assertTrue(issueTracker.postedComments.isEmpty())
    }

    private fun service(
        issueTracker: FakeTrackerApi,
        store: InMemoryProcessedCommentStore = InMemoryProcessedCommentStore(),
        storyRunRepository: FakeStoryRunRepository = FakeStoryRunRepository(),
    ): CostMonitorService =
        CostMonitorService(
            issueTrackerClient = issueTracker,
            storyRunRepository = storyRunRepository,
            processedCommentService = ProcessedCommentService(issueTracker, store),
            clock = clock,
        )

    private fun issue(
        comments: List<TrackerComment> = emptyList(),
        budget: Long? = 40000,
        tokensUsed: Long = 0,
        paused: Boolean = false,
    ): TrackerIssue =
        TrackerIssue(
            key = "KAN-1",
            summary = "Story KAN-1",
            status = "AI",
            fields = TrackerIssueFields(
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

    private fun storyRun(
        storyKey: String = "KAN-1",
        totalInputTokens: Long,
    ): StoryRunRecord =
        StoryRunRecord(
            id = 1,
            storyKey = storyKey,
            targetRepo = "git@example/repo.git",
            totalInputTokens = totalInputTokens,
        )

    private fun comment(id: String, body: String): TrackerComment =
        TrackerComment(id, "user", "User", body, null)

    private class FakeTrackerApi(
        private val issueLookup: (String) -> TrackerIssue = { throw UnsupportedOperationException() },
    ) : TrackerApi {
        val updates: MutableMap<String, MutableList<TrackerFieldUpdate>> = mutableMapOf()
        val postedComments = mutableListOf<Triple<String, AgentRole, String>>()
        val processedMarkerChecks = mutableListOf<Pair<String, AgentRole>>()

        override fun findAiIssues(projectKey: String, maxResults: Int): List<TrackerIssue> = emptyList()

        override fun getIssue(issueKey: String): TrackerIssue =
            issueLookup(issueKey)

        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
            updates.getOrPut(issueKey) { mutableListOf() } += update
        }

        override fun transitionIssue(issueKey: String, statusName: String) = Unit

        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment {
            val body = "${role.commentPrefix} $message"
            postedComments += Triple(issueKey, role, body)
            return TrackerComment("posted-${postedComments.size}", "factory", "Factory", body, null)
        }

        override fun hasProcessedCommentMarker(commentId: String, role: AgentRole): Boolean {
            processedMarkerChecks += commentId to role
            return false
        }

        override fun markCommentProcessed(commentId: String, role: AgentRole): Boolean =
            false

        fun lastUpdate(issueKey: String): TrackerFieldUpdate =
            updates.getValue(issueKey).last()
    }

    private data class ClosedStoryRun(
        val storyRunId: Long,
        val finalStatus: String,
        val endedAt: OffsetDateTime,
    )

    private class FakeStoryRunRepository(
        private val activeRuns: List<StoryRunRecord> = emptyList(),
    ) : StoryRunRepository {
        val closed = mutableListOf<ClosedStoryRun>()

        override fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord =
            throw UnsupportedOperationException()

        override fun get(storyRunId: Long): StoryRunRecord? = null

        override fun updatePullRequest(
            storyRunId: Long,
            branchName: String,
            prNumber: Int?,
            prUrl: String?,
            baseBranch: String?,
            branchPrefix: String?,
            previewUrlTemplate: String?,
            previewNamespaceTemplate: String?,
            previewDbSecretRecipe: String?,
        ) = Unit

        override fun activePullRequests(): List<StoryRunRecord> = emptyList()

        override fun activeRuns(): List<StoryRunRecord> = activeRuns

        override fun close(storyRunId: Long, finalStatus: String, endedAt: OffsetDateTime) {
            closed += ClosedStoryRun(storyRunId, finalStatus, endedAt)
        }
    }
}
