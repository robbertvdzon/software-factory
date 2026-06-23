package nl.vdzon.softwarefactory.pipeline.service

import nl.vdzon.softwarefactory.config.MergeConfig
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.core.StoryRunRepository
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.github.GitHubClientException
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import org.slf4j.LoggerFactory

/**
 * Verwerkt een MERGE-subtask: zet in AWAITING_HUMAN (manual) of merget automatisch
 * de feature-branch via de GitHub API (automatic). Wordt aangemaakt door
 * [SubtaskExecutionCoordinator] die de advanceChain-functie meegeeft om de keten
 * door te zetten zodra de merge klaar is.
 */
class MergeSubtaskHandler(
    private val issueTrackerClient: YouTrackApi,
    private val projectRepoResolver: ProjectRepoResolver,
    private val storyRunRepository: StoryRunRepository,
    private val gitHubApi: GitHubApi,
    private val advanceChain: (TrackerIssue) -> IssueProcessResult,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun process(subtask: TrackerIssue, phase: SubtaskPhase?): IssueProcessResult {
        val parentKey = issueTrackerClient.parentStoryKey(subtask.key)
            ?: return IssueProcessResult.Skipped(subtask.key, "merge-no-parent")
        val parent = runCatching { issueTrackerClient.getIssue(parentKey) }.getOrNull()
        val projectName = parent?.fields?.repo
        val mergeConfig = projectRepoResolver.mergeConfigFor(projectName)

        return when (phase) {
            null -> IssueProcessResult.Skipped(subtask.key, "not-started")
            SubtaskPhase.START -> when (mergeConfig) {
                MergeConfig.Manual -> {
                    issueTrackerClient.updateIssueFields(
                        subtask.key,
                        TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.AWAITING_HUMAN.trackerValue),
                    )
                    IssueProcessResult.Recovered(subtask.key, SubtaskPhase.AWAITING_HUMAN.trackerValue)
                }
                MergeConfig.Automatic -> performAutomaticMerge(subtask, parentKey)
            }
            SubtaskPhase.AWAITING_HUMAN -> IssueProcessResult.Skipped(subtask.key, "waiting-for-user")
            SubtaskPhase.MANUAL_ACTION_DONE -> advanceChain(subtask)
            SubtaskPhase.MERGING -> IssueProcessResult.Skipped(subtask.key, "merging-in-progress")
            SubtaskPhase.MERGE_APPROVED -> advanceChain(subtask)
            else -> IssueProcessResult.Skipped(subtask.key, "merge-unexpected:${phase.trackerValue}")
        }
    }

    private fun performAutomaticMerge(subtask: TrackerIssue, parentKey: String): IssueProcessResult {
        issueTrackerClient.updateIssueFields(
            subtask.key,
            TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.MERGING.trackerValue),
        )
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
        return try {
            gitHubApi.mergePullRequest(targetRepo, prNumber)
            logger.info("Automatische merge geslaagd: PR #{} van {} voor subtask {}.", prNumber, targetRepo, subtask.key)
            issueTrackerClient.updateIssueFields(
                subtask.key,
                TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.MERGE_APPROVED.trackerValue),
            )
            IssueProcessResult.Recovered(subtask.key, SubtaskPhase.MERGE_APPROVED.trackerValue)
        } catch (ex: GitHubClientException) {
            val errorMsg = "[ORCHESTRATOR] Automatische merge mislukt voor ${subtask.key}: ${ex.message}"
            logger.error(errorMsg, ex)
            // Zet fase terug op START zodat de orchestrator niet in MERGING blijft hangen bij de volgende cycle.
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
