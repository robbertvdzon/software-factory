package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.runtime.commands.*
import nl.vdzon.softwarefactory.runtime.docker.*
import nl.vdzon.softwarefactory.runtime.logging.*
import nl.vdzon.softwarefactory.runtime.repositories.*
import nl.vdzon.softwarefactory.runtime.workspaces.*

import nl.vdzon.softwarefactory.runtime.repositories.AgentEventRepository
import nl.vdzon.softwarefactory.runtime.workspaces.AgentWorkspaceCleaner
import nl.vdzon.softwarefactory.youtrack.AgentRole
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.youtrack.TrackerComment
import nl.vdzon.softwarefactory.youtrack.TrackerFieldUpdate
import nl.vdzon.softwarefactory.youtrack.TrackerIssue
import nl.vdzon.softwarefactory.youtrack.TrackerIssueFields
import nl.vdzon.softwarefactory.youtrack.services.ProcessedCommentService
import nl.vdzon.softwarefactory.youtrack.repositories.ProcessedCommentStore
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.github.PullRequestInfo
import nl.vdzon.softwarefactory.orchestrator.repositories.AgentRunCompletionRecord
import nl.vdzon.softwarefactory.orchestrator.repositories.AgentRunRecord
import nl.vdzon.softwarefactory.orchestrator.repositories.AgentRunRepository
import nl.vdzon.softwarefactory.orchestrator.repositories.CompletedAgentRun
import nl.vdzon.softwarefactory.orchestrator.services.CostMonitor
import nl.vdzon.softwarefactory.orchestrator.services.CostMonitorCheckResult
import nl.vdzon.softwarefactory.orchestrator.services.CreditsPause
import nl.vdzon.softwarefactory.orchestrator.services.CreditsPauseCoordinator
import nl.vdzon.softwarefactory.orchestrator.repositories.StoryRunRecord
import nl.vdzon.softwarefactory.orchestrator.repositories.StoryRunRepository
import nl.vdzon.softwarefactory.runtime.services.AgentRunCompletionService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

class AgentRunCompletionServiceTest {
    @Test
    fun `completion stores usage totals and redacted events`() {
        val runs = FakeAgentRunRepository()
        val storyRuns = FakeStoryRunRepository()
        val events = FakeAgentEventRepository()
        val costMonitor = FakeCostMonitor()
        val creditsPause = FakeCreditsPauseCoordinator()
        val issueTracker = FakeYouTrackApi()
        val workspaceCleaner = FakeAgentWorkspaceCleaner()
        val service = AgentRunCompletionService(
            agentRunRepository = runs,
            storyRunRepository = storyRuns,
            agentEventRepository = events,
            issueTrackerClient = issueTracker,
            processedCommentService = ProcessedCommentService(issueTracker, InMemoryProcessedCommentStore()),
            pullRequestClient = FakeGitHubApi(),
            agentWorkspaceCleaner = workspaceCleaner,
            costMonitor = costMonitor,
            creditsPauseCoordinator = creditsPause,
            clock = Clock.fixed(java.time.Instant.parse("2026-05-23T20:00:00Z"), ZoneOffset.UTC),
            objectMapper = jacksonObjectMapper(),
        )

        val response = service.complete(
            AgentRunCompleteRequest(
                storyKey = "KAN-69",
                role = "developer",
                containerName = "factory-kan-69-developer",
                outcome = "ok",
                summaryText = "done",
                inputTokens = 1000,
                outputTokens = 500,
                cacheReadInputTokens = 10,
                cacheCreationInputTokens = 20,
                numTurns = 2,
                durationMs = 1234,
                costUsdEst = 0.42,
                events = listOf(
                    AgentRunEventPayload("log", "SF_GITHUB_TOKEN=secret postgresql://user:pass@host/db"),
                    AgentRunEventPayload(
                        "github-pr",
                        """{"branchName":"ai/KAN-69","baseBranch":"main","branchPrefix":"ai/","prNumber":42,"prUrl":"https://github.example/pr/42","previewUrlTemplate":"https://app-pr-{pr_num}.example.com","previewNamespaceTemplate":"app-pr-{pr_num}","previewDbSecretRecipe":"printf db-url"}""",
                    ),
                ),
            ),
        )

        assertEquals(200, response.statusCode.value())
        assertEquals(1L, response.body?.agentRunId)
        assertEquals(7L, response.body?.storyRunId)
        assertEquals("ok", runs.completed.single().outcome)
        assertEquals(1000, runs.usageAdded.single().inputTokens)
        assertEquals("KAN-69", costMonitor.checkedStories.single())
        assertEquals(emptyList<String>(), creditsPause.exhaustedStories)
        assertEquals(PullRequestUpdate(7L, "ai/KAN-69", 42, "main", "ai/", "https://app-pr-{pr_num}.example.com", "app-pr-{pr_num}", "printf db-url"), storyRuns.pullRequests.single())
        assertTrue(events.payloads.first()["payload"].toString().contains("SF_GITHUB_TOKEN=<redacted>"))
        assertTrue(events.payloads.first()["payload"].toString().contains("postgresql://<redacted>"))
        assertEquals(listOf("/tmp/software-factory-test-workspace" to false), workspaceCleaner.cleaned)
    }

