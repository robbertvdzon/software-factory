package nl.vdzon.softwarefactory.e2e

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.github.PullRequestClient
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.github.PullRequestInfo
import nl.vdzon.softwarefactory.jira.AgentRole
import nl.vdzon.softwarefactory.jira.JiraClient
import nl.vdzon.softwarefactory.jira.JiraComment
import nl.vdzon.softwarefactory.jira.JiraCommentParser
import nl.vdzon.softwarefactory.jira.JiraFieldUpdate
import nl.vdzon.softwarefactory.jira.JiraIssue
import nl.vdzon.softwarefactory.jira.JiraIssueFields
import nl.vdzon.softwarefactory.jira.JiraKnownField
import nl.vdzon.softwarefactory.jira.ProcessedCommentService
import nl.vdzon.softwarefactory.jira.ProcessedCommentStore
import nl.vdzon.softwarefactory.orchestrator.AgentDispatchRequest
import nl.vdzon.softwarefactory.orchestrator.AgentDispatchResult
import nl.vdzon.softwarefactory.orchestrator.AgentRunCompletionRecord
import nl.vdzon.softwarefactory.orchestrator.AgentRunRecord
import nl.vdzon.softwarefactory.orchestrator.AgentRunRepository
import nl.vdzon.softwarefactory.orchestrator.AgentRuntime
import nl.vdzon.softwarefactory.orchestrator.CompletedAgentRun
import nl.vdzon.softwarefactory.orchestrator.CostMonitorService
import nl.vdzon.softwarefactory.orchestrator.CreditsPause
import nl.vdzon.softwarefactory.orchestrator.CreditsPauseCoordinator
import nl.vdzon.softwarefactory.orchestrator.ManualCommandService
import nl.vdzon.softwarefactory.orchestrator.OrchestratorPollResult
import nl.vdzon.softwarefactory.orchestrator.OrchestratorService
import nl.vdzon.softwarefactory.orchestrator.OrchestratorSettings
import nl.vdzon.softwarefactory.orchestrator.StoryRunRecord
import nl.vdzon.softwarefactory.orchestrator.StoryRunRepository
import nl.vdzon.softwarefactory.preview.PreviewEnvironmentCleaner
import nl.vdzon.softwarefactory.runtime.AgentEventRepository
import nl.vdzon.softwarefactory.runtime.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.AgentRunCompletionService
import nl.vdzon.softwarefactory.runtime.AgentRunEventPayload
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class FactoryE2eHarness {
    private val now = OffsetDateTime.parse("2026-05-24T12:00:00Z")
    private val clock: Clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC)
    private val processedCommentStore = InMemoryProcessedCommentStore()

    val jira = FakeJiraAdapter()
    val github = FakeGitHubAdapter()
    val docker = FakeDockerAgentRuntime(now)
    val storyRuns = InMemoryStoryRunRepository()
    val agentRuns = InMemoryAgentRunRepository(storyRuns)
    val events = FakeAgentEventRepository()
    val previewCleaner = FakePreviewEnvironmentCleaner()
    val creditsPause = FakeCreditsPauseCoordinator()

    private val processedCommentService = ProcessedCommentService(jira, processedCommentStore)
    private val costMonitor = CostMonitorService(jira, storyRuns, processedCommentService)
    private val manualCommands = ManualCommandService(
        jiraClient = jira,
        processedCommentService = processedCommentService,
        agentRuntime = docker,
        storyRunRepository = storyRuns,
        pullRequestClient = github,
        previewEnvironmentCleaner = previewCleaner,
        clock = clock,
    )
    private val orchestrator = OrchestratorService(
        jiraClient = jira,
        agentRuntime = docker,
        storyRunRepository = storyRuns,
        agentRunRepository = agentRuns,
        pullRequestClient = github,
        previewEnvironmentCleaner = previewCleaner,
        costMonitor = costMonitor,
        creditsPauseCoordinator = creditsPause,
        manualCommandProcessor = manualCommands,
        settings = settings(),
        clock = clock,
    )
    private val completionService = AgentRunCompletionService(
        agentRunRepository = agentRuns,
        storyRunRepository = storyRuns,
        agentEventRepository = events,
        pullRequestClient = github,
        costMonitor = costMonitor,
        creditsPauseCoordinator = creditsPause,
        clock = clock,
        objectMapper = jacksonObjectMapper(),
    )

    fun addIssue(
        key: String = DEFAULT_STORY_KEY,
        targetRepo: String = DEFAULT_TARGET_REPO,
        budget: Long = 40_000,
    ) {
        jira.addIssue(
            JiraIssue(
                key = key,
                summary = "Story $key",
                status = "AI",
                fields = JiraIssueFields(
                    targetRepo = targetRepo,
                    aiPhase = null,
                    aiLevel = 5,
                    aiTokenBudget = budget,
                    aiTokensUsed = 0,
                    agentStartedAt = null,
                    paused = false,
                    error = null,
                ),
                comments = emptyList(),
            ),
        )
    }

    fun poll(): OrchestratorPollResult =
        orchestrator.pollOnce("KAN")

    fun completeRunning(role: AgentRole, outcome: ScriptedAgentOutcome, storyKey: String = DEFAULT_STORY_KEY) {
        val container = docker.runningContainers(storyKey, role).last()
        if (outcome.exitCode == 0 && outcome.phase != null) {
            jira.updateIssueFields(storyKey, JiraFieldUpdate.of(JiraKnownField.AI_PHASE to outcome.phase))
            jira.postAgentComment(storyKey, role, outcome.comment)
        } else {
            jira.updateIssueFields(
                storyKey,
                JiraFieldUpdate.of(JiraKnownField.ERROR to "${role.commentPrefix} ${outcome.comment}"),
            )
        }

        val events = mutableListOf<AgentRunEventPayload>()
        if (role == AgentRole.DEVELOPER && outcome.exitCode == 0) {
            val issue = jira.getIssue(storyKey)
            val branchName = "ai/$storyKey"
            val pr = github.openPullRequest(
                targetRepo = requireNotNull(issue.fields.targetRepo),
                branchName = branchName,
                baseBranch = "main",
                title = "$storyKey - Software Factory changes",
                body = "E2E dummy PR voor $storyKey.",
            )
            events += AgentRunEventPayload(
                "github-pr",
                """
                {
                  "branchName": "$branchName",
                  "baseBranch": "main",
                  "branchPrefix": "ai/",
                  "prNumber": ${pr.number},
                  "prUrl": "${pr.url}",
                  "previewUrlTemplate": "https://app-pr-{pr_num}.example.com",
                  "previewNamespaceTemplate": "app-pr-{pr_num}",
                  "previewDbSecretRecipe": "printf db-url"
                }
                """.trimIndent(),
            )
        }

        completionService.complete(
            AgentRunCompleteRequest(
                storyKey = storyKey,
                role = role.markerKeyPart,
                containerName = container.containerName,
                outcome = outcome.outcome,
                summaryText = outcome.comment,
                inputTokens = outcome.inputTokens,
                outputTokens = outcome.outputTokens,
                cacheReadInputTokens = outcome.cacheReadInputTokens,
                cacheCreationInputTokens = outcome.cacheCreationInputTokens,
                numTurns = 1,
                durationMs = 10,
                costUsdEst = 0.01,
                events = events,
            ),
        )
        docker.complete(container.containerName)
    }

    fun addUserComment(storyKey: String = DEFAULT_STORY_KEY, body: String) {
        jira.addUserComment(storyKey, body)
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

    companion object {
        const val DEFAULT_STORY_KEY = "KAN-100"
        const val DEFAULT_TARGET_REPO = "git@github.com:robbertvdzon/sample-build-project.git"
    }
}

