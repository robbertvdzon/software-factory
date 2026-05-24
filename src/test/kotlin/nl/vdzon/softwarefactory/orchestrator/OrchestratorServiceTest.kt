package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.github.PullRequestClient
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.github.PullRequestInfo
import nl.vdzon.softwarefactory.jira.AgentRole
import nl.vdzon.softwarefactory.jira.JiraClient
import nl.vdzon.softwarefactory.jira.JiraComment
import nl.vdzon.softwarefactory.jira.JiraFieldUpdate
import nl.vdzon.softwarefactory.jira.JiraIssue
import nl.vdzon.softwarefactory.jira.JiraIssueFields
import nl.vdzon.softwarefactory.jira.JiraKnownField
import nl.vdzon.softwarefactory.jira.ProcessedCommentService
import nl.vdzon.softwarefactory.jira.ProcessedCommentStore
import nl.vdzon.softwarefactory.preview.PreviewEnvironmentCleaner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        assertEquals(5, runtime.dispatches.single().aiLevel)
        assertEquals("dummy-ai-client", runtime.dispatches.single().aiModel)
        assertEquals("medium", runtime.dispatches.single().aiEffort)
        assertEquals(listOf("factory-KAN-3-refiner" to 1L), runtime.logCaptures)
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

    @Test
    fun `detects merged PR transitions Jira to Done and closes story run`() {
        val jira = FakeJiraClient(listOf(issue("KAN-11", phase = "tested-successfully")))
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("KAN-11", "git@github.com:robbertvdzon/sample-build-project.git")
        storyRuns.updatePullRequest(
            storyRun.id,
            "ai/KAN-11",
            123,
            "https://github.com/robbertvdzon/sample-build-project/pull/123",
            "main",
            "ai/",
            "https://sample-pr-{pr_num}.example.com",
            "sample-pr-{pr_num}",
            null,
        )
        val pullRequests = FakePullRequestClient(mergedPrs = setOf(123))
        val previewCleaner = FakePreviewEnvironmentCleaner()
        val service = service(jira, storyRuns = storyRuns, pullRequests = pullRequests, previewCleaner = previewCleaner)

        val result = service.pollOnce()

        assertEquals(IssueProcessResult.Skipped("KAN-11", "tested-successfully"), result.issueResults[0])
        assertEquals(IssueProcessResult.Merged("KAN-11", 123), result.issueResults[1])
        assertEquals(listOf("sample-pr-123"), previewCleaner.cleanedNamespaces)
        assertEquals("Done", jira.transitions.single().second)
        assertEquals("merged", storyRuns.closed.single().second)
    }

    @Test
    fun `PR factory comment is claimed and routes story back to developer feedback phase`() {
        val jira = FakeJiraClient(listOf(issue("KAN-12", phase = "tested-successfully")))
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("KAN-12", "git@github.com:robbertvdzon/sample-build-project.git")
        storyRuns.updatePullRequest(
            storyRun.id,
            "ai/KAN-12",
            124,
            "https://github.com/robbertvdzon/sample-build-project/pull/124",
            "main",
            "ai/",
            "https://sample-pr-{pr_num}.example.com",
            "sample-pr-{pr_num}",
            null,
        )
        val pullRequests = FakePullRequestClient(
            commentsByPr = mapOf(124 to listOf(PullRequestComment(9001, "@factory kun je deze tekst aanpassen?"))),
        )
        val service = service(jira, storyRuns = storyRuns, pullRequests = pullRequests)

        val result = service.pollOnce()

        assertEquals(IssueProcessResult.PrCommentTriggered("KAN-12", 124, 1), result.issueResults[1])
        assertEquals("tested-with-feedback-for-developer", jira.lastUpdate("KAN-12").values[JiraKnownField.AI_PHASE])
        assertEquals(9001, pullRequests.claimedComments.single())
    }

    @Test
    fun `claimed PR comments are passed to developer as comment mode task bundle`() {
        val jira = FakeJiraClient(listOf(issue("KAN-16", phase = "tested-with-feedback-for-developer")))
        val runtime = FakeAgentRuntime(now)
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("KAN-16", "git@github.com:robbertvdzon/sample-build-project.git")
        storyRuns.updatePullRequest(
            storyRun.id,
            "ai/KAN-16",
            126,
            "https://github.com/robbertvdzon/sample-build-project/pull/126",
            "main",
            "ai/",
            null,
            null,
            null,
        )
        val pullRequests = FakePullRequestClient(
            claimedCommentsByPr = mapOf(126 to listOf(PullRequestComment(9901, "@factory maak de knop duidelijker"))),
        )
        val service = service(jira, runtime = runtime, storyRuns = storyRuns, pullRequests = pullRequests)

        val result = service.pollOnce()

        assertEquals(IssueProcessResult.Dispatched("KAN-16", AgentRole.DEVELOPER, "factory-KAN-16-developer"), result.issueResults.single())
        val dispatch = runtime.dispatches.single()
        assertEquals("comment", dispatch.agentMode)
        assertTrue(dispatch.prCommentContext.orEmpty().contains("PR comment 9901"))
        assertTrue(dispatch.prCommentContext.orEmpty().contains("maak de knop duidelijker"))
    }

    @Test
    fun `dispatch task context includes Jira description and only relevant unprocessed comments`() {
        val issue = issue(
            "KAN-15",
            phase = null,
            description = "Als PO wil ik duidelijke context in task markdown.",
            comments = listOf(
                JiraComment("user-1", null, "Robbert", "Dit antwoord moet de refiner meenemen.", null),
                JiraComment("user-2", null, "Robbert", "Dit antwoord is al verwerkt.", null),
                JiraComment("review-1", null, "Reviewer", "[REVIEWER] niet relevant voor refiner.", null),
            ),
        )
        val jira = FakeJiraClient(listOf(issue))
        val runtime = FakeAgentRuntime(now)
        val processed = InMemoryProcessedCommentStore().apply {
            markProcessed("KAN-15", "user-2", AgentRole.REFINER)
        }
        val service = service(jira, runtime = runtime, processedCommentStore = processed)

        service.pollOnce()

        val context = runtime.dispatches.single().jiraContext.orEmpty()
        assertTrue(context.contains("Als PO wil ik duidelijke context"))
        assertTrue(context.contains("Dit antwoord moet de refiner meenemen."))
        assertFalse(context.contains("Dit antwoord is al verwerkt."))
        assertFalse(context.contains("niet relevant voor refiner"))
    }


    @Test
    fun `tester dispatch receives rendered preview context from PR metadata`() {
        val jira = FakeJiraClient(listOf(issue("KAN-13", phase = "review-finished")))
        val runtime = FakeAgentRuntime(now)
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("KAN-13", "git@github.com:robbertvdzon/sample-build-project.git")
        storyRuns.updatePullRequest(
            storyRun.id,
            "ai/KAN-13",
            77,
            "https://github.com/robbertvdzon/sample-build-project/pull/77",
            "main",
            "ai/",
            "https://sample-pr-{pr_num}.example.com",
            "sample-pr-{pr_num}",
            "printf db-url",
        )
        val service = service(jira, runtime = runtime, storyRuns = storyRuns)

        val result = service.pollOnce()

        assertEquals(IssueProcessResult.Dispatched("KAN-13", AgentRole.TESTER, "factory-KAN-13-tester"), result.issueResults.single())
        val dispatch = runtime.dispatches.single()
        assertEquals(77, dispatch.prNumber)
        assertEquals("main", dispatch.baseBranch)
        assertEquals("ai/", dispatch.branchPrefix)
        assertEquals("https://sample-pr-77.example.com", dispatch.previewUrl)
        assertEquals("sample-pr-77", dispatch.previewNamespace)
    }

    @Test
    fun `system credits pause prevents new dispatches`() {
        val jira = FakeJiraClient(listOf(issue("KAN-14", phase = null)))
        val runtime = FakeAgentRuntime(now)
        val credits = FakeCreditsPauseCoordinator().apply {
            pause = CreditsPause(now.plusMinutes(15), "credits exhausted")
        }
        val service = service(jira, runtime = runtime, creditsPauseCoordinator = credits)

        val result = service.pollOnce()

        assertEquals(listOf(IssueProcessResult.Skipped("KAN-14", "credits-paused")), result.issueResults)
        assertEquals(emptyList<AgentDispatchRequest>(), runtime.dispatches)
    }

    @Test
    fun `budget cap prevents dispatch`() {
        val jira = FakeJiraClient(listOf(issue("KAN-15", phase = null)))
        val runtime = FakeAgentRuntime(now)
        val costMonitor = FakeCostMonitor().apply { paused = true }
        val service = service(jira, runtime = runtime, costMonitor = costMonitor)

        val result = service.pollOnce()

        assertEquals(listOf(IssueProcessResult.Skipped("KAN-15", "budget-exceeded")), result.issueResults)
        assertEquals(emptyList<AgentDispatchRequest>(), runtime.dispatches)
    }

    private fun service(
        jira: FakeJiraClient,
        runtime: FakeAgentRuntime = FakeAgentRuntime(now),
        storyRuns: InMemoryStoryRunRepository = InMemoryStoryRunRepository(),
        agentRuns: InMemoryAgentRunRepository = InMemoryAgentRunRepository(),
        pullRequests: FakePullRequestClient = FakePullRequestClient(),
        processedCommentStore: InMemoryProcessedCommentStore = InMemoryProcessedCommentStore(),
        previewCleaner: FakePreviewEnvironmentCleaner = FakePreviewEnvironmentCleaner(),
        costMonitor: FakeCostMonitor = FakeCostMonitor(),
        creditsPauseCoordinator: FakeCreditsPauseCoordinator = FakeCreditsPauseCoordinator(),
        manualCommandProcessor: ManualCommandProcessor = NoopManualCommandProcessor(),
    ): OrchestratorService =
        OrchestratorService(
            jiraClient = jira,
            agentRuntime = runtime,
            storyRunRepository = storyRuns,
            agentRunRepository = agentRuns,
            pullRequestClient = pullRequests,
            processedCommentService = ProcessedCommentService(jira, processedCommentStore),
            previewEnvironmentCleaner = previewCleaner,
            costMonitor = costMonitor,
            creditsPauseCoordinator = creditsPauseCoordinator,
            manualCommandProcessor = manualCommandProcessor,
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
                costMonitorInterval = java.time.Duration.ofMinutes(5),
                creditsPauseDefault = java.time.Duration.ofMinutes(30),
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
        description: String? = "Beschrijving voor $key",
        comments: List<JiraComment> = emptyList(),
    ): JiraIssue =
        JiraIssue(
            key = key,
            summary = "Story $key",
            description = description,
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
            comments = comments,
        )

    private class FakeJiraClient(
        private val issues: List<JiraIssue>,
    ) : JiraClient {
        val updates: MutableMap<String, MutableList<JiraFieldUpdate>> = mutableMapOf()
        val transitions: MutableList<Pair<String, String>> = mutableListOf()

        override fun findAiIssues(projectKey: String, maxResults: Int): List<JiraIssue> =
            issues

        override fun getIssue(issueKey: String): JiraIssue =
            issues.first { it.key == issueKey }

        override fun updateIssueFields(issueKey: String, update: JiraFieldUpdate) {
            updates.getOrPut(issueKey) { mutableListOf() } += update
        }

        override fun transitionIssue(issueKey: String, statusName: String) {
            transitions += issueKey to statusName
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

    private class InMemoryProcessedCommentStore : ProcessedCommentStore {
        private val processed = mutableSetOf<Triple<String, String, AgentRole>>()

        override fun isProcessed(storyKey: String, commentId: String, role: AgentRole): Boolean =
            Triple(storyKey, commentId, role) in processed

        override fun markProcessed(storyKey: String, commentId: String, role: AgentRole) {
            processed += Triple(storyKey, commentId, role)
        }
    }

    private class FakeAgentRuntime(
        private val now: OffsetDateTime,
    ) : AgentRuntime {
        val dispatches: MutableList<AgentDispatchRequest> = mutableListOf()
        val logCaptures: MutableList<Pair<String, Long>> = mutableListOf()
        val runningByRole: MutableMap<AgentRole, Int> = mutableMapOf()

        override fun dispatch(request: AgentDispatchRequest): AgentDispatchResult {
            dispatches += request
            return AgentDispatchResult(
                containerName = "factory-${request.storyKey}-${request.role.markerKeyPart}",
                startedAt = now,
            )
        }

        override fun captureLogs(containerName: String, agentRunId: Long) {
            logCaptures += containerName to agentRunId
        }

        override fun isAgentRunning(storyKey: String, role: AgentRole): Boolean =
            false

        override fun isAnyAgentRunningForStory(storyKey: String): Boolean =
            false

        override fun runningCount(role: AgentRole?): Int =
            if (role == null) runningByRole.values.sum() else runningByRole[role] ?: 0

        override fun killForStory(storyKey: String): Int =
            0
    }

    private class InMemoryStoryRunRepository : StoryRunRepository {
        private val runs = mutableMapOf<String, StoryRunRecord>()
        private var nextId = 1L
        val closed = mutableListOf<Pair<Long, String>>()

        override fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord =
            runs.getOrPut(storyKey) { StoryRunRecord(nextId++, storyKey, targetRepo) }

        override fun get(storyRunId: Long): StoryRunRecord? =
            runs.values.firstOrNull { it.id == storyRunId }

        override fun updatePullRequest(
            storyRunId: Long,
            branchName: String,
            prNumber: Int,
            prUrl: String?,
            baseBranch: String?,
            branchPrefix: String?,
            previewUrlTemplate: String?,
            previewNamespaceTemplate: String?,
            previewDbSecretRecipe: String?,
        ) {
            val entry = runs.entries.first { it.value.id == storyRunId }
            entry.setValue(
                entry.value.copy(
                    branchName = branchName,
                    prNumber = prNumber,
                    prUrl = prUrl,
                    baseBranch = baseBranch,
                    branchPrefix = branchPrefix,
                    previewUrlTemplate = previewUrlTemplate,
                    previewNamespaceTemplate = previewNamespaceTemplate,
                    previewDbSecretRecipe = previewDbSecretRecipe,
                ),
            )
        }

        override fun activePullRequests(): List<StoryRunRecord> =
            runs.values.filter { it.prNumber != null }

        override fun activeRuns(): List<StoryRunRecord> =
            runs.values.toList()

        override fun close(storyRunId: Long, finalStatus: String, endedAt: OffsetDateTime) {
            closed += storyRunId to finalStatus
            val entry = runs.entries.first { it.value.id == storyRunId }
            runs.remove(entry.key)
        }
    }

    private class InMemoryAgentRunRepository : AgentRunRepository {
        private val runs = mutableListOf<AgentRunRecord>()
        private var nextId = 1L

        override fun recordStarted(
            storyRunId: Long,
            role: AgentRole,
            containerName: String,
            model: String?,
            effort: String?,
            level: Int?,
        ): Long {
            val id = nextId++
            runs += AgentRunRecord(
                id = id,
                storyRunId = storyRunId,
                role = role,
                startedAt = OffsetDateTime.now(),
                endedAt = null,
                outcome = null,
                summaryText = null,
                model = model,
                effort = effort,
                level = level,
            )
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

    private class FakePullRequestClient(
        private val mergedPrs: Set<Int> = emptySet(),
        private val commentsByPr: Map<Int, List<PullRequestComment>> = emptyMap(),
        private val claimedCommentsByPr: Map<Int, List<PullRequestComment>> = emptyMap(),
    ) : PullRequestClient {
        val claimedComments = mutableListOf<Long>()

        override fun ensurePullRequest(
            repoRoot: java.nio.file.Path,
            branchName: String,
            baseBranch: String,
            title: String,
            body: String,
        ): PullRequestInfo =
            PullRequestInfo(number = 1, url = "https://github.example/pr/1")

        override fun isMerged(targetRepo: String, prNumber: Int): Boolean =
            prNumber in mergedPrs

        override fun unprocessedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> =
            commentsByPr[prNumber].orEmpty()

        override fun claimedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> =
            claimedCommentsByPr[prNumber].orEmpty()

        override fun markCommentClaimed(targetRepo: String, commentId: Long) {
            claimedComments += commentId
        }

        override fun markCommentDone(targetRepo: String, commentId: Long) = Unit

        override fun markCommentFailed(targetRepo: String, commentId: Long) = Unit

        override fun closePullRequest(targetRepo: String, prNumber: Int) = Unit

        override fun deleteBranch(targetRepo: String, branchName: String) = Unit

        override fun mergePullRequest(targetRepo: String, prNumber: Int) = Unit
    }

    private class FakePreviewEnvironmentCleaner : PreviewEnvironmentCleaner {
        val cleanedNamespaces = mutableListOf<String>()

        override fun cleanup(namespace: String): Boolean {
            cleanedNamespaces += namespace
            return true
        }
    }

    private class FakeCostMonitor : CostMonitor {
        var paused = false

        override fun applyBudgetTriggers(issue: JiraIssue): JiraIssue =
            issue

        override fun checkBudget(issue: JiraIssue, storyRun: StoryRunRecord): CostMonitorCheckResult =
            CostMonitorCheckResult(storyRun.totalTokens, issue.fields.aiTokenBudget ?: 40000, paused, emptyList())

        override fun checkCompletedRun(storyKey: String, storyRun: StoryRunRecord) = Unit
    }

    private class FakeCreditsPauseCoordinator : CreditsPauseCoordinator {
        var pause: CreditsPause? = null
        val exhaustedStories = mutableListOf<String>()

        override fun activePause(now: OffsetDateTime): CreditsPause? =
            pause

        override fun handleCreditsExhausted(storyKey: String, summaryText: String?) {
            exhaustedStories += storyKey
        }
    }

    private class NoopManualCommandProcessor : ManualCommandProcessor {
        override fun apply(issue: JiraIssue): ManualCommandApplication =
            ManualCommandApplication(issue)
    }
}