    @Test
    fun `credits exhausted completion activates system pause coordinator`() {
        val runs = FakeAgentRunRepository()
        val storyRuns = FakeStoryRunRepository()
        val events = FakeAgentEventRepository()
        val creditsPause = FakeCreditsPauseCoordinator()
        val issueTracker = FakeYouTrackApi()
        val service = AgentRunCompletionService(
            agentRunRepository = runs,
            storyRunRepository = storyRuns,
            agentEventRepository = events,
            issueTrackerClient = issueTracker,
            processedCommentService = ProcessedCommentService(issueTracker, InMemoryProcessedCommentStore()),
            pullRequestClient = FakeGitHubApi(),
            agentWorkspaceCleaner = FakeAgentWorkspaceCleaner(),
            costMonitor = FakeCostMonitor(),
            creditsPauseCoordinator = creditsPause,
            clock = Clock.fixed(java.time.Instant.parse("2026-05-23T20:00:00Z"), ZoneOffset.UTC),
            objectMapper = jacksonObjectMapper(),
        )

        service.complete(
            AgentRunCompleteRequest(
                storyKey = "KAN-69",
                role = "developer",
                containerName = "factory-kan-69-developer",
                outcome = "credits-exhausted",
                summaryText = "HTTP 429 credit exhausted",
            ),
        )

        assertEquals(listOf("KAN-69"), creditsPause.exhaustedStories)
    }

    @Test
    fun `developer completion marks claimed PR comments done or failed`() {
        val runs = FakeAgentRunRepository()
        val storyRuns = FakeStoryRunRepository()
        val pullRequests = FakeGitHubApi(
            claimedComments = listOf(PullRequestComment(10, "@factory pas dit aan")),
        )
        val issueTracker = FakeYouTrackApi()
        val service = AgentRunCompletionService(
            agentRunRepository = runs,
            storyRunRepository = storyRuns,
            agentEventRepository = FakeAgentEventRepository(),
            issueTrackerClient = issueTracker,
            processedCommentService = ProcessedCommentService(issueTracker, InMemoryProcessedCommentStore()),
            pullRequestClient = pullRequests,
            agentWorkspaceCleaner = FakeAgentWorkspaceCleaner(),
            costMonitor = FakeCostMonitor(),
            creditsPauseCoordinator = FakeCreditsPauseCoordinator(),
            clock = Clock.fixed(java.time.Instant.parse("2026-05-23T20:00:00Z"), ZoneOffset.UTC),
            objectMapper = jacksonObjectMapper(),
        )

        service.complete(
            AgentRunCompleteRequest(
                storyKey = "KAN-69",
                role = "developer",
                containerName = "factory-kan-69-developer",
                outcome = "ok",
            ),
        )

        assertEquals(listOf(10L), pullRequests.doneComments)
    }

