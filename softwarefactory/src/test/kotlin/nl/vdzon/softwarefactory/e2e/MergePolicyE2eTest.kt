package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.core.OrchestratorSettings
import nl.vdzon.softwarefactory.core.StoryRunRepository
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerIssueFields
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.github.PullRequestChecksResult
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.github.PullRequestHeadChangedException
import nl.vdzon.softwarefactory.github.PullRequestInfo
import nl.vdzon.softwarefactory.merge.internal.ProjectAwarePullRequestMergeService
import nl.vdzon.softwarefactory.orchestrator.services.ManualCommandService
import nl.vdzon.softwarefactory.pipeline.service.MergeSubtaskHandler
import nl.vdzon.softwarefactory.testsupport.FakeAgentRuntime
import nl.vdzon.softwarefactory.testsupport.FakePreviewEnvironmentCleaner
import nl.vdzon.softwarefactory.testsupport.FakeTrackerApi
import nl.vdzon.softwarefactory.testsupport.InMemoryProcessedCommentStore
import nl.vdzon.softwarefactory.testsupport.InMemoryStoryRunRepository
import nl.vdzon.softwarefactory.tracker.services.ProcessedCommentService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Cross-module regressie: beide echte entrypoints zijn bedraad op dezelfde centrale mergeservice.
 * Iedere readinessvariant wordt met een verse tracker/run uitgevoerd zodat geen unitfake-bypass
 * onbedoeld een van beide paden groen kan maken.
 */
