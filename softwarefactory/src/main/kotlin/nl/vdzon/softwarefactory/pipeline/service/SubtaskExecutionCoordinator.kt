package nl.vdzon.softwarefactory.pipeline.service

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.AgentRunRepository
import nl.vdzon.softwarefactory.core.AgentRuntime
import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.core.OrchestratorSettings
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.SubtaskType
import nl.vdzon.softwarefactory.core.StoryRunRepository
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.github.GitHubApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime

/**
 * Fase 5 — SubtaskExecutionCoordinator. Voert de subtask-pipeline uit per type
 * (development/review/test/manual/summary/merge/deploy) op de gedeelde story-branch; recovery van een actieve
 * fase, auto-approve, en het doorzetten van de keten (fase 4). Dispatchen gaat via [AgentDispatcher].
 */
@Component
class SubtaskExecutionCoordinator(
    private val issueTrackerClient: YouTrackApi,
    private val agentRuntime: AgentRuntime,
    private val storyRunRepository: StoryRunRepository,
    private val agentRunRepository: AgentRunRepository,
    private val projectRepoResolver: ProjectRepoResolver,
    private val settings: OrchestratorSettings,
    private val clock: Clock,
    private val dispatcher: AgentDispatcher,
    private val gitHubApi: GitHubApi,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // YouTrack State-lane: een afgeronde subtask/story → Done.
    private val STATE_DONE = "Done"

    private val mergeHandler by lazy {
        MergeSubtaskHandler(issueTrackerClient, projectRepoResolver, storyRunRepository, gitHubApi, ::advanceSubtaskChain)
    }
    private val deployHandler by lazy {
        DeploySubtaskHandler(issueTrackerClient, projectRepoResolver, ::advanceSubtaskChain, clock)
    }

    fun processSubtask(subtask: TrackerIssue): IssueProcessResult {
        val type = SubtaskType.fromTracker(subtask.fields.subtaskType)
            ?: return IssueProcessResult.Skipped(subtask.key, "unknown-subtask-type")
        val phase = SubtaskPhase.fromTracker(subtask.fields.subtaskPhase)
        return when (type) {
            SubtaskType.MANUAL -> manualSubtask(subtask, phase)
            SubtaskType.DEVELOPMENT -> developmentSubtask(subtask, phase)
            SubtaskType.REVIEW -> reviewSubtask(subtask, phase)
            SubtaskType.TEST -> testSubtask(subtask, phase)
            SubtaskType.SUMMARY -> summarySubtask(subtask, phase)
            SubtaskType.MERGE -> mergeHandler.process(subtask, phase)
            SubtaskType.DEPLOY -> deployHandler.process(subtask, phase)
        }
    }

    private fun manualSubtask(subtask: TrackerIssue, phase: SubtaskPhase?): IssueProcessResult =
        when (phase) {
            null -> IssueProcessResult.Skipped(subtask.key, "not-started")
            SubtaskPhase.START -> {
                issueTrackerClient.updateIssueFields(
                    subtask.key,
                    TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.AWAITING_HUMAN.trackerValue),
                )
                IssueProcessResult.Recovered(subtask.key, SubtaskPhase.AWAITING_HUMAN.trackerValue)
            }
            SubtaskPhase.AWAITING_HUMAN -> IssueProcessResult.Skipped(subtask.key, "waiting-for-user")
            SubtaskPhase.MANUAL_ACTION_DONE -> advanceSubtaskChain(subtask)
            else -> IssueProcessResult.Skipped(subtask.key, "manual-unexpected:${phase.trackerValue}")
        }

    private fun developmentSubtask(subtask: TrackerIssue, phase: SubtaskPhase?): IssueProcessResult =
        when (phase) {
            null -> IssueProcessResult.Skipped(subtask.key, "not-started")
            SubtaskPhase.START,
            SubtaskPhase.DEVELOPMENT_QUESTIONS_ANSWERED,
            -> dispatchSubtask(subtask, AgentRole.DEVELOPER, SubtaskPhase.DEVELOPING)
            SubtaskPhase.DEVELOPMENT_REJECTED ->
                dispatchSubtask(subtask, AgentRole.DEVELOPER, SubtaskPhase.DEVELOPING, loopback = true)
            SubtaskPhase.DEVELOPING -> recoverActiveSubtaskPhase(subtask, SubtaskPhase.DEVELOPING)
            SubtaskPhase.DEVELOPED_WITH_QUESTIONS -> IssueProcessResult.Skipped(subtask.key, "waiting-for-user")
            SubtaskPhase.DEVELOPED -> autoAdvanceSubtask(subtask, SubtaskPhase.DEVELOPMENT_APPROVED)
            SubtaskPhase.DEVELOPMENT_APPROVED -> dispatchSubtask(subtask, AgentRole.REVIEWER, SubtaskPhase.REVIEWING)
            SubtaskPhase.REVIEW_QUESTIONS_ANSWERED -> dispatchSubtask(subtask, AgentRole.REVIEWER, SubtaskPhase.REVIEWING)
            SubtaskPhase.REVIEW_REJECTED ->
                dispatchSubtask(subtask, AgentRole.DEVELOPER, SubtaskPhase.DEVELOPING, loopback = true)
            SubtaskPhase.REVIEWING -> recoverActiveSubtaskPhase(subtask, SubtaskPhase.REVIEWING)
            SubtaskPhase.REVIEWED_WITH_QUESTIONS -> IssueProcessResult.Skipped(subtask.key, "waiting-for-user")
            SubtaskPhase.REVIEWED -> autoAdvanceSubtask(subtask, SubtaskPhase.REVIEW_APPROVED)
            SubtaskPhase.REVIEW_APPROVED -> advanceSubtaskChain(subtask)
            else -> IssueProcessResult.Skipped(subtask.key, "dev-subtask-unexpected:${phase.trackerValue}")
        }

    private fun reviewSubtask(subtask: TrackerIssue, phase: SubtaskPhase?): IssueProcessResult =
        when (phase) {
            null -> IssueProcessResult.Skipped(subtask.key, "not-started")
            SubtaskPhase.START,
            SubtaskPhase.REVIEW_QUESTIONS_ANSWERED,
            -> dispatchSubtask(subtask, AgentRole.REVIEWER, SubtaskPhase.REVIEWING)
            SubtaskPhase.REVIEWING -> recoverActiveSubtaskPhase(subtask, SubtaskPhase.REVIEWING)
            SubtaskPhase.REVIEWED_WITH_QUESTIONS -> IssueProcessResult.Skipped(subtask.key, "waiting-for-user")
            SubtaskPhase.REVIEWED -> autoAdvanceSubtask(subtask, SubtaskPhase.REVIEW_APPROVED)
            SubtaskPhase.REVIEW_REJECTED ->
                dispatchSubtask(subtask, AgentRole.DEVELOPER, SubtaskPhase.DEVELOPING, loopback = true)
            SubtaskPhase.DEVELOPING -> recoverActiveSubtaskPhase(subtask, SubtaskPhase.DEVELOPING)
            SubtaskPhase.DEVELOPED_WITH_QUESTIONS -> IssueProcessResult.Skipped(subtask.key, "waiting-for-user")
            SubtaskPhase.DEVELOPMENT_QUESTIONS_ANSWERED -> dispatchSubtask(subtask, AgentRole.DEVELOPER, SubtaskPhase.DEVELOPING)
            // Story-brede review: geen aparte dev-goedkeuring; na de fix direct re-review.
            SubtaskPhase.DEVELOPED -> dispatchSubtask(subtask, AgentRole.REVIEWER, SubtaskPhase.REVIEWING)
            SubtaskPhase.REVIEW_APPROVED -> advanceSubtaskChain(subtask)
            else -> IssueProcessResult.Skipped(subtask.key, "review-subtask-unexpected:${phase.trackerValue}")
        }

    private fun testSubtask(subtask: TrackerIssue, phase: SubtaskPhase?): IssueProcessResult =
        when (phase) {
            null -> IssueProcessResult.Skipped(subtask.key, "not-started")
            SubtaskPhase.START,
            SubtaskPhase.TEST_QUESTIONS_ANSWERED,
            -> dispatchSubtask(subtask, AgentRole.TESTER, SubtaskPhase.TESTING)
            SubtaskPhase.TESTING -> recoverActiveSubtaskPhase(subtask, SubtaskPhase.TESTING)
            SubtaskPhase.TESTED_WITH_QUESTIONS -> IssueProcessResult.Skipped(subtask.key, "waiting-for-user")
            SubtaskPhase.TESTED -> autoAdvanceSubtask(subtask, SubtaskPhase.TEST_APPROVED)
            SubtaskPhase.TEST_REJECTED ->
                dispatchSubtask(subtask, AgentRole.DEVELOPER, SubtaskPhase.DEVELOPING, loopback = true)
            SubtaskPhase.DEVELOPING -> recoverActiveSubtaskPhase(subtask, SubtaskPhase.DEVELOPING)
            SubtaskPhase.DEVELOPED_WITH_QUESTIONS -> IssueProcessResult.Skipped(subtask.key, "waiting-for-user")
            SubtaskPhase.DEVELOPMENT_QUESTIONS_ANSWERED -> dispatchSubtask(subtask, AgentRole.DEVELOPER, SubtaskPhase.DEVELOPING)
            // Story-brede test: na de fix direct re-test (geen aparte dev-goedkeuring).
            SubtaskPhase.DEVELOPED -> dispatchSubtask(subtask, AgentRole.TESTER, SubtaskPhase.TESTING)
            SubtaskPhase.TEST_APPROVED -> advanceSubtaskChain(subtask)
            else -> IssueProcessResult.Skipped(subtask.key, "test-subtask-unexpected:${phase.trackerValue}")
        }

    private fun summarySubtask(subtask: TrackerIssue, phase: SubtaskPhase?): IssueProcessResult =
        when (phase) {
            null -> IssueProcessResult.Skipped(subtask.key, "not-started")
            SubtaskPhase.START,
            SubtaskPhase.SUMMARY_QUESTIONS_ANSWERED,
            SubtaskPhase.SUMMARY_REJECTED,
            -> dispatchSubtask(subtask, AgentRole.SUMMARIZER, SubtaskPhase.SUMMARIZING)
            SubtaskPhase.SUMMARIZING -> recoverActiveSubtaskPhase(subtask, SubtaskPhase.SUMMARIZING)
            SubtaskPhase.SUMMARY_WITH_QUESTIONS -> IssueProcessResult.Skipped(subtask.key, "waiting-for-user")
            SubtaskPhase.SUMMARIZED -> autoAdvanceSubtask(subtask, SubtaskPhase.SUMMARY_APPROVED)
            SubtaskPhase.SUMMARY_APPROVED -> advanceSubtaskChain(subtask)
            else -> IssueProcessResult.Skipped(subtask.key, "summary-subtask-unexpected:${phase.trackerValue}")
        }

    /**
     * Dispatch een subtask-agent op de gedeelde PARENT-branch: storyRun + concurrency
     * keyen op de parent, velden + result op de subtask zelf (`Subtask Phase`).
     */
    private fun dispatchSubtask(
        subtask: TrackerIssue,
        role: AgentRole,
        activePhase: SubtaskPhase,
        loopback: Boolean = false,
    ): IssueProcessResult {
        val parentKey = issueTrackerClient.parentStoryKey(subtask.key)
        if (parentKey == null) {
            val message = "[ORCHESTRATOR] Subtask zonder parent-story; kan geen gedeelde branch bepalen."
            issueTrackerClient.updateIssueFields(subtask.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
            return IssueProcessResult.Errored(subtask.key, message)
        }
        // Fase 6 — pauze/budget/fouten horen op story-niveau: check de parent.
        val parent = runCatching { issueTrackerClient.getIssue(parentKey) }.getOrNull()
        if (parent != null) {
            if (parent.fields.paused) {
                return IssueProcessResult.Skipped(subtask.key, "parent-paused")
            }
            if (!parent.fields.error.isNullOrBlank()) {
                return IssueProcessResult.Skipped(subtask.key, "parent-error")
            }
        }
        // Supplier erven van de parent als de subtask er zelf geen heeft.
        val effectiveSupplier = subtask.fields.aiSupplier?.takeIf { it.isNotBlank() && !it.equals("none", true) }
            ?: parent?.fields?.aiSupplier?.takeIf { it.isNotBlank() && !it.equals("none", true) }
        if (effectiveSupplier == null) {
            return IssueProcessResult.Skipped(subtask.key, "ai-supplier")
        }
        // Subtaken gebruiken de repo van hun parent-story (Repo-veld van de parent);
        // valt terug op het eigen Repo-veld als de parent (nog) niet leesbaar is.
        val targetRepo = projectRepoResolver.resolve(parent?.fields?.repo)
            ?: projectRepoResolver.resolve(subtask.fields.repo)
        return dispatcher.dispatch(
            issue = subtask,
            role = role,
            sourcePhase = null,
            phaseField = TrackerField.SUBTASK_PHASE,
            activePhaseValue = activePhase.trackerValue,
            storyRunKey = parentKey,
            loopbackCapped = loopback,
            budgetIssue = parent ?: subtask,
            parentContext = parent,
            targetRepo = targetRepo,
        )
    }

    /**
     * Recovery voor een subtask die in een actieve fase hangt. Draait er nog een agent
     * op de parent-branch → wachten; anders is 'ie waarschijnlijk gecrasht → de actieve
     * rol opnieuw dispatchen.
     */
    private fun recoverActiveSubtaskPhase(subtask: TrackerIssue, active: SubtaskPhase): IssueProcessResult {
        val parentKey = issueTrackerClient.parentStoryKey(subtask.key)
        val startedAt = subtask.fields.agentStartedAt
        val now = OffsetDateTime.now(clock)
        // Hard timeout (per subtask-run): hangende agent → permanente Error (stalt de keten).
        if (startedAt != null && startedAt.plus(settings.hardTimeout).isBefore(now)) {
            parentKey?.let { runCatching { agentRuntime.killForStory(it) } }
            val message = "[ORCHESTRATOR] Hard timeout: subtask hangt langer dan " +
                "${settings.hardTimeout.toMinutes()} minuten in ${active.trackerValue}."
            issueTrackerClient.updateIssueFields(subtask.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
            return IssueProcessResult.Errored(subtask.key, message)
        }
        if (parentKey != null && agentRuntime.isAnyAgentRunningForStory(parentKey)) {
            return IssueProcessResult.Skipped(subtask.key, "agent-running")
        }
        val role = active.activeRole
            ?: return IssueProcessResult.Skipped(subtask.key, "subtask-active-no-role")
        // Wacht tot de completion van de laatste run is verwerkt (endedAt gevuld) voordat we 'm
        // als 'hangend' beschouwen. De container kan al gestopt zijn terwijl de completion-poller
        // het resultaat nog niet heeft ingelezen; in dat gat is de YouTrack-fase nog "developing".
        if (parentKey != null) {
            val storyRun = storyRunRepository.openOrCreate(parentKey, subtask.fields.targetRepo.orEmpty())
            val latestRun = agentRunRepository.latestForRole(storyRun.id, role)
            if (latestRun != null && latestRun.endedAt == null) {
                return IssueProcessResult.Skipped(subtask.key, "awaiting-agent-completion")
            }
            // Net geëindigde run: de completion zet `endedAt` in de DB VÓÓRdat 'ie de nieuwe fase
            // naar YouTrack schrijft. Geef de completion daarom een grace ná endedAt.
            val endedAt = latestRun?.endedAt
            if (endedAt != null && endedAt.plus(settings.activePhaseRecoveryDelay).isAfter(now)) {
                return IssueProcessResult.Skipped(subtask.key, "awaiting-completion-settle")
            }
        }
        // Extra vangnet: een net-gestarte run nog even rust geven (tijd-grace vanaf start).
        if (startedAt != null && startedAt.plus(settings.activePhaseRecoveryDelay).isAfter(now)) {
            return IssueProcessResult.Skipped(subtask.key, "waiting-for-active-phase-recovery")
        }
        logger.warn("Recovery: subtask {} hangt in {}; herstart {}.", subtask.key, active.trackerValue, role.markerKeyPart)
        return dispatchSubtask(subtask, role, active)
    }

    private fun advanceSubtaskChain(finished: TrackerIssue): IssueProcessResult {
        val parentKey = issueTrackerClient.parentStoryKey(finished.key)
        // De afgeronde subtask heeft z'n eindfase bereikt → Done-lane.
        issueTrackerClient.transitionIssue(finished.key, STATE_DONE)
        if (parentKey == null) {
            return IssueProcessResult.Skipped(finished.key, "subtask-without-parent")
        }
        // De eerstvolgende nog-niet-gestarte/lopende subtaak: zet die op `start` zodat de
        // orchestrator 'm bij de volgende poll oppakt.
        val next = issueTrackerClient.subtasksOf(parentKey).firstOrNull { sibling ->
            sibling.key != finished.key &&
                SubtaskPhase.fromTracker(sibling.fields.subtaskPhase)?.isTerminal != true
        }
        when {
            // Volgende subtaak nog niet gestart → op `start` zetten. ALLEEN als z'n fase nog leeg is:
            // een terminale subtaak wordt elke poll opnieuw verwerkt, en zonder deze guard zou 'ie een
            // al-lopende volgende subtaak telkens terug op `start` zetten → eindeloze herstart-loop.
            next != null && next.fields.subtaskPhase.isNullOrBlank() ->
                issueTrackerClient.updateIssueFields(
                    next.key,
                    TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.START.trackerValue),
                )
            // Volgende loopt/wacht al → niets doen (geen reset).
            next != null -> Unit
            // Geen volgende non-terminal subtask meer → alle subtaken klaar → story Done.
            else -> issueTrackerClient.transitionIssue(parentKey, STATE_DONE)
        }
        return IssueProcessResult.Chained(finished.key, next?.key)
    }

    /**
     * SF-12 — auto-approve op subtask-niveau. De auto-approve-vlag staat op de PARENT-story, dus die
     * wordt via de parent gelezen. Bij aan zet dit het `Subtask Phase`-veld op het `*-approved`.
     */
    private fun autoAdvanceSubtask(subtask: TrackerIssue, approved: SubtaskPhase): IssueProcessResult {
        if (!autoApproveActive(subtask)) {
            return IssueProcessResult.Skipped(subtask.key, "waiting-for-approval")
        }
        issueTrackerClient.updateIssueFields(
            subtask.key,
            TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to approved.trackerValue),
        )
        logger.info("Auto-approve: subtask {} advanced naar {}.", subtask.key, approved.trackerValue)
        return IssueProcessResult.Recovered(subtask.key, approved.trackerValue)
    }

    /**
     * Auto-approve geldt centraal op de PARENT-story; een subtask zelf mag 'm ook gezet hebben.
     * Parent-lookup is best-effort: ontbreekt/faalt die, dan uit.
     */
    private fun autoApproveActive(subtask: TrackerIssue): Boolean {
        if (subtask.fields.autoApprove) {
            return true
        }
        val parentKey = issueTrackerClient.parentStoryKey(subtask.key) ?: return false
        return runCatching { issueTrackerClient.getIssue(parentKey).fields.autoApprove }.getOrDefault(false)
    }
}
