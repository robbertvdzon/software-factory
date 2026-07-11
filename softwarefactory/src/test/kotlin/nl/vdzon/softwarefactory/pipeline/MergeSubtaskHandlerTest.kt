package nl.vdzon.softwarefactory.pipeline

import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.core.StoryRunRecord
import nl.vdzon.softwarefactory.core.StoryRunRepository
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerIssueFields
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.github.GitHubClientException
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.github.PullRequestInfo
import nl.vdzon.softwarefactory.github.PullRequestChecksResult
import nl.vdzon.softwarefactory.merge.PullRequestMergeResult
import nl.vdzon.softwarefactory.merge.PullRequestMergeService
import nl.vdzon.softwarefactory.pipeline.service.MergeSubtaskHandler
import nl.vdzon.softwarefactory.tracker.TrackerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.OffsetDateTime

class MergeSubtaskHandlerTest {

    private val parentKey = "SF-100"
    private val subtaskKey = "SF-101"
    private val targetRepo = "git@github.com:robbert/sf.git"

    private fun subtask(phase: SubtaskPhase?) = TrackerIssue(
        key = subtaskKey,
        summary = "Merge subtask",
        status = "Open",
        fields = TrackerIssueFields(
            targetRepo = targetRepo,
            aiPhase = null,
            aiLevel = null,
            aiTokenBudget = null,
            aiTokensUsed = null,
            agentStartedAt = null,
            paused = false,
            error = null,
            subtaskPhase = phase?.trackerValue,
        ),
        comments = emptyList(),
    )

    private fun parentIssue(projectName: String = "softwarefactory") = TrackerIssue(
        key = parentKey,
        summary = "Parent story",
        status = "Open",
        fields = TrackerIssueFields(
            targetRepo = targetRepo,
            repo = projectName,
            aiPhase = null,
            aiLevel = null,
            aiTokenBudget = null,
            aiTokensUsed = null,
            agentStartedAt = null,
            paused = false,
            error = null,
        ),
        comments = emptyList(),
    )

    private val storyRun = StoryRunRecord(id = 1L, storyKey = parentKey, targetRepo = targetRepo, prNumber = 42)

    // advanceChain zit niet meer in de handler-constructor maar gaat per process-aanroep mee.
    private val defaultAdvance: (TrackerIssue) -> IssueProcessResult = { IssueProcessResult.Chained(subtaskKey, null) }

