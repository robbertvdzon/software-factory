package nl.vdzon.softwarefactory.pipeline.service

import nl.vdzon.softwarefactory.core.BoardState
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.AgentRunRepository
import nl.vdzon.softwarefactory.core.AgentRuntime
import nl.vdzon.softwarefactory.core.ErrorCategory
import nl.vdzon.softwarefactory.core.HumanActionPolicy
import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.core.OrchestratorSettings
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.SubtaskType
import nl.vdzon.softwarefactory.core.StoryRunRepository
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.tracker.TrackerApi
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
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
    private val issueTrackerClient: TrackerApi,
    private val agentRuntime: AgentRuntime,
    private val storyRunRepository: StoryRunRepository,
    private val agentRunRepository: AgentRunRepository,
    private val projectRepoResolver: ProjectRepoResolver,
    private val settings: OrchestratorSettings,
    private val clock: Clock,
    private val dispatcher: AgentDispatcher,
    // Gewone Spring-beans: de vroegere constructor-cycle (advanceChain-callback in de
    // handler-constructors) is opgelost door de callback per process-aanroep mee te geven.
    private val mergeHandler: MergeSubtaskHandler,
    private val deployHandler: DeploySubtaskHandler,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // tracker State-lanes (zie core.BoardState): afgerond → Done; een manual-approve-reject
    // zet alle subtaken terug in de todo-kolom.
    private val stateDone = BoardState.DONE.laneName
    private val stateTodo = BoardState.TODO.laneName

    fun processSubtask(subtask: TrackerIssue): IssueProcessResult {
        val type = SubtaskType.fromTracker(subtask.fields.subtaskType)
            ?: return IssueProcessResult.Skipped(subtask.key, "unknown-subtask-type")
        val phase = SubtaskPhase.fromTracker(subtask.fields.subtaskPhase)
        return when (type) {
            SubtaskType.MANUAL -> manualSubtask(subtask, phase)
            SubtaskType.MANUAL_APPROVE -> manualApproveSubtask(subtask, phase)
            SubtaskType.DEVELOPMENT -> developmentSubtask(subtask, phase)
            SubtaskType.REVIEW -> reviewSubtask(subtask, phase)
            SubtaskType.TEST -> testSubtask(subtask, phase)
            SubtaskType.SUMMARY -> summarySubtask(subtask, phase)
            SubtaskType.DOCUMENTATION -> documentationSubtask(subtask, phase)
            SubtaskType.MERGE -> mergeHandler.process(subtask, phase, ::advanceSubtaskChain)
            SubtaskType.DEPLOY -> deployHandler.process(subtask, phase, ::advanceSubtaskChain)
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

    /**
     * SF-192 — handmatige goedkeur-poort (geen agent). Analoog aan [manualSubtask]: op `start` zetten
     * we 'm op `manual-approve-needed` en wachten op een mens (approve/reject-commando). Approve laat de
     * keten doorlopen; reject reset de hele story-keten.
     */
    private fun manualApproveSubtask(subtask: TrackerIssue, phase: SubtaskPhase?): IssueProcessResult =
        when (phase) {
            null -> IssueProcessResult.Skipped(subtask.key, "not-started")
            SubtaskPhase.START -> {
                issueTrackerClient.updateIssueFields(
                    subtask.key,
                    TrackerFieldUpdate.of(
                        TrackerField.SUBTASK_PHASE to SubtaskPhase.MANUAL_APPROVE_NEEDED.trackerValue,
                    ),
                )
                IssueProcessResult.Recovered(subtask.key, SubtaskPhase.MANUAL_APPROVE_NEEDED.trackerValue)
            }
            SubtaskPhase.MANUAL_APPROVE_NEEDED -> IssueProcessResult.Skipped(subtask.key, "waiting-for-user")
            SubtaskPhase.MANUALLY_APPROVED -> advanceSubtaskChain(subtask)
            SubtaskPhase.MANUALLY_NOT_APPROVED -> resetStoryChainAfterRejection(subtask)
            else -> IssueProcessResult.Skipped(subtask.key, "manual-approve-unexpected:${phase.trackerValue}")
        }

    /**
     * SF-192 — afkeuren via de poort: zet ALLE subtaken van de story terug naar todo (Subtask Phase
     * leeg + State-lane → todo), inclusief de manual-approve-subtaak zelf, en (her)start de keten door
     * de eerste subtaak op `start` te zetten. De afkeurreden is door het reject-commando al in de
     * story-description gezet. Idempotent: na de reset is de manual-approve-fase leeg, dus de volgende
     * poll triggert de reset niet opnieuw (geen herstart-loop).
     */
    private fun resetStoryChainAfterRejection(rejected: TrackerIssue): IssueProcessResult {
        val parentKey = issueTrackerClient.parentStoryKey(rejected.key)
            ?: return IssueProcessResult.Skipped(rejected.key, "subtask-without-parent")
        val subtasks = runCatching { issueTrackerClient.subtasksOf(parentKey) }
            .getOrElse {
                logger.warn("Manual-approve reset: kon subtaken van {} niet laden.", parentKey, it)
                return IssueProcessResult.Skipped(rejected.key, "reset-load-failed")
            }
        // Alle subtaken (incl. de manual-approve-poort zelf) → fase leeg + todo-lane.
        subtasks.forEach { sub ->
            issueTrackerClient.updateIssueFields(sub.key, TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to null))
            issueTrackerClient.transitionIssue(sub.key, stateTodo)
        }
        // Story zelf ook terug in de todo-lane: 'ie stond op Done/in-progress en moet opnieuw lopen.
        issueTrackerClient.transitionIssue(parentKey, stateTodo)
        // De eerste subtaak weer op `start` zodat de keten opnieuw begint.
        subtasks.firstOrNull()?.let { first ->
            issueTrackerClient.updateIssueFields(
                first.key,
                TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.START.trackerValue),
            )
        }
        logger.info(
            "Manual-approve reject: story {} volledig gereset ({} subtaken naar todo).",
            parentKey,
            subtasks.size,
        )
        return IssueProcessResult.Chained(rejected.key, subtasks.firstOrNull()?.key)
    }

    /**
     * SF-200 — een test-bevinding (`test-rejected`) test alleen en oordeelt; de tester doet zelf
     * geen gerichte fix meer. In plaats van een DEVELOPER-loopback resetten we de hele subtaak-keten
     * (identiek aan een handmatige reject): (1) de testreden van de laatste TESTER-run komt in een
     * herhaalbaar te overschrijven, gemarkeerd blok in de parent-story-description zodat develop/
     * review/test die bij de herstart meekrijgen, en (2) [resetStoryChainAfterRejection] zet de keten
     * terug op start (zelfde branch). Een cap voorkomt oneindig herstarten: is die bereikt, dan geen
     * reset maar de test-subtaak in `Error` (handmatige triage), analoog aan de developer-loopback-cap.
     *
     * Idempotent t.o.v. de poll: na de reset is deze test-subtaak fase-leeg, dus de volgende poll
     * triggert geen nieuwe reset.
     */
    private fun handleTestRejection(subtask: TrackerIssue): IssueProcessResult {
        val parentKey = issueTrackerClient.parentStoryKey(subtask.key)
            ?: return IssueProcessResult.Skipped(subtask.key, "subtask-without-parent")
        val storyRun = storyRunRepository.openOrCreate(parentKey, subtask.fields.targetRepo.orEmpty())
        // De cap telt het aantal TESTER-runs op de gedeelde story-run: elke (afgekeurde) test-run die
        // tot een reset leidde laat een TESTER-run achter, dus dat is de teller voor uitgevoerde resets.
        // Net als de developer-loopback-cap mag de N-de reset nog, pas de (N+1)-de wordt geblokkeerd.
        val testerRuns = agentRunRepository.countForRole(storyRun.id, AgentRole.TESTER)
        if (testerRuns >= settings.maxTestChainResets + 1) {
            // BELANGRIJK: anders dan de developer-loopback-cap kent de test-cap GEEN resume-increment.
            // De teller is `countForRole(storyRun.id, TESTER)` op de persistente story-run en daalt niet
            // door `Error` te legen. Alleen het Error-veld leegmaken — terwijl de fase `test-rejected`
            // blijft en de teller ≥ cap+1 staat — loopt op de eerstvolgende poll direct opnieuw in deze
            // cap (re-error-loop). De melding wijst daarom op de wél werkende herstelpaden.
            val message = "[ORCHESTRATOR] Test-chain reset cap bereikt (${settings.maxTestChainResets}x). " +
                "Handmatige triage nodig. Let op: de TESTER-teller staat op de gedeelde story-run en de " +
                "test-cap heeft geen resume-increment, dus alleen `Error` legen herstart niets (de " +
                "volgende poll loopt meteen opnieuw in deze cap). Werkende opties: zet `Paused = true` en " +
                "parkeer dit ticket, of `re-implement` de story zodat een verse story-run de teller reset."
            // Geen reset meer. Error op de test-subtaak zelf (net als de developer-loopback-cap): de
            // top-level error-guard skipt 'm daarna elke poll, dus de keten stalt netjes en idempotent
            // tot een mens ingrijpt. De error surfacet op het storyscherm als subtaak-fout.
            issueTrackerClient.updateIssueFields(subtask.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
            logger.warn("Test-chain reset cap bereikt voor story {} ({} TESTER-runs).", parentKey, testerRuns)
            return IssueProcessResult.Errored(subtask.key, message)
        }
        val reason = agentRunRepository.latestForRole(storyRun.id, AgentRole.TESTER)?.summaryText
        writeTestFeedbackToStory(parentKey, reason)
        return resetStoryChainAfterRejection(subtask)
    }

    /**
     * Schrijft de testreden in een herhaalbaar te overschrijven, gemarkeerd blok in de
     * parent-story-description (eigen `test-feedback`-markers, los van de handmatige-afkeur-feedback).
     * Bestaat het blok al (vorige bevinding), dan wordt het vervangen i.p.v. gestapeld. Hergebruik van
     * de marker-blok-techniek uit `ManualCommandService.writeRejectionReasonToStory`.
     */
    private fun writeTestFeedbackToStory(parentKey: String, reason: String?) {
        val parent = runCatching { issueTrackerClient.getIssue(parentKey) }.getOrElse {
            logger.warn("Test-reject: kon story {} niet laden voor de testreden.", parentKey, it)
            return
        }
        val block = buildString {
            append(TEST_FEEDBACK_START)
            append("\n## Test-feedback\n")
            append(reason?.takeIf { it.isNotBlank() } ?: "(geen reden opgegeven)")
            append("\n")
            append(TEST_FEEDBACK_END)
        }
        val existing = parent.description.orEmpty()
        val blockRegex = Regex(
            Regex.escape(TEST_FEEDBACK_START) + ".*?" + Regex.escape(TEST_FEEDBACK_END),
            RegexOption.DOT_MATCHES_ALL,
        )
        val updatedDescription = if (blockRegex.containsMatchIn(existing)) {
            blockRegex.replace(existing, Regex.escapeReplacement(block))
        } else if (existing.isBlank()) {
            block
        } else {
            "$existing\n\n$block"
        }
        runCatching { issueTrackerClient.updateIssueDescription(parentKey, updatedDescription) }
            .onFailure { logger.warn("Test-reject: kon story-description van {} niet bijwerken.", parentKey, it) }
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
            SubtaskPhase.DEVELOPED_WITH_QUESTIONS -> questionsOutcome(subtask)
            SubtaskPhase.DEVELOPED -> autoAdvanceSubtask(subtask, SubtaskPhase.DEVELOPMENT_APPROVED)
            SubtaskPhase.DEVELOPMENT_APPROVED -> dispatchSubtask(subtask, AgentRole.REVIEWER, SubtaskPhase.REVIEWING)
            SubtaskPhase.REVIEW_QUESTIONS_ANSWERED ->
                dispatchSubtask(subtask, AgentRole.REVIEWER, SubtaskPhase.REVIEWING)
            SubtaskPhase.REVIEW_REJECTED ->
                dispatchSubtask(subtask, AgentRole.DEVELOPER, SubtaskPhase.DEVELOPING, loopback = true)
            SubtaskPhase.REVIEWING -> recoverActiveSubtaskPhase(subtask, SubtaskPhase.REVIEWING)
            SubtaskPhase.REVIEWED_WITH_QUESTIONS -> questionsOutcome(subtask)
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
            SubtaskPhase.REVIEWED_WITH_QUESTIONS -> questionsOutcome(subtask)
            SubtaskPhase.REVIEWED -> autoAdvanceSubtask(subtask, SubtaskPhase.REVIEW_APPROVED)
            SubtaskPhase.REVIEW_REJECTED ->
                dispatchSubtask(subtask, AgentRole.DEVELOPER, SubtaskPhase.DEVELOPING, loopback = true)
            SubtaskPhase.DEVELOPING -> recoverActiveSubtaskPhase(subtask, SubtaskPhase.DEVELOPING)
            SubtaskPhase.DEVELOPED_WITH_QUESTIONS -> questionsOutcome(subtask)
            SubtaskPhase.DEVELOPMENT_QUESTIONS_ANSWERED ->
                dispatchSubtask(subtask, AgentRole.DEVELOPER, SubtaskPhase.DEVELOPING)
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
            SubtaskPhase.TESTED_WITH_QUESTIONS -> questionsOutcome(subtask)
            SubtaskPhase.TESTED -> autoAdvanceSubtask(subtask, SubtaskPhase.TEST_APPROVED)
            // SF-200 — de tester doet geen eigen developer-fix meer: een bevinding reset de hele
            // subtaak-keten (zoals een handmatige reject), met de testreden als feedback in de story.
            SubtaskPhase.TEST_REJECTED -> handleTestRejection(subtask)
            SubtaskPhase.DEVELOPING -> recoverActiveSubtaskPhase(subtask, SubtaskPhase.DEVELOPING)
            SubtaskPhase.DEVELOPED_WITH_QUESTIONS -> questionsOutcome(subtask)
            SubtaskPhase.DEVELOPMENT_QUESTIONS_ANSWERED ->
                dispatchSubtask(subtask, AgentRole.DEVELOPER, SubtaskPhase.DEVELOPING)
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
            SubtaskPhase.SUMMARY_WITH_QUESTIONS -> questionsOutcome(subtask)
            SubtaskPhase.SUMMARIZED -> autoAdvanceSubtask(subtask, SubtaskPhase.SUMMARY_APPROVED)
            SubtaskPhase.SUMMARY_APPROVED -> advanceSubtaskChain(subtask)
            else -> IssueProcessResult.Skipped(subtask.key, "summary-subtask-unexpected:${phase.trackerValue}")
        }

    /**
     * SF-213 — documentatie-stap. Gemodelleerd naar [summarySubtask]: dispatch de DOCUMENTER op
     * `start`/`documentation-questions-answered`, recover een hangende `documenting`-fase, auto-advance
     * `documented → documentation-approved` (bij auto-approve), en zet de keten door op approved. Geen
     * reject-tak: de documenter doet z'n werk en rapporteert klaar of stelt vragen.
     */
    private fun documentationSubtask(subtask: TrackerIssue, phase: SubtaskPhase?): IssueProcessResult =
        when (phase) {
            null -> IssueProcessResult.Skipped(subtask.key, "not-started")
            SubtaskPhase.START,
            SubtaskPhase.DOCUMENTATION_QUESTIONS_ANSWERED,
            -> dispatchSubtask(subtask, AgentRole.DOCUMENTER, SubtaskPhase.DOCUMENTING)
            SubtaskPhase.DOCUMENTING -> recoverActiveSubtaskPhase(subtask, SubtaskPhase.DOCUMENTING)
            SubtaskPhase.DOCUMENTATION_WITH_QUESTIONS -> questionsOutcome(subtask)
            SubtaskPhase.DOCUMENTED -> autoAdvanceSubtask(subtask, SubtaskPhase.DOCUMENTATION_APPROVED)
            SubtaskPhase.DOCUMENTATION_APPROVED -> advanceSubtaskChain(subtask)
            else -> IssueProcessResult.Skipped(subtask.key, "documentation-subtask-unexpected:${phase.trackerValue}")
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
        // het resultaat nog niet heeft ingelezen; in dat gat is de tracker-fase nog "developing".
        if (parentKey != null) {
            val storyRun = storyRunRepository.openOrCreate(parentKey, subtask.fields.targetRepo.orEmpty())
            val latestRun = agentRunRepository.latestForRole(storyRun.id, role)
            if (latestRun != null && latestRun.endedAt == null) {
                return IssueProcessResult.Skipped(subtask.key, "awaiting-agent-completion")
            }
            // Net geëindigde run: de completion zet `endedAt` in de DB VÓÓRdat 'ie de nieuwe fase
            // naar de tracker schrijft. Geef de completion daarom een grace ná endedAt.
            val endedAt = latestRun?.endedAt
            if (endedAt != null && endedAt.plus(settings.activePhaseRecoveryDelay).isAfter(now)) {
                return IssueProcessResult.Skipped(subtask.key, "awaiting-completion-settle")
            }
        }
        // Extra vangnet: een net-gestarte run nog even rust geven (tijd-grace vanaf start).
        if (startedAt != null &&
            startedAt.plus(settings.activePhaseRecoveryDelay).isAfter(now)
        ) {
            return IssueProcessResult.Skipped(subtask.key, "waiting-for-active-phase-recovery")
        }
        logger.warn("Recovery: subtask {} hangt in {}; herstart {}.", subtask.key, active.trackerValue, role.markerKeyPart)
        return dispatchSubtask(subtask, role, active)
    }

    private fun advanceSubtaskChain(finished: TrackerIssue): IssueProcessResult {
        val parentKey = issueTrackerClient.parentStoryKey(finished.key)
        // De afgeronde subtask heeft z'n eindfase bereikt → Done-lane. Alleen aanroepen bij een
        // echte statuswijziging: anders wordt `updated_at` elke poll opnieuw gebumpt en wekt de
        // subtask zichzelf voor altijd op (SF-903).
        if (finished.status != stateDone) {
            issueTrackerClient.transitionIssue(finished.key, stateDone)
        }
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
            else -> {
                // Zelfde idempotentie-guard als hierboven, nu voor de parent-story.
                if (issueTrackerClient.getIssue(parentKey).status != stateDone) {
                    issueTrackerClient.transitionIssue(parentKey, stateDone)
                }
                // Elke subtask-handler (o.a. deploy) kan via openOrCreate() een NIEUWE story_run
                // openen nadat de merge-run al gesloten is (bv. voor de deploy-fase) — die bleef tot
                // nu toe voor altijd open (ended_at leeg): niemand sloot 'm na een geslaagde deploy.
                // Nu de story écht klaar is (geen non-terminal subtaken meer): sluit de actieve run
                // alsnog, anders toont de dashboard voor een voltooide story voor altijd een lege
                // "ended"-datum.
                runCatching { storyRunRepository.openOrCreate(parentKey, "") }.getOrNull()
                    ?.let { storyRunRepository.close(it.id, "done", OffsetDateTime.now(clock)) }
            }
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
     * Auto-approve geldt centraal op de PARENT-story; de beslislogica leeft in
     * [HumanActionPolicy], zodat uitvoering, inbox en meldingen dezelfde nemen.
     */
    private fun autoApproveActive(subtask: TrackerIssue): Boolean =
        HumanActionPolicy.autoApproveActive(subtask) { subtaskKey ->
            issueTrackerClient.parentStoryKey(subtaskKey)
                ?.let { parentKey -> runCatching { issueTrackerClient.getIssue(parentKey).fields }.getOrNull() }
        }

    /**
     * SF-335 — uitkomst van een `*_WITH_QUESTIONS`-subtaak-fase. Bij een effectief silent subtaak
     * (eigen veld of geërfd van de parent) wachten we niet op een mens, maar zetten we de subtaak in
     * [TrackerField.ERROR] met de vragen (uit de laatste agent-comment) als clarification-gemarkeerde
     * error-tekst. Niet-silent: bestaand wacht-gedrag (`waiting-for-user`).
     */
    private fun questionsOutcome(subtask: TrackerIssue): IssueProcessResult {
        if (!issueTrackerClient.effectiveSilent(subtask)) {
            return IssueProcessResult.Skipped(subtask.key, "waiting-for-user")
        }
        val questions = subtask.comments
            .lastOrNull { it.isAgentComment }?.body?.takeIf { it.isNotBlank() }
        val message = ErrorCategory.clarificationText(questions)
        issueTrackerClient.updateIssueFields(subtask.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
        logger.info("Silent: subtaak {} kreeg vragen ({}); in clarification-error gezet i.p.v. wachten.", subtask.key, subtask.fields.subtaskPhase)
        return IssueProcessResult.Errored(subtask.key, message)
    }

    companion object {
        // SF-200 — markers rond het test-feedbackblok in de story-description. Stabiel houden: een
        // volgende test-bevinding vervangt het blok hierop (niet stapelen). Bewust losse markers van
        // het manual-approve-feedbackblok, zodat test-feedback en handmatige-afkeur-feedback los staan.
        const val TEST_FEEDBACK_START = "<!-- test-feedback:start -->"
        const val TEST_FEEDBACK_END = "<!-- test-feedback:end -->"
    }
}