    @Test
    fun `successful refiner and developer completions mark processed Issue comments`() {
        val issueTracker = FakeYouTrackApi(
            issue = issue(
                comments = listOf(
                    TrackerComment("user-1", null, "Robbert", "Hier is het antwoord op je vraag.", null),
                    TrackerComment("review-1", null, "Reviewer", "[REVIEWER] edge case ontbreekt.", null),
                    TrackerComment("test-1", null, "Tester", "[TESTER] bug in happy path.", null),
                    TrackerComment("refiner-1", null, "Refiner", "[REFINER] refined story.", null),
                ),
            ),
        )
        val processed = ProcessedCommentService(issueTracker, InMemoryProcessedCommentStore())
        val service = AgentRunCompletionService(
            agentRunRepository = FakeAgentRunRepository(),
            storyRunRepository = FakeStoryRunRepository(),
            agentEventRepository = FakeAgentEventRepository(),
            issueTrackerClient = issueTracker,
            processedCommentService = processed,
            pullRequestClient = FakeGitHubApi(),
            agentWorkspaceCleaner = FakeAgentWorkspaceCleaner(),
            costMonitor = FakeCostMonitor(),
            creditsPauseCoordinator = FakeCreditsPauseCoordinator(),
            clock = Clock.fixed(java.time.Instant.parse("2026-05-23T20:00:00Z"), ZoneOffset.UTC),
            objectMapper = jacksonObjectMapper(),
        )

        service.complete(
            AgentRunCompleteRequest(
                storyKey = "KAN-69",
                role = "refiner",
                containerName = "factory-kan-69-refiner",
                outcome = "ok",
            ),
        )
        service.complete(
            AgentRunCompleteRequest(
                storyKey = "KAN-69",
                role = "developer",
                containerName = "factory-kan-69-developer",
                outcome = "ok",
            ),
        )

        assertEquals(
            listOf(
                "user-1" to AgentRole.REFINER,
                "review-1" to AgentRole.DEVELOPER,
                "test-1" to AgentRole.DEVELOPER,
            ),
            issueTracker.markedComments,
        )
    }

    private class FakeStoryRunRepository : StoryRunRepository {
        val pullRequests = mutableListOf<PullRequestUpdate>()

        override fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord =
            StoryRunRecord(7, storyKey, targetRepo)

        override fun get(storyRunId: Long): StoryRunRecord? =
            StoryRunRecord(
                id = storyRunId,
                storyKey = "KAN-69",
                targetRepo = "git@github.com:robbertvdzon/sample-build-project.git",
                prNumber = 42,
                totalInputTokens = 1000,
                totalOutputTokens = 500,
            )

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
            pullRequests += PullRequestUpdate(
                storyRunId,
                branchName,
                prNumber,
                baseBranch,
                branchPrefix,
                previewUrlTemplate,
                previewNamespaceTemplate,
                previewDbSecretRecipe,
            )
        }

        override fun activePullRequests(): List<StoryRunRecord> = emptyList()

        override fun activeRuns(): List<StoryRunRecord> = emptyList()

