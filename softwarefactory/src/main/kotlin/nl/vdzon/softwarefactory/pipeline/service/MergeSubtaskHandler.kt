package nl.vdzon.softwarefactory.pipeline.service

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
import org.springframework.stereotype.Component

/**
 * Verwerkt een MERGE-subtask: merget bij fase START altijd automatisch de feature-branch
 * via de GitHub API. Gewone Spring-bean: de advanceChain-functie zit niet meer in de
 * constructor (dat dwong [SubtaskExecutionCoordinator] tot handmatige constructie), maar
 * wordt per [process]-aanroep meegegeven om de keten door te zetten zodra de merge klaar is.
 *
 * De merge is onvoorwaardelijk; er is geen configureerbare handmatige merge-poort meer.
 * De handmatige goedkeuring vóór de merge gebeurt in een aparte manual-approve-subtaak,
 * niet hier.
 */
@Component
class MergeSubtaskHandler(
    private val issueTrackerClient: YouTrackApi,
    private val storyRunRepository: StoryRunRepository,
    private val gitHubApi: GitHubApi,
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
