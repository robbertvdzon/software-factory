package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.jira.AgentRole
import nl.vdzon.softwarefactory.jira.JiraClient
import nl.vdzon.softwarefactory.jira.JiraComment
import nl.vdzon.softwarefactory.jira.JiraFieldUpdate
import nl.vdzon.softwarefactory.jira.JiraIssue
import nl.vdzon.softwarefactory.jira.JiraIssueFields
import nl.vdzon.softwarefactory.jira.JiraKnownField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class OrchestratorServiceTest {
    private val now: OffsetDateTime = OffsetDateTime.parse("2026-05-23T20:00:00Z")
    private val clock: Clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC)

    @Test
    fun `poll skips paused and errored issues and dispatches empty phase to refiner`() {
        val jira = FakeJiraClient(
            listOf(
                issue("KAN-1", paused = true),
                issue("KAN-2", error = "blocked"),
                issue("KAN-3", phase = null),
            ),
        )
        val runtime = FakeAgentRuntime(now)
        val agentRuns = InMemoryAgentRunRepository()
        val service = service(jira, runtime = runtime, agentRuns = agentRuns)

        val result = service.pollOnce()

        assertEquals(
            listOf(
                IssueProcessResult.Skipped("KAN-1", "paused"),
                IssueProcessResult.Skipped("KAN-2", "error"),
                IssueProcessResult.Dispatched("KAN-3", AgentRole.REFINER, "factory-KAN-3-refiner"),
            ),
            result.issueResults,
        )
        assertEquals("refining", jira.lastUpdate("KAN-3").values[JiraKnownField.AI_PHASE])
        assertEquals(now, jira.lastUpdate("KAN-3").values[JiraKnownField.AGENT_STARTED_AT])
        assertEquals("KAN-3", runtime.dispatches.single().labels["story-key"])
        assertEquals("refiner", runtime.dispatches.single().labels["role"])
        assertEquals(1, agentRuns.countForRole(1, AgentRole.REFINER))
    }

    @Test
    fun `dispatches completed phases to the next role and respects concurrency caps`() {
        val jira = FakeJiraClient(
            listOf(
                issue("KAN-4", phase = "developed"),
                issue("KAN-5", phase = "review-finished"),
                issue("KAN-6", phase = "refined-finished"),
            ),
        )
        val runtime = FakeAgentRuntime(now).apply {
            runningByRole[AgentRole.DEVELOPER] = 2
        }
        val service = service(jira, runtime = runtime)

        val result = service.pollOnce()

        assertEquals(IssueProcessResult.Dispatched("KAN-4", AgentRole.REVIEWER, "factory-KAN-4-reviewer"), result.issueResults[0])
        assertEquals(IssueProcessResult.Dispatched("KAN-5", AgentRole.TESTER, "factory-KAN-5-tester"), result.issueResults[1])
        assertEquals(IssueProcessResult.Skipped("KAN-6", "concurrency-cap"), result.issueResults[2])
        assertEquals(listOf(AgentRole.REVIEWER, AgentRole.TESTER), runtime.dispatches.map { it.role })
    }

    @Test
    fun `recovers active phase forward when DB already has a successful run`() {
        val jira = FakeJiraClient(listOf(issue("KAN-7", phase = "reviewing")))
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("KAN-7", "git@example/repo.git")
        val agentRuns = InMemoryAgentRunRepository().apply {
            addEnded(storyRun.id, AgentRole.REVIEWER, outcome = "review-finished", summary = "OK")
        }
        val service = service(jira, storyRuns = storyRuns, agentRuns = agentRuns)

        val result = service.pollOnce()

        assertEquals(listOf(IssueProcessResult.Recovered("KAN-7", "review-finished")), result.issueResults)
        assertEquals("review-finished", jira.lastUpdate("KAN-7").values[JiraKnownField.AI_PHASE])
    }

    @Test
    fun `retries transient failure by returning to the previous completed phase`() {
        val jira = FakeJiraClient(
            listOf(issue("KAN-8", phase = "testing", agentStartedAt = now.minusMinutes(5))),
        )
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("KAN-8", "git@example/repo.git")
        val agentRuns = InMemoryAgentRunRepository().apply {
            addEnded(storyRun.id, AgentRole.TESTER, outcome = "failed", summary = "timeout while waiting")
        }
        val service = service(jira, storyRuns = storyRuns, agentRuns = agentRuns)

        val result = service.pollOnce()

        assertEquals(listOf(IssueProcessResult.Recovered("KAN-8", "review-finished")), result.issueResults)
        assertEquals("review-finished", jira.lastUpdate("KAN-8").values[JiraKnownField.AI_PHASE])
    }

    @Test
    fun `writes Error for hard timeout and developer loopback cap`() {
        val jira = FakeJiraClient(
            listOf(
                issue("KAN-9", phase = "developing", agentStartedAt = now.minusMinutes(61)),
                issue("KAN-10", phase = "reviewed-with-feedback-for-developer"),
            ),
        )
        val storyRuns = InMemoryStoryRunRepository()
        val cappedRun = storyRuns.openOrCreate("KAN-10", "git@example/repo.git")
        val agentRuns = InMemoryAgentRunRepository().apply {
            repeat(6) { addEnded(cappedRun.id, AgentRole.DEVELOPER, outcome = "developed", summary = "done") }
        }
        val service = service(jira, storyRuns = storyRuns, agentRuns = agentRuns)

        val result = service.pollOnce()

        assertTrue(result.issueResults[0] is IssueProcessResult.Errored)
        assertTrue(jira.lastUpdate("KAN-9").values[JiraKnownField.ERROR].toString().contains("Hard timeout"))
        assertTrue(result.issueResults[1] is IssueProcessResult.Errored)
        assertTrue(jira.lastUpdate("KAN-10").values[JiraKnownField.ERROR].toString().contains("Developer-loopback cap"))
    }

    private fun service(
        jira: FakeJiraClient,
        runtime: FakeAgentRuntime = FakeAgentRuntime(now),
        storyRuns: InMemoryStoryRunRepository = InMemoryStoryRunRepository(),
        agentRuns: InMemoryAgentRunRepository = InMemoryAgentRunRepository(),
    ): OrchestratorService =
        OrchestratorService(
            jiraClient = jira,
            agentRuntime = runtime,
            storyRunRepository = storyRuns,
            agentRunRepository = agentRuns,
            settings = OrchestratorSettings(
                pollingEnabled = true,
                pollInterval = java.time.Duration.ofSeconds(15),
                maxParallelRefiner = 1,
                maxParallelDeveloper = 2,
                maxParallelReviewer = 2,
                maxParallelTester = 1,
                maxParallelTotal = 4,
                maxDeveloperLoopbacks = 5,
                maxTransientRetries = 2,
                hardTimeout = java.time.Duration.ofMinutes(60),
            ),
            clock = clock,
        )

    private fun issue(
        key: String,
        phase: String? = null,
        paused: Boolean = false,
        error: String? = null,
        targetRepo: String? = "git@example/repo.git",
        agentStartedAt: OffsetDateTime? = null,
    ): JiraIssue =
        JiraIssue(
            key = key,
            summary = "Story $key",
            status = "AI",
            fields = JiraIssueFields(
                targetRepo = targetRepo,
                aiPhase = phase,
                aiLevel = 5,
                aiTokenBudget = 100000,
                aiTokensUsed = 0,
                agentStartedAt = agentStartedAt,
                paused = paused,
                error = error,
            ),
            comments = emptyList(),
        )

    private class FakeJiraClient(
        private val issues: List<JiraIssue>,
    ) : JiraClient {
        val updates: MutableMap<String, MutableList<JiraFieldUpdate>> = mutableMapOf()

        override fun findAiIssues(projectKey: String, maxResults: Int): List<JiraIssue> =
            issues

        override fun getIssue(issueKey: String): JiraIssue =
            issues.first { it.key == issueKey }

        override fun updateIssueFields(issueKey: String, update: JiraFieldUpdate) {
            updates.getOrPut(issueKey) { mutableListOf() } += update
        }

        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): JiraComment =
            throw UnsupportedOperationException()

        override fun hasProcessedCommentMarker(commentId: String, role: AgentRole): Boolean =
            false

        override fun markCommentProcessed(commentId: String, role: AgentRole): Boolean =
            false

        fun lastUpdate(issueKey: String): JiraFieldUpdate =
            updates.getValue(issueKey).last()
    }

    private class FakeAgentRuntime(
        private val now: OffsetDateTime,
    ) : AgentRuntime {
        val dispatches: MutableList<AgentDispatchRequest> = mutableListOf()
        val runningByRole: MutableMap<AgentRole, Int> = mutableMapOf()

        override fun dispatch(request: AgentDispatchRequest): AgentDispatchResult {
            dispatches += request
            return AgentDispatchResult(
                containerName = "factory-${request.storyKey}-${request.role.markerKeyPart}",
                startedAt = now,
            )
        }

        override fun isAgentRunning(storyKey: String, role: AgentRole): Boolean =
            false

        override fun isAnyAgentRunningForStory(storyKey: String): Boolean =
            false

        override fun runningCount(role: AgentRole?): Int =
            if (role == null) runningByRole.values.sum() else runningByRole[role] ?: 0
    }

    private class InMemoryStoryRunRepository : StoryRunRepository {
        private val runs = mutableMapOf<String, StoryRunRecord>()
        private var nextId = 1L

        override fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord =
            runs.getOrPut(storyKey) { StoryRunRecord(nextId++, storyKey, targetRepo) }
    }

    private class InMemoryAgentRunRepository : AgentRunRepository {
        private val runs = mutableListOf<AgentRunRecord>()
        private var nextId = 1L

        override fun recordStarted(storyRunId: Long, role: AgentRole, containerName: String, level: Int?): Long {
            val id = nextId++
            runs += AgentRunRecord(id, storyRunId, role, OffsetDateTime.now(), null, null, null)
            return id
        }

        override fun complete(
            containerName: String,
            completion: AgentRunCompletionRecord,
            endedAt: OffsetDateTime,
        ): CompletedAgentRun? = null

        override fun addUsageToStoryRun(storyRunId: Long, completion: AgentRunCompletionRecord) = Unit

        override fun latestForRole(storyRunId: Long, role: AgentRole): AgentRunRecord? =
            recentForRole(storyRunId, role, limit = 1).firstOrNull()

        override fun recentForRole(storyRunId: Long, role: AgentRole, limit: Int): List<AgentRunRecord> =
            runs.filter { it.storyRunId == storyRunId && it.role == role }
                .sortedByDescending { it.id }
                .take(limit)

        override fun countForRole(storyRunId: Long, role: AgentRole): Int =
            runs.count { it.storyRunId == storyRunId && it.role == role }

        fun addEnded(storyRunId: Long, role: AgentRole, outcome: String, summary: String) {
            runs += AgentRunRecord(
                id = nextId++,
                storyRunId = storyRunId,
                role = role,
                startedAt = OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC),
                endedAt = OffsetDateTime.now(),
                outcome = outcome,
                summaryText = summary,
            )
        }
    }
}