data class ScriptedAgentOutcome(
    val phase: String?,
    val comment: String,
    val outcome: String = "ok",
    val exitCode: Int = 0,
    val inputTokens: Int = 1000,
    val outputTokens: Int = 500,
    val cacheReadInputTokens: Int = 0,
    val cacheCreationInputTokens: Int = 0,
)

object ScriptedOutcomes {
    fun refinerOk(inputTokens: Int = 1000, outputTokens: Int = 500): ScriptedAgentOutcome =
        ScriptedAgentOutcome("refined-finished", "(dummy) refinement OK", inputTokens = inputTokens, outputTokens = outputTokens)

    fun developerOk(): ScriptedAgentOutcome =
        ScriptedAgentOutcome("developed", "(dummy) placeholder-wijziging gepushed")

    fun reviewerOk(): ScriptedAgentOutcome =
        ScriptedAgentOutcome("review-finished", "(dummy) review OK")

    fun reviewerFeedback(): ScriptedAgentOutcome =
        ScriptedAgentOutcome("reviewed-with-feedback-for-developer", "(dummy) feedback: controleer edge cases.", "feedback")

    fun testerOk(): ScriptedAgentOutcome =
        ScriptedAgentOutcome("tested-successfully", "(dummy) tests OK")

    fun testerBug(): ScriptedAgentOutcome =
        ScriptedAgentOutcome("tested-with-feedback-for-developer", "(dummy) bug: happy path faalt.", "bug")
}

