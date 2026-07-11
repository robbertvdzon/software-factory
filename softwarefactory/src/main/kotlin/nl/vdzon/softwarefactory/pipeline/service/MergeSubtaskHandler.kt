package nl.vdzon.softwarefactory.pipeline.service

import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.core.StoryRunRepository
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.merge.PullRequestMergeResult
import nl.vdzon.softwarefactory.merge.PullRequestMergeService
import nl.vdzon.softwarefactory.tracker.TrackerApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Verwerkt een MERGE-subtask via de centrale, projectbewuste mergepolicy. Pending CI laat de
 * subtaak in START wachten zonder Error; alleen groen bewijs op de actuele PR-head kan mergen.
 * Gewone Spring-bean: de advanceChain-functie zit niet meer in de
 * constructor (dat dwong [SubtaskExecutionCoordinator] tot handmatige constructie), maar
 * wordt per [process]-aanroep meegegeven om de keten door te zetten zodra de merge klaar is.
 *
 * De handmatige goedkeuring vóór de merge gebeurt in een aparte manual-approve-subtaak.
 */
@Component
class MergeSubtaskHandler(
    private val issueTrackerClient: TrackerApi,
    private val storyRunRepository: StoryRunRepository,
    private val pullRequestMergeService: PullRequestMergeService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun process(
        subtask: TrackerIssue,
        phase: SubtaskPhase?,
        advanceChain: (TrackerIssue) -> IssueProcessResult,
    ): IssueProcessResult {
        val parentKey = issueTrackerClient.parentStoryKey(subtask.key)
            ?: return IssueProcessResult.Skipped(subtask.key, "merge-no-parent")
        return when (phase) {
            null -> IssueProcessResult.Skipped(subtask.key, "not-started")
            SubtaskPhase.START -> performAutomaticMerge(subtask, parentKey)
            SubtaskPhase.MERGING -> IssueProcessResult.Skipped(subtask.key, "merging-in-progress")
            SubtaskPhase.MERGE_APPROVED -> advanceChain(subtask)
            else -> IssueProcessResult.Skipped(subtask.key, "merge-unexpected:${phase.trackerValue}")
        }
    }

    private fun performAutomaticMerge(subtask: TrackerIssue, parentKey: String): IssueProcessResult {
        val storyRun = runCatching { storyRunRepository.openOrCreate(parentKey, "") }.getOrNull()
        val targetRepo = storyRun?.targetRepo?.takeIf { it.isNotEmpty() }
            ?: run {
                val errorMsg = "[ORCHESTRATOR] Geen targetRepo gevonden voor automatische merge van ${subtask.key}."
                issueTrackerClient.updateIssueFields(subtask.key, TrackerFieldUpdate.of(TrackerField.ERROR to errorMsg))
                return IssueProcessResult.Errored(subtask.key, errorMsg)
            }
        val prNumber = storyRun.prNumber
            ?: run {
                val errorMsg = "[ORCHESTRATOR] Geen PR-nummer gevonden voor automatische merge van ${subtask.key}."
                issueTrackerClient.updateIssueFields(subtask.key, TrackerFieldUpdate.of(TrackerField.ERROR to errorMsg))
                return IssueProcessResult.Errored(subtask.key, errorMsg)
            }
        val projectName = runCatching { issueTrackerClient.getIssue(parentKey).fields.repo }.getOrNull()
        return when (
            val result = pullRequestMergeService.merge(projectName, targetRepo, prNumber) {
                issueTrackerClient.updateIssueFields(
                    subtask.key,
                    TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.MERGING.trackerValue),
                )
            }
        ) {
            is PullRequestMergeResult.Merged -> {
                logger.info(
                    "Automatische merge geslaagd: PR #{} van {} op head {} voor subtask {}.",
                    prNumber, targetRepo, result.verifiedHeadSha, subtask.key,
                )
                issueTrackerClient.updateIssueFields(
                    subtask.key,
                    TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.MERGE_APPROVED.trackerValue),
                )
                IssueProcessResult.Recovered(subtask.key, SubtaskPhase.MERGE_APPROVED.trackerValue)
            }
            is PullRequestMergeResult.Pending -> {
                issueTrackerClient.updateIssueFields(
                    subtask.key,
                    TrackerFieldUpdate.of(
                        TrackerField.SUBTASK_PHASE to SubtaskPhase.START.trackerValue,
                        TrackerField.ERROR to null,
                    ),
                )
                IssueProcessResult.Skipped(subtask.key, "merge-pending:${result.reason}")
            }
            is PullRequestMergeResult.Blocked -> {
                val errorMsg = "[ORCHESTRATOR] Automatische merge geblokkeerd voor ${subtask.key}: ${result.reason}"
                logger.error(errorMsg)
                issueTrackerClient.updateIssueFields(
                    subtask.key,
                    TrackerFieldUpdate.of(
                        TrackerField.SUBTASK_PHASE to SubtaskPhase.START.trackerValue,
                        TrackerField.ERROR to errorMsg,
                    ),
                )
                IssueProcessResult.Errored(subtask.key, errorMsg)
            }
        }
    }
}