        override fun close(storyRunId: Long, finalStatus: String, endedAt: OffsetDateTime) = Unit
    }

    private data class PullRequestUpdate(
        val storyRunId: Long,
        val branchName: String,
        val prNumber: Int,
        val baseBranch: String?,
        val branchPrefix: String?,
        val previewUrlTemplate: String?,
        val previewNamespaceTemplate: String?,
        val previewDbSecretRecipe: String?,
    )

    private class FakeAgentRunRepository : AgentRunRepository {
        val completed = mutableListOf<AgentRunCompletionRecord>()
        val usageAdded = mutableListOf<AgentRunCompletionRecord>()

        override fun recordStarted(
            storyRunId: Long,
            role: AgentRole,
            containerName: String,
            model: String?,
            effort: String?,
            level: Int?,
            workspacePath: String?,
        ): Long = 1

        override fun complete(
            containerName: String,
            completion: AgentRunCompletionRecord,
            endedAt: OffsetDateTime,
        ): CompletedAgentRun? {
            completed += completion
            return CompletedAgentRun(agentRunId = 1, storyRunId = 7, workspacePath = "/tmp/software-factory-test-workspace")
        }

        override fun addUsageToStoryRun(storyRunId: Long, completion: AgentRunCompletionRecord) {
            usageAdded += completion
        }

        override fun latestForRole(storyRunId: Long, role: AgentRole): AgentRunRecord? = null

        override fun recentForRole(storyRunId: Long, role: AgentRole, limit: Int): List<AgentRunRecord> = emptyList()

        override fun countForRole(storyRunId: Long, role: AgentRole): Int = 0
    }

    private class FakeAgentEventRepository : AgentEventRepository {
        val payloads = mutableListOf<Map<String, Any?>>()

        override fun append(agentRunId: Long, kind: String, payload: Map<String, Any?>) {
            payloads += payload
        }
    }

    private class FakeAgentWorkspaceCleaner : AgentWorkspaceCleaner {
        val cleaned = mutableListOf<Pair<String?, Boolean>>()

        override fun cleanup(workspacePath: String?, failed: Boolean): Boolean {
            cleaned += workspacePath to failed
            return true
        }
    }

    private class FakeCostMonitor : CostMonitor {
        val checkedStories = mutableListOf<String>()

        override fun applyBudgetTriggers(issue: nl.vdzon.softwarefactory.youtrack.TrackerIssue): nl.vdzon.softwarefactory.youtrack.TrackerIssue =
            issue

        override fun checkBudget(
            issue: nl.vdzon.softwarefactory.youtrack.TrackerIssue,
            storyRun: StoryRunRecord,
        ): CostMonitorCheckResult =
            CostMonitorCheckResult(storyRun.totalTokens, issue.fields.aiTokenBudget ?: 40000, false, emptyList())

        override fun checkCompletedRun(storyKey: String, storyRun: StoryRunRecord) {
            checkedStories += storyKey
        }
    }

    private class FakeCreditsPauseCoordinator : CreditsPauseCoordinator {
        val exhaustedStories = mutableListOf<String>()

        override fun activePause(now: OffsetDateTime): CreditsPause? = null

        override fun handleCreditsExhausted(storyKey: String, summaryText: String?) {
            exhaustedStories += storyKey
        }
    }

    private class FakeGitHubApi(
        private val claimedComments: List<PullRequestComment> = emptyList(),
    ) : GitHubApi {
        val doneComments = mutableListOf<Long>()
        val failedComments = mutableListOf<Long>()

        override fun ensurePullRequest(
            repoRoot: java.nio.file.Path,
            branchName: String,
            baseBranch: String,
            title: String,
            body: String,
        ): PullRequestInfo =
            PullRequestInfo(42, "https://github.example/pr/42")

        override fun isMerged(targetRepo: String, prNumber: Int): Boolean = false

        override fun unprocessedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> = emptyList()

        override fun claimedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> =
            claimedComments

        override fun markCommentClaimed(targetRepo: String, commentId: Long) = Unit

        override fun markCommentDone(targetRepo: String, commentId: Long) {
            doneComments += commentId
        }

        override fun markCommentFailed(targetRepo: String, commentId: Long) {
            failedComments += commentId
        }

        override fun closePullRequest(targetRepo: String, prNumber: Int) = Unit

        override fun deleteBranch(targetRepo: String, branchName: String) = Unit

        override fun mergePullRequest(targetRepo: String, prNumber: Int) = Unit
    }

    private class FakeYouTrackApi(
        private val issue: TrackerIssue = issue(),
    ) : YouTrackApi {
        val markedComments = mutableListOf<Pair<String, AgentRole>>()

        override fun findAiIssues(projectKey: String, maxResults: Int): List<TrackerIssue> = listOf(issue)

        override fun getIssue(issueKey: String): TrackerIssue = issue

        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) = Unit

        override fun transitionIssue(issueKey: String, statusName: String) = Unit

        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment =
            TrackerComment("agent-comment", null, role.markerKeyPart, "${role.commentPrefix} $message", null)

        override fun hasProcessedCommentMarker(commentId: String, role: AgentRole): Boolean = false

        override fun markCommentProcessed(commentId: String, role: AgentRole): Boolean {
            markedComments += commentId to role
            return true
        }
    }

    private class InMemoryProcessedCommentStore : ProcessedCommentStore {
        private val processed = mutableSetOf<Triple<String, String, AgentRole>>()

        override fun isProcessed(storyKey: String, commentId: String, role: AgentRole): Boolean =
            Triple(storyKey, commentId, role) in processed

        override fun markProcessed(storyKey: String, commentId: String, role: AgentRole) {
            processed += Triple(storyKey, commentId, role)
        }
    }

    private companion object {
        fun issue(comments: List<TrackerComment> = emptyList()): TrackerIssue =
            TrackerIssue(
                key = "KAN-69",
                summary = "Story KAN-69",
                description = "Maak de factory flow compleet.",
                status = "AI",
                fields = TrackerIssueFields(
                    targetRepo = "git@github.com:robbertvdzon/sample-build-project.git",
                    aiPhase = null,
                    aiLevel = 5,
                    aiTokenBudget = 40000,
                    aiTokensUsed = 0,
                    agentStartedAt = null,
                    paused = false,
                    error = null,
                ),
                comments = comments,
            )
    }
}