class FakeJiraAdapter : JiraClient {
    private val issues = linkedMapOf<String, JiraIssue>()
    private val processedMarkers = mutableSetOf<Pair<String, AgentRole>>()
    private var nextCommentSequence = 1

    val fieldUpdates = mutableListOf<Pair<String, JiraFieldUpdate>>()
    val transitions = mutableListOf<Pair<String, String>>()

    fun addIssue(issue: JiraIssue) {
        issues[issue.key] = issue
    }

    fun addUserComment(storyKey: String, body: String): JiraComment =
        appendComment(storyKey, JiraComment(nextCommentId().toString(), "user", "User", body, OffsetDateTime.now()))

    override fun findAiIssues(projectKey: String, maxResults: Int): List<JiraIssue> =
        issues.values
            .filter { it.key.startsWith("$projectKey-") && it.status == "AI" }
            .sortedBy { it.key }
            .take(maxResults)

    override fun getIssue(issueKey: String): JiraIssue =
        requireNotNull(issues[issueKey]) { "Unknown fake Jira issue: $issueKey" }

    override fun updateIssueFields(issueKey: String, update: JiraFieldUpdate) {
        fieldUpdates += issueKey to update
        val issue = getIssue(issueKey)
        var fields = issue.fields
        update.values.forEach { (field, value) ->
            fields = when (field) {
                JiraKnownField.TARGET_REPO -> fields.copy(targetRepo = value as String?)
                JiraKnownField.AI_PHASE -> fields.copy(aiPhase = value as String?)
                JiraKnownField.AI_LEVEL -> fields.copy(aiLevel = value as Int?)
                JiraKnownField.AI_TOKEN_BUDGET -> fields.copy(aiTokenBudget = value as Long?)
                JiraKnownField.AI_TOKENS_USED -> fields.copy(aiTokensUsed = (value as Number?)?.toLong())
                JiraKnownField.AGENT_STARTED_AT -> fields.copy(agentStartedAt = value as OffsetDateTime?)
                JiraKnownField.PAUSED -> fields.copy(paused = value as Boolean)
                JiraKnownField.ERROR -> fields.copy(error = value as String?)
            }
        }
        issues[issueKey] = issue.copy(fields = fields)
    }

    override fun updateIssueSummary(issueKey: String, summary: String) {
        val issue = getIssue(issueKey)
        issues[issueKey] = issue.copy(summary = summary)
    }

    override fun transitionIssue(issueKey: String, statusName: String) {
        transitions += issueKey to statusName
        val issue = getIssue(issueKey)
        issues[issueKey] = issue.copy(status = statusName)
    }

    override fun postAgentComment(issueKey: String, role: AgentRole, message: String): JiraComment =
        appendComment(
            issueKey,
            JiraComment(
                id = nextCommentId().toString(),
                authorAccountId = "factory",
                authorDisplayName = "Software Factory",
                body = "${role.commentPrefix} $message",
                created = OffsetDateTime.now(),
            ),
        )

