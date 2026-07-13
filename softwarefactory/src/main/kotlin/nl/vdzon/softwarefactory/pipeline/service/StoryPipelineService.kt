package nl.vdzon.softwarefactory.pipeline.service

import nl.vdzon.softwarefactory.core.contracts.StoryPipeline
import nl.vdzon.softwarefactory.core.contracts.AiPhase
import nl.vdzon.softwarefactory.core.contracts.CostMonitor
import nl.vdzon.softwarefactory.core.contracts.IssueProcessResult
import nl.vdzon.softwarefactory.core.contracts.IssueType
import nl.vdzon.softwarefactory.core.contracts.ManualCommandProcessor
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.tracker.IssueLifecyclePort
import org.springframework.stereotype.Component

/**
 * Dunne router/facade van de pipeline-engine ([StoryPipeline]-port). Past handmatige commando's en
 * de generieke guards (paused/error/supplier) toe en routeert vervolgens naar de
 * [StoryRefinementCoordinator] (story-fasen) of de [SubtaskExecutionCoordinator] (subtask-pipeline).
 *
 * Alle daadwerkelijke beslis-/uitvoeringslogica zit in die coördinatoren + de [AgentDispatcher];
 * deze klasse bevat bewust geen state-machine-logica.
 */
@Component
class StoryPipelineService(
    private val issueTrackerClient: IssueLifecyclePort,
    private val costMonitor: CostMonitor,
    private val manualCommandProcessor: ManualCommandProcessor,
    private val storyRefinementCoordinator: StoryRefinementCoordinator,
    private val subtaskExecutionCoordinator: SubtaskExecutionCoordinator,
) : StoryPipeline {

    override fun process(issue: TrackerIssue): IssueProcessResult {
        val manualCommandApplication = manualCommandProcessor.apply(issue)
        manualCommandApplication.stopResult?.let { return it }

        val currentIssue = costMonitor.applyBudgetTriggers(manualCommandApplication.issue)
        if (currentIssue.fields.paused) {
            return IssueProcessResult.Skipped(currentIssue.key, "paused")
        }
        if (!currentIssue.fields.error.isNullOrBlank()) {
            recoverRetryableIssueError(currentIssue)?.let { return it }
            return IssueProcessResult.Skipped(currentIssue.key, "error")
        }
        // Voor een STORY is de eigen supplier vereist. Een SUBTASK mag een lege supplier
        // hebben en erft die van de parent — dat wordt bij de dispatch afgehandeld.
        if (currentIssue.fields.issueType == IssueType.STORY &&
            (currentIssue.fields.aiSupplier.isNullOrBlank() || currentIssue.fields.aiSupplier.equals("none", ignoreCase = true))
        ) {
            return IssueProcessResult.Skipped(currentIssue.key, "ai-supplier")
        }

        // Router op IssueType (afgeleid uit het `Type`-veld): story-refinementflow vs. subtask-pipeline.
        return when (currentIssue.fields.issueType) {
            IssueType.STORY -> storyRefinementCoordinator.processStoryRefinement(currentIssue)
            IssueType.SUBTASK -> subtaskExecutionCoordinator.processSubtask(currentIssue)
        }
    }

    private fun recoverRetryableIssueError(issue: TrackerIssue): IssueProcessResult? {
        val error = issue.fields.error.orEmpty()
        if (!error.contains("[ORCHESTRATOR] Geen actieve container gevonden")) {
            return null
        }
        val phase = AiPhase.fromTracker(issue.fields.aiPhase)?.takeIf { it.isActive } ?: return null
        val previousPhase = AiPhase.previousCompletedBeforeRetry(phase)
        issueTrackerClient.updateIssueFields(
            issue.key,
            TrackerFieldUpdate.of(
                TrackerField.ERROR to null,
                TrackerField.AI_PHASE to previousPhase?.trackerValue,
            ),
        )
        return IssueProcessResult.Recovered(issue.key, previousPhase?.trackerValue ?: "<empty>")
    }
}
