package nl.vdzon.softwarefactory.orchestrator.services

import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.orchestrator.AgentDispatchRequest
import nl.vdzon.softwarefactory.orchestrator.AgentFailurePolicy
import nl.vdzon.softwarefactory.orchestrator.AgentRunRecord
import nl.vdzon.softwarefactory.orchestrator.AgentRunRepository
import nl.vdzon.softwarefactory.orchestrator.AgentRuntime
import nl.vdzon.softwarefactory.orchestrator.AiPhase
import nl.vdzon.softwarefactory.orchestrator.StoryPhase
import nl.vdzon.softwarefactory.orchestrator.SubtaskPhase
import nl.vdzon.softwarefactory.orchestrator.services.AiRouting
import nl.vdzon.softwarefactory.orchestrator.CostMonitor
import nl.vdzon.softwarefactory.orchestrator.CreditsPauseCoordinator
import nl.vdzon.softwarefactory.orchestrator.IssueProcessResult
import nl.vdzon.softwarefactory.orchestrator.services.ManualCommandProcessor
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.orchestrator.OrchestratorPollResult
import nl.vdzon.softwarefactory.orchestrator.models.OrchestratorSettings
import nl.vdzon.softwarefactory.orchestrator.StoryRunRecord
import nl.vdzon.softwarefactory.orchestrator.StoryRunRepository
import nl.vdzon.softwarefactory.youtrack.AgentRole
import nl.vdzon.softwarefactory.youtrack.FactoryCommand
import nl.vdzon.softwarefactory.youtrack.IssueType
import nl.vdzon.softwarefactory.youtrack.SubtaskSpec
import nl.vdzon.softwarefactory.youtrack.SubtaskType
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.youtrack.TrackerComment
import nl.vdzon.softwarefactory.youtrack.TrackerFieldUpdate
import nl.vdzon.softwarefactory.youtrack.TrackerIssue
import nl.vdzon.softwarefactory.youtrack.TrackerField
import nl.vdzon.softwarefactory.youtrack.ProcessedCommentsApi
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.orchestrator.PreparedStoryWorkspace
import nl.vdzon.softwarefactory.orchestrator.StoryWorkspaceApi
import nl.vdzon.softwarefactory.support.SupportApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime

@Service
class OrchestratorService(
    private val issueTrackerClient: YouTrackApi,
    private val agentRuntime: AgentRuntime,
    private val storyRunRepository: StoryRunRepository,
    private val agentRunRepository: AgentRunRepository,
    private val pullRequestClient: GitHubApi,
    private val processedCommentService: ProcessedCommentsApi,
    private val previewApi: PreviewApi,
    private val storyWorkspaceService: StoryWorkspaceApi,
    private val costMonitor: CostMonitor,
    private val creditsPauseCoordinator: CreditsPauseCoordinator,
    private val manualCommandProcessor: ManualCommandProcessor,
    private val projectRepoResolver: ProjectRepoResolver,
    private val settings: OrchestratorSettings,
    private val clock: Clock,
    // Hard, synchroon opruimen van een hele story (zie purgeStory). Default-construct uit de
    // eigen deps, zodat bestaande directe constructie (tests) blijft compileren; Spring injecteert
    // de @Service-bean.
    private val storyPurgeService: StoryPurgeService = StoryPurgeService(
        issueTrackerClient = issueTrackerClient,
        agentRuntime = agentRuntime,
        storyRunRepository = storyRunRepository,
        pullRequestClient = pullRequestClient,
        previewApi = previewApi,
        storyWorkspaceService = storyWorkspaceService,
    ),
) : OrchestratorApi {
    private val logger = LoggerFactory.getLogger(javaClass)

    // YouTrack State-lanes (Agile-board kolommen) die de orchestrator zet bij voortgang.
    // Best-effort: projecten zonder deze State-waarde negeren de transitie (zie transitionIssue).
    private val STATE_IN_PROGRESS = "In Progress"
    private val STATE_DONE = "Done"

    override fun pollOnce(projectKey: String): OrchestratorPollResult {
        val t0 = System.nanoTime()
        val issues = issueTrackerClient.findWorkIssues()
        val t1 = System.nanoTime()
        val activeCreditsPause = creditsPauseCoordinator.activePause(OffsetDateTime.now(clock))
        if (activeCreditsPause != null) {
            return OrchestratorPollResult(issues.map { IssueProcessResult.Skipped(it.key, "credits-paused") })
        }
        val processed = issues.map { processIssue(it) }
        val t2 = System.nanoTime()
        val prResults = monitorPullRequests(issues.map { it.key }.toSet())
        val t3 = System.nanoTime()
        fun ms(from: Long, to: Long): Long = (to - from) / 1_000_000
        logger.info(
            "Poll-stappen: findWorkIssues={}ms, processIssues({})={}ms, prMonitor={}ms",
            ms(t0, t1),
            issues.size,
            ms(t1, t2),
            ms(t2, t3),
        )
        return OrchestratorPollResult(processed + prResults)
    }

    override fun processIssue(issue: TrackerIssue): IssueProcessResult {
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

        // Router op IssueType (afgeleid uit het `Type`-veld). v2 kent alleen nog de
        // Story Phase-refinementflow (story) en de subtask-pipeline (subtask); het
        // oude `AI Phase`-pad is in fase 7 verwijderd.
        return when (currentIssue.fields.issueType) {
            IssueType.STORY -> processStoryRefinement(currentIssue)
            IssueType.SUBTASK -> processSubtask(currentIssue)
        }
    }

    /**
     * Fase 5 — SubtaskExecutionCoordinator. Voert de subtask-pipeline uit per type
     * (development/review/test/manual/summary) op de gedeelde story-branch. Terminale
     * subtaken zetten de keten door (fase 4).
     */
    private fun processSubtask(subtask: TrackerIssue): IssueProcessResult {
        val type = SubtaskType.fromTracker(subtask.fields.subtaskType)
            ?: return IssueProcessResult.Skipped(subtask.key, "unknown-subtask-type")
        val phase = SubtaskPhase.fromTracker(subtask.fields.subtaskPhase)
        return when (type) {
            SubtaskType.MANUAL -> manualSubtask(subtask, phase)
            SubtaskType.DEVELOPMENT -> developmentSubtask(subtask, phase)
            SubtaskType.REVIEW -> reviewSubtask(subtask, phase)
            SubtaskType.TEST -> testSubtask(subtask, phase)
            SubtaskType.SUMMARY -> summarySubtask(subtask, phase)
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
        return dispatchIfAllowed(
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
        // Een tijd-grace vanaf start dekt dit niet voor agents die langer draaien dan de grace,
        // dus leunen we — net als recoverActiveStoryPhase — op endedAt van de laatste run.
        if (parentKey != null) {
            val storyRun = storyRunRepository.openOrCreate(parentKey, subtask.fields.targetRepo.orEmpty())
            val latestRun = agentRunRepository.latestForRole(storyRun.id, role)
            if (latestRun != null && latestRun.endedAt == null) {
                return IssueProcessResult.Skipped(subtask.key, "awaiting-agent-completion")
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
        // orchestrator 'm bij de volgende poll oppakt (vervangt het oude ai-development-label).
        val next = issueTrackerClient.subtasksOf(parentKey).firstOrNull { sibling ->
            sibling.key != finished.key &&
                SubtaskPhase.fromTracker(sibling.fields.subtaskPhase)?.isTerminal != true
        }
        if (next != null) {
            issueTrackerClient.updateIssueFields(
                next.key,
                TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.START.trackerValue),
            )
        } else {
            // Geen volgende non-terminal subtask meer → alle subtaken klaar → story Done.
            issueTrackerClient.transitionIssue(parentKey, STATE_DONE)
        }
        return IssueProcessResult.Chained(finished.key, next?.key)
    }

    /**
     * SF-12 — auto-approve op story-niveau. Bij auto-approve=aan advancet een
     * `*-ed`-status (REFINED/PLANNED) direct naar het bijbehorende `*-approved`
     * i.p.v. op de gebruiker te wachten. Default uit = bestaand gedrag (waiting).
     */
    private fun autoAdvanceStory(issue: TrackerIssue, approved: StoryPhase): IssueProcessResult {
        if (!issue.fields.autoApprove) {
            return IssueProcessResult.Skipped(issue.key, "waiting-for-approval")
        }
        issueTrackerClient.updateIssueFields(
            issue.key,
            TrackerFieldUpdate.of(TrackerField.STORY_PHASE to approved.trackerValue),
        )
        logger.info("Auto-approve: story {} advanced naar {}.", issue.key, approved.trackerValue)
        return IssueProcessResult.Recovered(issue.key, approved.trackerValue)
    }

    /**
     * SF-12 — auto-approve op subtask-niveau. De auto-approve-vlag staat op de
     * PARENT-story (zoals de supplier-inheritance), dus die wordt via de parent
     * gelezen. Bij aan zet dit het `Subtask Phase`-veld op het `*-approved` zodat
     * de bestaande approved-tak de keten oppakt. Default uit = waiting-for-approval.
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
     * Auto-approve geldt centraal op de PARENT-story; een subtask zelf mag 'm ook
     * gezet hebben. Parent-lookup is best-effort: ontbreekt/faalt die, dan uit.
     */
    private fun autoApproveActive(subtask: TrackerIssue): Boolean {
        if (subtask.fields.autoApprove) {
            return true
        }
        val parentKey = issueTrackerClient.parentStoryKey(subtask.key) ?: return false
        return runCatching { issueTrackerClient.getIssue(parentKey).fields.autoApprove }.getOrDefault(false)
    }

    /**
     * Fase 2a — refine-stap van de StoryRefinementCoordinator op het
     * `Story Phase`-veld. Planner (plan-stap) volgt in fase 2b.
     */
    private fun processStoryRefinement(issue: TrackerIssue): IssueProcessResult =
        when (StoryPhase.fromTracker(issue.fields.storyPhase)) {
            // Lege fase = nog niet starten; pas bij fase `start` pakt de orchestrator 'm op.
            null -> IssueProcessResult.Skipped(issue.key, "not-started")
            StoryPhase.START,
            StoryPhase.QUESTIONS_ANSWERED,
            StoryPhase.REFINED_REJECTED,
            -> dispatchIfAllowed(
                issue,
                AgentRole.REFINER,
                sourcePhase = null,
                phaseField = TrackerField.STORY_PHASE,
                activePhaseValue = StoryPhase.REFINING.trackerValue,
            )
            StoryPhase.REFINED_WITH_QUESTIONS -> IssueProcessResult.Skipped(issue.key, "waiting-for-user")
            StoryPhase.REFINED -> autoAdvanceStory(issue, StoryPhase.REFINED_APPROVED)
            StoryPhase.REFINING -> recoverActiveStoryPhase(issue, StoryPhase.REFINING)
            // Plan-stap (fase 2b): refined-approved start de planner.
            StoryPhase.REFINED_APPROVED,
            StoryPhase.PLANNING_QUESTIONS_ANSWERED,
            StoryPhase.PLANNING_REJECTED,
            -> dispatchIfAllowed(
                issue,
                AgentRole.PLANNER,
                sourcePhase = null,
                phaseField = TrackerField.STORY_PHASE,
                activePhaseValue = StoryPhase.PLANNING.trackerValue,
            )
            StoryPhase.PLANNED_WITH_QUESTIONS -> IssueProcessResult.Skipped(issue.key, "waiting-for-user")
            StoryPhase.PLANNED -> autoAdvanceStory(issue, StoryPhase.PLANNING_APPROVED)
            StoryPhase.PLANNING -> recoverActiveStoryPhase(issue, StoryPhase.PLANNING)
            // Terminaal: refinement klaar, orchestrator laat de story los (development = tag-gedreven).
            StoryPhase.PLANNING_APPROVED -> IssueProcessResult.Skipped(issue.key, "refinement-done")
        }

    override fun queueCommand(storyKey: String, command: FactoryCommand) {
        issueTrackerClient.postComment(storyKey, "@factory:command:${command.token}")
    }

    override fun purgeStory(storyKey: String) {
        storyPurgeService.purgeStory(storyKey)
    }

    private fun dispatchIfAllowed(
        issue: TrackerIssue,
        role: AgentRole,
        sourcePhase: AiPhase?,
        phaseField: TrackerField = TrackerField.AI_PHASE,
        activePhaseValue: String = AiPhase.activeFor(role).trackerValue,
        // Fase 5/6 — voor subtaken draait de agent op de PARENT-branch: storyRun +
        // concurrency-guard keyen op de parent, terwijl velden + result op de subtask
        // (issue.key) blijven. `loopbackCapped` markeert een subtask-fix-developer.
        storyRunKey: String = issue.key,
        loopbackCapped: Boolean = false,
        // Fase 6 — budget hoort op story-niveau: subtaken geven de parent mee.
        budgetIssue: TrackerIssue = issue,
        // Fase 6 — parent story-tekst als extra context voor subtask-agents.
        parentContext: TrackerIssue? = null,
        // De repo wordt afgeleid uit het `Repo`-veld (story) of dat van de parent (subtask),
        // via ProjectRepoResolver. Door de caller meegegeven; null = geen geldige repo → Error.
        targetRepo: String? = projectRepoResolver.resolve(issue.fields.repo),
    ): IssueProcessResult {
        if (targetRepo.isNullOrBlank()) {
            val message = "[ORCHESTRATOR] Geen repo: vul het `Repo`-veld met een projectnaam uit projects.yaml " +
                "of een repo-URL (subtaken erven de repo van hun parent-story). Leeg `Error` om opnieuw te proberen."
            issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
            return IssueProcessResult.Errored(issue.key, message)
        }

        val storyRun = storyRunRepository.openOrCreate(storyRunKey, targetRepo)
        val budgetResult = costMonitor.checkBudget(budgetIssue, storyRun)
        if (budgetResult.paused) {
            return IssueProcessResult.Skipped(issue.key, "budget-exceeded")
        }

        if (role == AgentRole.DEVELOPER && (sourcePhase.isDeveloperLoopbackPhase() || loopbackCapped)) {
            // De loopback-cap geldt per werk-eenheid. Voor een subtaak (issue.key != storyRunKey)
            // tellen we alleen díé subtaak; anders zou een story met meerdere subtaken het budget
            // delen en zou de eerste reject-loopback al door de cap knallen. Story-niveau telt breed.
            val developerRuns = if (issue.key != storyRunKey) {
                agentRunRepository.countForRoleAndSubtask(storyRun.id, AgentRole.DEVELOPER, issue.key)
            } else {
                agentRunRepository.countForRole(storyRun.id, AgentRole.DEVELOPER)
            }
            val maxDeveloperLoopbacks = issue.fields.developerLoopbackLimit(settings.maxDeveloperLoopbacks)
            if (developerRuns >= maxDeveloperLoopbacks + 1) {
                val message = "[ORCHESTRATOR] Developer-loopback cap bereikt (${maxDeveloperLoopbacks}x). " +
                    "Handmatige triage nodig. Geef feedback en leeg `Error` om opnieuw te proberen, " +
                    "of zet `Paused = true` en parkeer dit ticket."
                issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
                return IssueProcessResult.Errored(issue.key, message)
            }
        }

        if (!canDispatch(storyRunKey, role)) {
            return IssueProcessResult.Skipped(issue.key, "concurrency-cap")
        }

        val startedAt = OffsetDateTime.now(clock)
        issueTrackerClient.updateIssueFields(
            issue.key,
            TrackerFieldUpdate.of(
                phaseField to activePhaseValue,
                TrackerField.AGENT_STARTED_AT to startedAt,
            ),
        )
        // Een agent gaat dit issue (story of subtask) actief verwerken → In Progress-lane.
        issueTrackerClient.transitionIssue(issue.key, STATE_IN_PROGRESS)

        return try {
            val workspace = storyWorkspaceService.prepare(storyRun, role)
            storyWorkspaceService.ensureStoryWorklog(storyRun, issue.summary, issue.description)
            storyRunRepository.updateWorkspace(
                storyRunId = storyRun.id,
                workspacePath = workspace.workspacePath.toString(),
                branchName = workspace.branchName,
                baseBranch = workspace.baseBranch,
                branchPrefix = workspace.branchPrefix,
                previewUrlTemplate = workspace.deploymentConfig.previewUrlTemplate,
                previewNamespaceTemplate = workspace.deploymentConfig.previewNamespaceTemplate,
                previewDbSecretRecipe = workspace.deploymentConfig.previewDbSecretRecipe,
            )
            postWorkspaceLinkIfNew(issue.key, storyRun, workspace)
            val request = dispatchRequest(
                issue = issue,
                targetRepo = targetRepo,
                storyRun = storyRun,
                workspace = workspace,
                role = role,
                activePhaseValue = activePhaseValue,
                sourcePhase = sourcePhase,
                parentContext = parentContext,
            )

            logger.info(
                "Starting agent dispatch: story={} role={} storyRunId={} sourcePhase={} targetPhase={} supplier={} level={} model={} targetRepo={} prNumber={} branch={} workspace={}",
                issue.key,
                role.markerKeyPart,
                storyRun.id,
                sourcePhase?.trackerValue ?: "<empty>",
                activePhaseValue,
                request.aiSupplier?.takeIf { it.isNotBlank() } ?: "<unset>",
                request.aiLevel ?: "<unset>",
                request.aiModel?.takeIf { it.isNotBlank() } ?: "<default>",
                SupportApi.default().redact(targetRepo),
                storyRun.prNumber ?: "<none>",
                workspace.branchName,
                workspace.workspacePath,
            )
            val dispatch = agentRuntime.dispatch(request)
            val agentRunId = agentRunRepository.recordStarted(
                storyRunId = storyRun.id,
                role = role,
                containerName = dispatch.containerName,
                model = request.aiModel,
                effort = request.aiEffort,
                level = request.aiLevel,
                workspacePath = dispatch.workspacePath,
                // Voor subtaken (storyRun keyt op de parent) → markeer de run met de subtask-key.
                subtaskKey = issue.key.takeIf { storyRunKey != issue.key },
            )
            logger.info(
                "Agent started: story={} role={} agentRunId={} storyRunId={} container={} workspace={} phase={} supplier={} level={} model={}",
                issue.key,
                role.markerKeyPart,
                agentRunId,
                storyRun.id,
                dispatch.containerName,
                dispatch.workspacePath ?: "<unknown>",
                activePhaseValue,
                request.aiSupplier?.takeIf { it.isNotBlank() } ?: "<unset>",
                request.aiLevel ?: "<unset>",
                request.aiModel?.takeIf { it.isNotBlank() } ?: "<default>",
            )
            runCatching {
                agentRuntime.captureLogs(dispatch.containerName, agentRunId)
            }.onFailure { exception ->
                logger.warn("Agent log capture could not be started for {}", dispatch.containerName, exception)
            }
            IssueProcessResult.Dispatched(issue.key, role, dispatch.containerName)
        } catch (exception: Exception) {
            val message = "[ORCHESTRATOR] Agent dispatch voor ${role.markerKeyPart} faalde: ${exception.message}"
            logger.warn("Agent dispatch failed for {} {}", issue.key, role, exception)
            issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
            IssueProcessResult.Errored(issue.key, message)
        }
    }

    private fun postWorkspaceLinkIfNew(storyKey: String, storyRun: StoryRunRecord, workspace: PreparedStoryWorkspace) {
        if (!storyRun.workspacePath.isNullOrBlank()) {
            return
        }
        val repoRoot = workspace.repoRoot.toAbsolutePath().normalize()
        val message = """
        [ORCHESTRATOR] Work folder aangemaakt:
        - Repo: [$repoRoot](${repoRoot.toUri()})
        - Open in IntelliJ: `open -a "IntelliJ IDEA" "$repoRoot"`
        """.trimIndent()
        runCatching {
            issueTrackerClient.postComment(storyKey, message)
        }.onFailure { exception ->
            logger.warn("Could not post workspace link for {}", storyKey, exception)
        }
    }

    private fun dispatchRequest(
        issue: TrackerIssue,
        targetRepo: String,
        storyRun: StoryRunRecord,
        workspace: PreparedStoryWorkspace,
        role: AgentRole,
        activePhaseValue: String,
        sourcePhase: AiPhase?,
        parentContext: TrackerIssue? = null,
    ): AgentDispatchRequest {
        val previewUrl = previewApi.render(workspace.deploymentConfig.previewUrlTemplate, storyRun.prNumber)
        val previewNamespace = previewApi.render(workspace.deploymentConfig.previewNamespaceTemplate, storyRun.prNumber)
        val prCommentContext = prCommentContext(storyRun, role, sourcePhase)
        // Subtaken erven supplier/model/effort van de parent-story als ze zelf leeg zijn.
        val supplier = issue.fields.aiSupplier?.takeIf { it.isNotBlank() }
            ?: parentContext?.fields?.aiSupplier?.takeIf { it.isNotBlank() }
        val model = issue.fields.aiModel?.takeIf { it.isNotBlank() }
            ?: parentContext?.fields?.aiModel?.takeIf { it.isNotBlank() }
        val effort = issue.fields.aiReasoningEffort?.takeIf { it.isNotBlank() }
            ?: parentContext?.fields?.aiReasoningEffort?.takeIf { it.isNotBlank() }
        val aiRoute = AiRouting.resolve(issue.fields.aiLevel, supplier, role)
        return AgentDispatchRequest(
            storyKey = issue.key,
            serializationKey = storyRun.storyKey,
            targetRepo = targetRepo,
            storyRunId = storyRun.id,
            workspacePath = workspace.workspacePath.toString(),
            branchName = workspace.branchName,
            role = role,
            phase = activePhaseValue,
            baseBranch = workspace.baseBranch,
            branchPrefix = workspace.branchPrefix,
            prNumber = storyRun.prNumber,
            previewUrl = previewUrl,
            previewNamespace = previewNamespace,
            developerLoopbackReason = sourcePhase.developerLoopbackReason(),
            agentMode = "comment".takeIf { prCommentContext != null },
            trackerContext = trackerContext(issue, role, parentContext),
            prCommentContext = prCommentContext,
            aiLevel = aiRoute.level,
            aiSupplier = supplier,
            // Per-subtask model/effort (planner-keuze) gaat voor; anders parent, anders routing.
            aiModel = model ?: aiRoute.model,
            aiEffort = effort ?: aiRoute.effort,
        )
    }

    private fun trackerContext(issue: TrackerIssue, role: AgentRole, parentContext: TrackerIssue? = null): String =
        buildString {
            appendLine("## Issue Context")
            appendLine()
            appendLine("- Key: `${issue.key}`")
            appendLine("- Summary: ${issue.summary}")
            appendLine("- Status: ${issue.status}")
            appendLine("- Project: `${issue.projectKey}`")
            issue.fields.subtaskType?.let { appendLine("- Subtask Type: `$it`") }
            issue.fields.aiSupplier?.let { appendLine("- AI Supplier: `$it`") }
            issue.fields.aiLevel?.let { appendLine("- AI Level: `$it`") }
            // Fase 6 — subtask-agent krijgt de (gerefinede) parent story-tekst mee.
            parentContext?.let { parent ->
                appendLine()
                appendLine("### Parent Story (`${parent.key}`): ${parent.summary}")
                appendLine()
                appendLine(parent.description?.trim()?.takeIf { it.isNotBlank() } ?: "Geen parent-description.")
            }
            appendLine()
            appendLine("### Description")
            appendLine()
            appendLine(issue.description?.trim()?.takeIf { it.isNotBlank() } ?: "Geen issue tracker-description gevonden.")
            appendLine()
            appendLine("### Relevant Issue Comments")
            appendLine()
            val comments = issueTrackerClient.taskComments(issue, role) { comment, commentRole ->
                processedCommentService.isProcessed(issue.key, comment.id, commentRole)
            }
            if (comments.isEmpty()) {
                appendLine("Geen nieuwe relevante comments voor deze rol.")
            } else {
                comments.forEach { comment ->
                    appendLine(comment.toTaskMarkdown())
                    appendLine()
                }
            }
        }.trimEnd()

    private fun prCommentContext(storyRun: StoryRunRecord, role: AgentRole, sourcePhase: AiPhase?): String? {
        if (role != AgentRole.DEVELOPER || sourcePhase != AiPhase.TESTED_WITH_FEEDBACK_FOR_DEVELOPER) {
            return null
        }
        val prNumber = storyRun.prNumber ?: return null
        val comments = pullRequestClient.claimedFactoryComments(storyRun.targetRepo, prNumber)
        if (comments.isEmpty()) {
            return null
        }
        return buildString {
            appendLine("## PR Comment Task Bundle")
            appendLine()
            appendLine("Verwerk onderstaande `@factory` PR-comments op dezelfde branch en PR.")
            appendLine()
            comments.forEach { comment ->
                appendLine("### PR comment ${comment.id}")
                appendLine()
                appendLine(comment.body.trim())
                appendLine()
            }
        }.trimEnd()
    }

    private fun canDispatch(storyKey: String, role: AgentRole): Boolean {
        if (agentRuntime.isAnyAgentRunningForStory(storyKey)) {
            return false
        }
        if (agentRuntime.runningCount(role) >= settings.maxParallelFor(role)) {
            return false
        }
        return agentRuntime.runningCount(null) < settings.maxParallelTotal
    }

    private fun monitorPullRequests(activeAiStoryKeys: Set<String>): List<IssueProcessResult> =
        storyRunRepository.activePullRequests()
            .filter { it.storyKey in activeAiStoryKeys }
            .mapNotNull { run ->
                runCatching {
                    monitorPullRequest(run)
                }.onFailure { exception ->
                    logger.warn("PR monitor failed for {}", run.storyKey, exception)
                }.getOrNull()
            }

    private fun monitorPullRequest(run: StoryRunRecord): IssueProcessResult? {
        val prNumber = run.prNumber ?: return null
        if (pullRequestClient.isMerged(run.targetRepo, prNumber)) {
            cleanupPreviewNamespace(run)
            issueTrackerClient.transitionIssue(run.storyKey, "Done")
            storyRunRepository.close(run.id, "merged", OffsetDateTime.now(clock))
            return IssueProcessResult.Merged(run.storyKey, prNumber)
        }

        if (agentRuntime.isAnyAgentRunningForStory(run.storyKey)) {
            return null
        }

        val comments = pullRequestClient.unprocessedFactoryComments(run.targetRepo, prNumber)
        if (comments.isEmpty()) {
            return null
        }
        comments.forEach { comment -> pullRequestClient.markCommentClaimed(run.targetRepo, comment.id) }
        // Fase 7 — v2: late PR-feedback wordt een nieuwe development-subtask op de story
        // (i.p.v. een story-phase-reset). De subtask krijgt meteen fase `start` zodat de keten 'm oppakt.
        val supplier = runCatching { issueTrackerClient.getIssue(run.storyKey).fields.aiSupplier }.getOrNull()
        val description = comments.joinToString("\n\n") { "- ${it.body}" }
        val subtask = issueTrackerClient.createSubtask(
            run.storyKey,
            SubtaskSpec(
                type = SubtaskType.DEVELOPMENT,
                title = "PR-feedback verwerken (PR #$prNumber)",
                description = "Verwerk de volgende PR-commentaren op de gedeelde branch:\n\n$description",
            ),
            supplier = supplier,
        )
        issueTrackerClient.updateIssueFields(
            subtask.key,
            TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.START.trackerValue),
        )
        return IssueProcessResult.PrCommentTriggered(run.storyKey, prNumber, comments.size)
    }

    private fun cleanupPreviewNamespace(run: StoryRunRecord): Boolean {
        val namespace = previewApi.render(run.previewNamespaceTemplate, run.prNumber) ?: return false
        return previewApi.cleanup(namespace)
    }

    /**
     * Recovery voor een actieve `Story Phase` (refining/planning).
     */
    private fun recoverActiveStoryPhase(issue: TrackerIssue, phase: StoryPhase): IssueProcessResult {
        val role = requireNotNull(phase.activeRole)
        if (agentRuntime.isAgentRunning(issue.key, role)) {
            return IssueProcessResult.Skipped(issue.key, "agent-running")
        }

        val storyRun = storyRunRepository.openOrCreate(issue.key, issue.fields.targetRepo.orEmpty())
        val latestRun = agentRunRepository.latestForRole(storyRun.id, role)
        val startedAt = issue.fields.agentStartedAt
        val now = OffsetDateTime.now(clock)

        // Default-eindstatus bij succes en de reset-status bij retry, per stap.
        val completedDefault = if (phase == StoryPhase.PLANNING) StoryPhase.PLANNED else StoryPhase.REFINED
        // Re-dispatch via de status die de betreffende agent opnieuw start:
        // refining -> leeg (refiner), planning -> refined-approved (planner).
        val retryReset: String? = if (phase == StoryPhase.PLANNING) StoryPhase.REFINED_APPROVED.trackerValue else null

        if (startedAt != null && startedAt.plus(settings.hardTimeout).isBefore(now)) {
            val message = "[ORCHESTRATOR] Hard timeout: ${phase.trackerValue} loopt langer dan ${settings.hardTimeout.toMinutes()} minuten zonder voortgang."
            issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
            return IssueProcessResult.Errored(issue.key, message)
        }

        if (latestRun != null && latestRun.endedAt == null) {
            return IssueProcessResult.Skipped(issue.key, "awaiting-agent-completion")
        }

        if (latestRun != null && latestRun.isSuccessful()) {
            // Leid de eindfase af zonder een vraag-uitkomst plat te slaan: de agent-run bewaart alleen
            // een outcome-token ("ok"/"questions"), geen StoryPhase. Een 'questions'-outcome moet naar de
            // bijbehorende `*-with-questions`-fase (anders verdwijnt de vraag en krijg je een approve).
            val asksQuestion = latestRun.outcome?.contains("question", ignoreCase = true) == true
            val completed = StoryPhase.fromTracker(latestRun.outcome)?.takeUnless { it.isActive }
                ?: when {
                    asksQuestion && phase == StoryPhase.PLANNING -> StoryPhase.PLANNED_WITH_QUESTIONS
                    asksQuestion && phase == StoryPhase.REFINING -> StoryPhase.REFINED_WITH_QUESTIONS
                    else -> completedDefault
                }
            issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.STORY_PHASE to completed.trackerValue))
            return IssueProcessResult.Recovered(issue.key, completed.trackerValue)
        }

        if (latestRun != null && latestRun.isRetryableFailure()) {
            val transientFailures = agentRunRepository.recentForRole(
                storyRun.id,
                role,
                settings.maxTransientRetries + 1,
            ).takeWhile { it.isRetryableFailure() }.size

            if (transientFailures <= settings.maxTransientRetries) {
                issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.STORY_PHASE to retryReset))
                return IssueProcessResult.Recovered(issue.key, retryReset ?: "<empty>")
            }

            val message = "[ORCHESTRATOR] Transient retry cap bereikt (${settings.maxTransientRetries}x) voor ${role.markerKeyPart}; handmatige triage nodig."
            issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
            return IssueProcessResult.Errored(issue.key, message)
        }

        if (startedAt != null && startedAt.plus(settings.activePhaseRecoveryDelay).isAfter(now)) {
            return IssueProcessResult.Skipped(issue.key, "waiting-for-active-phase-recovery")
        }

        issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.STORY_PHASE to retryReset))
        return IssueProcessResult.Recovered(issue.key, retryReset ?: "<empty>")
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

    private fun AiPhase?.isDeveloperLoopbackPhase(): Boolean =
        this == AiPhase.REVIEWED_WITH_FEEDBACK_FOR_DEVELOPER || this == AiPhase.TESTED_WITH_FEEDBACK_FOR_DEVELOPER

    private fun AiPhase?.developerLoopbackReason(): String? =
        when (this) {
            AiPhase.REVIEWED_WITH_FEEDBACK_FOR_DEVELOPER -> "Lees eerst het laatste [REVIEWER]-comment en verwerk die feedback op dezelfde branch en PR."
            AiPhase.TESTED_WITH_FEEDBACK_FOR_DEVELOPER -> "Lees eerst het laatste [TESTER]-comment en verwerk die feedback op dezelfde branch en PR."
            else -> null
        }

    private fun TrackerComment.toTaskMarkdown(): String =
        buildString {
            appendLine("#### Issue comment $id")
            authorDisplayName?.takeIf { it.isNotBlank() }?.let { appendLine("- Author: $it") }
            created?.let { appendLine("- Created: `$it`") }
            appendLine()
            appendLine(body.trim())
        }.trimEnd()

    private fun AgentRunRecord.isSuccessful(): Boolean =
        endedAt != null && outcome?.contains("error", ignoreCase = true) != true && outcome?.contains("failed", ignoreCase = true) != true

    private fun AgentRunRecord.isRetryableFailure(): Boolean =
        AgentFailurePolicy.isRetryable(outcome, summaryText)
}