    override fun hasProcessedCommentMarker(commentId: String, role: AgentRole): Boolean =
        commentId to role in processedMarkers

    override fun markCommentProcessed(commentId: String, role: AgentRole): Boolean {
        processedMarkers += commentId to role
        return true
    }

    override fun deleteAgentComments(issueKey: String): Int {
        val issue = getIssue(issueKey)
        val remaining = issue.comments.filterNot { it.isAgentComment }
        issues[issueKey] = issue.copy(comments = remaining)
        return issue.comments.size - remaining.size
    }

    private fun appendComment(issueKey: String, comment: JiraComment): JiraComment {
        val issue = getIssue(issueKey)
        issues[issueKey] = issue.copy(comments = issue.comments + comment)
        return comment
    }

    private fun nextCommentId(): Int =
        nextCommentSequence++
}

class FakeGitHubAdapter : PullRequestClient {
    private val pullRequests = linkedMapOf<Int, FakePullRequest>()
    private val claimedCommentIds = mutableSetOf<Long>()
    private val doneCommentIds = mutableSetOf<Long>()
    private val failedCommentIds = mutableSetOf<Long>()
    private var nextPrNumber = 1

    val deletedBranches = mutableListOf<Pair<String, String>>()

    fun openPullRequest(
        targetRepo: String,
        branchName: String,
        baseBranch: String,
        title: String,
        body: String,
    ): PullRequestInfo {
        pullRequests.values
            .firstOrNull { it.targetRepo == targetRepo && it.branchName == branchName && it.state == "OPEN" }
            ?.let { return it.info() }

        val number = nextPrNumber++
        val pr = FakePullRequest(
            number = number,
            targetRepo = targetRepo,
            branchName = branchName,
            baseBranch = baseBranch,
            title = title,
            body = body,
            url = "https://github.example/pr/$number",
        )
        pullRequests[number] = pr
        return pr.info()
    }

    fun addComment(prNumber: Int, body: String): PullRequestComment {
        val pr = requireNotNull(pullRequests[prNumber]) { "Unknown fake PR: $prNumber" }
        val comment = PullRequestComment((pr.comments.size + 1).toLong(), body)
        pr.comments += comment
        return comment
    }

    fun state(prNumber: Int): String? =
        pullRequests[prNumber]?.state

    override fun ensurePullRequest(repoRoot: Path, branchName: String, baseBranch: String, title: String, body: String): PullRequestInfo =
        openPullRequest(repoRoot.toString(), branchName, baseBranch, title, body)

    override fun isMerged(targetRepo: String, prNumber: Int): Boolean =
        pullRequests[prNumber]?.state == "MERGED"

    override fun unprocessedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> =
        pullRequests[prNumber]?.comments.orEmpty()
            .filter { it.body.contains("@factory", ignoreCase = true) }
            .filterNot { JiraCommentParser.isAgentComment(it.body) }
            .filterNot { it.id in claimedCommentIds }
            .filterNot { it.id in doneCommentIds }
            .filterNot { it.id in failedCommentIds }

    override fun claimedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> =
        pullRequests[prNumber]?.comments.orEmpty()
            .filter { it.id in claimedCommentIds }
            .filterNot { it.id in doneCommentIds }
            .filterNot { it.id in failedCommentIds }

    override fun markCommentClaimed(targetRepo: String, commentId: Long) {
        claimedCommentIds += commentId
    }

    override fun markCommentDone(targetRepo: String, commentId: Long) {
        doneCommentIds += commentId
    }

    override fun markCommentFailed(targetRepo: String, commentId: Long) {
        failedCommentIds += commentId
    }

    override fun closePullRequest(targetRepo: String, prNumber: Int) {
        pullRequests[prNumber]?.state = "CLOSED"
    }

    override fun deleteBranch(targetRepo: String, branchName: String) {
        deletedBranches += targetRepo to branchName
    }

    override fun mergePullRequest(targetRepo: String, prNumber: Int) {
        pullRequests[prNumber]?.state = "MERGED"
    }