    private fun buildHandler(
        capturedUpdates: MutableList<Pair<String, TrackerFieldUpdate>> = mutableListOf(),
        capturedErrors: MutableList<Pair<String, TrackerFieldUpdate>> = mutableListOf(),
        mergeResult: PullRequestMergeResult = PullRequestMergeResult.Merged("automatic-test-head"),
        invokeBeforeMerge: Boolean = mergeResult is PullRequestMergeResult.Merged,
    ): MergeSubtaskHandler {
        val tracker = object : TrackerApi {
            override fun getIssue(issueKey: String) = parentIssue()
            override fun parentStoryKey(subtaskKey: String) = parentKey
            override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
                if (update.values[TrackerField.ERROR] != null) capturedErrors.add(issueKey to update)
                else capturedUpdates.add(issueKey to update)
            }

            override fun transitionIssue(issueKey: String, statusName: String) {}
            override fun postAgentComment(issueKey: String, role: nl.vdzon.softwarefactory.core.AgentRole, message: String) = error("unused")
        }
        val storyRunRepo = object : StoryRunRepository {
            override fun openOrCreate(storyKey: String, targetRepo: String) = storyRun
            override fun get(storyRunId: Long) = storyRun
            override fun updatePullRequest(storyRunId: Long, branchName: String, prNumber: Int?, prUrl: String?, baseBranch: String?, branchPrefix: String?, previewUrlTemplate: String?, previewNamespaceTemplate: String?, previewDbSecretRecipe: String?) {}
            override fun activePullRequests() = emptyList<StoryRunRecord>()
            override fun activeRuns() = emptyList<StoryRunRecord>()
            override fun close(storyRunId: Long, finalStatus: String, endedAt: OffsetDateTime) {}
        }
        val mergeService = PullRequestMergeService { _, _, _, beforeMerge ->
            if (invokeBeforeMerge) beforeMerge()
            mergeResult
        }
        return MergeSubtaskHandler(tracker, storyRunRepo, mergeService)
    }

    @Test
    fun `START always merges automatically MERGING then MERGE_APPROVED and never AWAITING_HUMAN`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val handler = buildHandler(capturedUpdates = updates)
        val result = handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START, defaultAdvance)

        assertTrue(result is IssueProcessResult.Recovered)
        val phases = updates.map { it.second.values[TrackerField.SUBTASK_PHASE] }
        // Het automatische merge-pad doorloopt MERGING -> MERGE_APPROVED, in die volgorde.
        assertEquals(listOf(SubtaskPhase.MERGING.trackerValue, SubtaskPhase.MERGE_APPROVED.trackerValue), phases)
        // De merge-subtaak wordt nooit op AWAITING_HUMAN gezet (geen handmatige merge-poort meer).
        assertTrue(SubtaskPhase.AWAITING_HUMAN.trackerValue !in phases)
        assertEquals(SubtaskPhase.MERGE_APPROVED.trackerValue, (result as IssueProcessResult.Recovered).phase)
    }

    @Test
    fun `merge failure sets ERROR and resets phase to START`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val errors = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val handler = buildHandler(
            capturedUpdates = updates,
            capturedErrors = errors,
            mergeResult = PullRequestMergeResult.Blocked("merge failed"),
        )
        val result = handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START, defaultAdvance)

        assertTrue(result is IssueProcessResult.Errored)
        assertTrue(errors.isNotEmpty())
        // Errored, geen awaiting-human: de fase wordt teruggezet op START, niet op AWAITING_HUMAN.
        val errorPhase = errors.last().second.values[TrackerField.SUBTASK_PHASE]
        assertEquals(SubtaskPhase.START.trackerValue, errorPhase)
        val allPhases = (updates + errors).map { it.second.values[TrackerField.SUBTASK_PHASE] }
        assertTrue(SubtaskPhase.AWAITING_HUMAN.trackerValue !in allPhases)
    }

    @Test
    fun `missing or red required check blocks merge and resets phase to START`() {
        val errors = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val handler = buildHandler(
            capturedErrors = errors,
            mergeResult = PullRequestMergeResult.Blocked("Verplichte GitHub-check ontbreekt"),
        )

        val result = handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START, defaultAdvance)

        assertTrue(result is IssueProcessResult.Errored)
        assertEquals(SubtaskPhase.START.trackerValue, errors.last().second.values[TrackerField.SUBTASK_PHASE])
        assertTrue(errors.last().second.values[TrackerField.ERROR].toString().contains("check ontbreekt"))
    }

    @Test
    fun `pending check waits in START without Error and is retried by a later poll`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val errors = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val handler = buildHandler(
            capturedUpdates = updates,
            capturedErrors = errors,
            mergeResult = PullRequestMergeResult.Pending("Repository verification=in_progress"),
        )

        val result = handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START, defaultAdvance)

        assertTrue(result is IssueProcessResult.Skipped)
        assertTrue(errors.isEmpty())
        assertEquals(SubtaskPhase.START.trackerValue, updates.last().second.values[TrackerField.SUBTASK_PHASE])
        assertEquals(null, updates.last().second.values[TrackerField.ERROR])
    }

    @Test
    fun `head race after MERGING resets to START without Error`() {
        val updates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val errors = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        val handler = buildHandler(
            capturedUpdates = updates,
            capturedErrors = errors,
            mergeResult = PullRequestMergeResult.Pending("PR-head wijzigde"),
            invokeBeforeMerge = true,
        )

        val result = handler.process(subtask(SubtaskPhase.START), SubtaskPhase.START, defaultAdvance)

        assertTrue(result is IssueProcessResult.Skipped)
        assertEquals(
            listOf(SubtaskPhase.MERGING.trackerValue, SubtaskPhase.START.trackerValue),
            updates.map { it.second.values[TrackerField.SUBTASK_PHASE] },
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `MERGE_APPROVED advances the chain to DEPLOY`() {
        val advanced = IssueProcessResult.Chained(subtaskKey, null)
        val handler = buildHandler()
        val result = handler.process(subtask(SubtaskPhase.MERGE_APPROVED), SubtaskPhase.MERGE_APPROVED) { advanced }
        assertEquals(advanced, result)
    }

    @Test
    fun `MERGING stays in progress`() {
        val handler = buildHandler()
        val result = handler.process(subtask(SubtaskPhase.MERGING), SubtaskPhase.MERGING, defaultAdvance)
        assertTrue(result is IssueProcessResult.Skipped)
    }

    @Test
    fun `null phase returns Skipped`() {
        val handler = buildHandler()
        val result = handler.process(subtask(null), null, defaultAdvance)
        assertTrue(result is IssueProcessResult.Skipped)
    }
}