class MergePolicyE2eTest {
    private val now = OffsetDateTime.parse("2026-07-11T12:00:00Z")
    private val clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC)

    @Test
    fun `automatic and manual entrypoints enforce every readiness outcome identically`() {
        val cases = listOf(
            Case("ready", PullRequestChecksResult.Ready("head-a", emptyList()), merged = true, pending = false),
            Case("pending", PullRequestChecksResult.Pending("queued"), merged = false, pending = true),
            Case("missing", PullRequestChecksResult.Blocked("missing"), merged = false, pending = false),
            Case("skipped", PullRequestChecksResult.Blocked("skipped"), merged = false, pending = false),
            Case("cancelled", PullRequestChecksResult.Blocked("cancelled"), merged = false, pending = false),
            Case("failed", PullRequestChecksResult.Blocked("failed"), merged = false, pending = false),
            Case("api-error", PullRequestChecksResult.Blocked("API unavailable"), merged = false, pending = false),
        )

        cases.forEach { scenario ->
            assertAutomatic(scenario)
            assertManual(scenario)
        }
    }

    @Test
    fun `head A green then B pushed is retryable through both entrypoints`() {
        val automatic = automaticFixture(PullRequestChecksResult.Ready("head-a", emptyList()), headRace = true)
        val automaticResult = automatic.handler.process(
            automatic.subtask,
            SubtaskPhase.START,
        ) { IssueProcessResult.Chained(automatic.subtask.key, null) }
        assertTrue(automaticResult is IssueProcessResult.Skipped)
        assertTrue(automatic.github.completedMerges.isEmpty())
        assertEquals(null, automatic.tracker.lastUpdate(automatic.subtask.key).values[TrackerField.ERROR])

        val manual = manualFixture(PullRequestChecksResult.Ready("head-a", emptyList()), headRace = true)
        val manualResult = manual.service.apply(manual.story)
        assertTrue(manualResult.retryLater)
        assertTrue(manual.github.completedMerges.isEmpty())
        assertTrue(manual.tracker.transitions.isEmpty())
        assertEquals(null, manualResult.issue.fields.error)
    }

    private fun assertAutomatic(scenario: Case) {
        val fixture = automaticFixture(scenario.readiness)
        val result = fixture.handler.process(fixture.subtask, SubtaskPhase.START) {
            IssueProcessResult.Chained(fixture.subtask.key, null)
        }

        assertEquals(scenario.merged, result is IssueProcessResult.Recovered, "automatic ${scenario.name}")
        assertEquals(scenario.pending, result is IssueProcessResult.Skipped, "automatic ${scenario.name}")
        assertEquals(scenario.merged, fixture.github.completedMerges.size == 1, "automatic ${scenario.name}")
        if (!scenario.merged && !scenario.pending) {
            assertTrue(result is IssueProcessResult.Errored, "automatic ${scenario.name}")
        }
    }

    private fun assertManual(scenario: Case) {
        val fixture = manualFixture(scenario.readiness)
        val result = fixture.service.apply(fixture.story)

        assertEquals(scenario.merged, result.stopResult is IssueProcessResult.Merged, "manual ${scenario.name}")
        assertEquals(scenario.pending, result.retryLater, "manual ${scenario.name}")
        assertEquals(scenario.merged, fixture.github.completedMerges.size == 1, "manual ${scenario.name}")
        if (!scenario.merged && !scenario.pending) {
            assertTrue(result.stopResult is IssueProcessResult.Errored, "manual ${scenario.name}")
        }
        if (scenario.pending) {
            assertFalse(result.issue.fields.error != null, "manual pending has no Error")
        }
    }

    private fun automaticFixture(readiness: PullRequestChecksResult, headRace: Boolean = false): AutomaticFixture {
        val story = story(comments = emptyList())
        val subtask = subtask()
        val tracker = FakeTrackerApi(listOf(story, subtask), parentKey = story.key)
        val runs = seededRun(story.key)
        val github = ScenarioGitHubApi(readiness, headRace)
        val handler = MergeSubtaskHandler(tracker, runs, mergeService(github))
        return AutomaticFixture(tracker, github, handler, subtask)
    }

    private fun manualFixture(readiness: PullRequestChecksResult, headRace: Boolean = false): ManualFixture {
        val story = story(
            comments = listOf(TrackerComment("merge-command", "user", "User", "@factory:command:merge", null)),
        )
        val tracker = FakeTrackerApi(listOf(story))
        val github = ScenarioGitHubApi(readiness, headRace)
        val service = ManualCommandService(
            issueTrackerClient = tracker,
            processedCommentService = ProcessedCommentService(tracker, InMemoryProcessedCommentStore()),
            agentRuntime = FakeAgentRuntime(now),
            storyRunRepository = seededRun(story.key),
            pullRequestClient = github,
            pullRequestMergeService = mergeService(github),
            previewApi = FakePreviewEnvironmentCleaner(),
            storyWorkspaceService = null,
            settings = settings(),
            clock = clock,
        )
        return ManualFixture(tracker, github, service, story)
    }

    private fun seededRun(storyKey: String): StoryRunRepository {
        val runs = InMemoryStoryRunRepository()
        val run = runs.openOrCreate(storyKey, TARGET_REPO)
        runs.updatePullRequest(run.id, "codex/test", 42, null, "main", "codex/", null, null, null)
        return runs
    }

    private fun mergeService(github: GitHubApi) = ProjectAwarePullRequestMergeService(
        github,
        ProjectConfiguration(
            repos = mapOf(PROJECT to TARGET_REPO),
            requiredChecks = mapOf(PROJECT to setOf("Backend verification")),
        ),
    )

    private fun story(comments: List<TrackerComment>) = TrackerIssue(
        key = "SF-100",
        summary = "Merge policy e2e",
        status = "Develop",
        fields = fields(repo = PROJECT, subtaskPhase = null),
        comments = comments,
    )

    private fun subtask() = TrackerIssue(
        key = "SF-101",
        summary = "Merge",
        status = "Develop",
        fields = fields(repo = PROJECT, subtaskPhase = SubtaskPhase.START.trackerValue),
        comments = emptyList(),
    )

    private fun fields(repo: String, subtaskPhase: String?) = TrackerIssueFields(
        targetRepo = TARGET_REPO,
        repo = repo,
        aiPhase = null,
        aiLevel = null,
        aiTokenBudget = null,
        aiTokensUsed = null,
        agentStartedAt = null,
        paused = false,
        error = null,
        subtaskPhase = subtaskPhase,
    )

    private fun settings() = OrchestratorSettings(
        pollInterval = Duration.ofSeconds(15),
        maxParallelRefiner = 1,
        maxParallelDeveloper = 1,
        maxParallelReviewer = 1,
        maxParallelTester = 1,
        maxParallelTotal = 2,
        maxDeveloperLoopbacks = 2,
        maxTransientRetries = 2,
        hardTimeout = Duration.ofMinutes(60),
        costMonitorInterval = Duration.ofMinutes(5),
        creditsPauseDefault = Duration.ofMinutes(30),
    )

    private data class Case(
        val name: String,
        val readiness: PullRequestChecksResult,
        val merged: Boolean,
        val pending: Boolean,
    )

    private data class AutomaticFixture(
        val tracker: FakeTrackerApi,
        val github: ScenarioGitHubApi,
        val handler: MergeSubtaskHandler,
        val subtask: TrackerIssue,
    )

    private data class ManualFixture(
        val tracker: FakeTrackerApi,
        val github: ScenarioGitHubApi,
        val service: ManualCommandService,
        val story: TrackerIssue,
    )

    private class ScenarioGitHubApi(
        private val readiness: PullRequestChecksResult,
        private val headRace: Boolean,
    ) : GitHubApi {
        val completedMerges = mutableListOf<String>()

        override fun requiredChecks(targetRepo: String, prNumber: Int, requiredNames: Set<String>) = readiness

        override fun mergePullRequest(targetRepo: String, prNumber: Int, expectedHeadSha: String) {
            if (headRace) throw PullRequestHeadChangedException(expectedHeadSha, "head-b")
            completedMerges += expectedHeadSha
        }

        override fun ensurePullRequest(
            repoRoot: Path,
            branchName: String,
            baseBranch: String,
            title: String,
            body: String,
        ) = PullRequestInfo(42, null)

        override fun isMerged(targetRepo: String, prNumber: Int) = false
        override fun unprocessedFactoryComments(targetRepo: String, prNumber: Int) = emptyList<PullRequestComment>()
        override fun claimedFactoryComments(targetRepo: String, prNumber: Int) = emptyList<PullRequestComment>()
        override fun markCommentClaimed(targetRepo: String, commentId: Long) = Unit
        override fun markCommentDone(targetRepo: String, commentId: Long) = Unit
        override fun markCommentFailed(targetRepo: String, commentId: Long) = Unit
        override fun closePullRequest(targetRepo: String, prNumber: Int) = Unit
        override fun deleteBranch(targetRepo: String, branchName: String) = Unit
    }

    private companion object {
        const val PROJECT = "softwarefactory"
        const val TARGET_REPO = "git@github.com:robbertvdzon/software-factory.git"
    }
}