    private data class FakePullRequest(
        val number: Int,
        val targetRepo: String,
        val branchName: String,
        val baseBranch: String,
        val title: String,
        val body: String,
        val url: String,
        val comments: MutableList<PullRequestComment> = mutableListOf(),
        var state: String = "OPEN",
    ) {
        fun info(): PullRequestInfo =
            PullRequestInfo(number = number, url = url, state = state)
    }
}

class FakeDockerAgentRuntime(
    private val now: OffsetDateTime,
) : AgentRuntime {
    private val containers = linkedMapOf<String, FakeContainer>()
    private var nextContainerId = 1

    val dispatches = mutableListOf<AgentDispatchRequest>()

    override fun dispatch(request: AgentDispatchRequest): AgentDispatchResult {
        val containerName = "factory-${request.storyKey.lowercase()}-${request.role.markerKeyPart}-${nextContainerId++}"
        dispatches += request
        containers[containerName] = FakeContainer(containerName, request.storyKey, request.role)
        return AgentDispatchResult(containerName, now)
    }

    override fun isAgentRunning(storyKey: String, role: AgentRole): Boolean =
        runningContainers(storyKey, role).isNotEmpty()

    override fun isAnyAgentRunningForStory(storyKey: String): Boolean =
        containers.values.any { it.storyKey == storyKey && it.running }

    override fun runningCount(role: AgentRole?): Int =
        containers.values.count { it.running && (role == null || it.role == role) }

    override fun killForStory(storyKey: String): Int {
        val running = containers.values.filter { it.storyKey == storyKey && it.running }
        running.forEach {
            it.running = false
            it.killed = true
        }
        return running.size
    }

    fun runningContainers(storyKey: String, role: AgentRole): List<FakeContainer> =
        containers.values.filter { it.storyKey == storyKey && it.role == role && it.running }

    fun complete(containerName: String) {
        requireNotNull(containers[containerName]) { "Unknown fake container: $containerName" }.running = false
    }

    data class FakeContainer(
        val containerName: String,
        val storyKey: String,
        val role: AgentRole,
        var running: Boolean = true,
        var killed: Boolean = false,
    )
}

class InMemoryStoryRunRepository : StoryRunRepository {
    private val runs = linkedMapOf<Long, StoryRunRecord>()
    private val closedRunIds = mutableSetOf<Long>()
    private var nextId = 1L

    val closed = mutableListOf<Pair<Long, String>>()

    override fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord {
        runs.values.firstOrNull { it.storyKey == storyKey && it.id !in closedRunIds }?.let { return it }
        val run = StoryRunRecord(nextId++, storyKey, targetRepo)
        runs[run.id] = run
        return run
    }

    override fun get(storyRunId: Long): StoryRunRecord? =
        runs[storyRunId]

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
        val run = requireNotNull(runs[storyRunId]) { "Unknown fake story run: $storyRunId" }
        runs[storyRunId] = run.copy(
            branchName = branchName,
            prNumber = prNumber,
            prUrl = prUrl,
            baseBranch = baseBranch,
            branchPrefix = branchPrefix,
            previewUrlTemplate = previewUrlTemplate,
            previewNamespaceTemplate = previewNamespaceTemplate,
            previewDbSecretRecipe = previewDbSecretRecipe,
        )
    }

    override fun activePullRequests(): List<StoryRunRecord> =
        activeRuns().filter { it.prNumber != null }

    override fun activeRuns(): List<StoryRunRecord> =
        runs.values.filterNot { it.id in closedRunIds }

    override fun close(storyRunId: Long, finalStatus: String, endedAt: OffsetDateTime) {
        closedRunIds += storyRunId
        closed += storyRunId to finalStatus
    }

    fun addUsage(storyRunId: Long, completion: AgentRunCompletionRecord) {
        val run = requireNotNull(runs[storyRunId]) { "Unknown fake story run: $storyRunId" }
        runs[storyRunId] = run.copy(
            totalInputTokens = run.totalInputTokens + completion.inputTokens,
            totalOutputTokens = run.totalOutputTokens + completion.outputTokens,
            totalCacheReadTokens = run.totalCacheReadTokens + completion.cacheReadInputTokens,
            totalCacheCreationTokens = run.totalCacheCreationTokens + completion.cacheCreationInputTokens,
            totalCostUsdEst = run.totalCostUsdEst + completion.costUsdEst,
        )
    }
}

class InMemoryAgentRunRepository(
    private val storyRuns: InMemoryStoryRunRepository,
) : AgentRunRepository {
    private val runs = linkedMapOf<Long, StoredAgentRun>()
    private var nextId = 1L

    override fun recordStarted(storyRunId: Long, role: AgentRole, containerName: String, level: Int?): Long {
        val id = nextId++
        runs[id] = StoredAgentRun(
            id = id,
            storyRunId = storyRunId,
            role = role,
            containerName = containerName,
            startedAt = OffsetDateTime.ofInstant(Instant.EPOCH.plusSeconds(id), ZoneOffset.UTC),
        )
        return id
    }

    override fun complete(containerName: String, completion: AgentRunCompletionRecord, endedAt: OffsetDateTime): CompletedAgentRun? {
        val run = runs.values.firstOrNull { it.containerName == containerName && it.endedAt == null } ?: return null
        run.endedAt = endedAt
        run.outcome = completion.outcome
        run.summaryText = completion.summaryText
        return CompletedAgentRun(run.id, run.storyRunId)
    }

    override fun addUsageToStoryRun(storyRunId: Long, completion: AgentRunCompletionRecord) {
        storyRuns.addUsage(storyRunId, completion)
    }

    override fun latestForRole(storyRunId: Long, role: AgentRole): AgentRunRecord? =
        recentForRole(storyRunId, role, 1).firstOrNull()

    override fun recentForRole(storyRunId: Long, role: AgentRole, limit: Int): List<AgentRunRecord> =
        runs.values
            .filter { it.storyRunId == storyRunId && it.role == role }
            .sortedByDescending { it.id }
            .take(limit)
            .map { it.record() }

    override fun countForRole(storyRunId: Long, role: AgentRole): Int =
        runs.values.count { it.storyRunId == storyRunId && it.role == role }

    private data class StoredAgentRun(
        val id: Long,
        val storyRunId: Long,
        val role: AgentRole,
        val containerName: String,
        val startedAt: OffsetDateTime,
        var endedAt: OffsetDateTime? = null,
        var outcome: String? = null,
        var summaryText: String? = null,
    ) {
        fun record(): AgentRunRecord =
            AgentRunRecord(id, storyRunId, role, startedAt, endedAt, outcome, summaryText)
    }
}

class FakeAgentEventRepository : AgentEventRepository {
    val appended = mutableListOf<Pair<String, Map<String, Any?>>>()

    override fun append(agentRunId: Long, kind: String, payload: Map<String, Any?>) {
        appended += kind to payload
    }
}

class InMemoryProcessedCommentStore : ProcessedCommentStore {
    private val processed = mutableSetOf<Triple<String, String, AgentRole>>()

    override fun isProcessed(storyKey: String, commentId: String, role: AgentRole): Boolean =
        Triple(storyKey, commentId, role) in processed

    override fun markProcessed(storyKey: String, commentId: String, role: AgentRole) {
        processed += Triple(storyKey, commentId, role)
    }
}

class FakePreviewEnvironmentCleaner : PreviewEnvironmentCleaner {
    val cleanedNamespaces = mutableListOf<String>()

    override fun cleanup(namespace: String): Boolean {
        cleanedNamespaces += namespace
        return true
    }
}

class FakeCreditsPauseCoordinator : CreditsPauseCoordinator {
    var activePause: CreditsPause? = null
    val exhaustedStories = mutableListOf<String>()

    override fun activePause(now: OffsetDateTime): CreditsPause? =
        activePause

    override fun handleCreditsExhausted(storyKey: String, summaryText: String?) {
        exhaustedStories += storyKey
    }
}
