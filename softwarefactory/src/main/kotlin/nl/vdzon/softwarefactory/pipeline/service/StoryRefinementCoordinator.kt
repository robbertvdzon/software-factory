package nl.vdzon.softwarefactory.pipeline.service

import nl.vdzon.softwarefactory.core.contracts.AgentFailurePolicy
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AgentRunRecord
import nl.vdzon.softwarefactory.core.contracts.ApprovalMode
import nl.vdzon.softwarefactory.core.contracts.AgentRunRepository
import nl.vdzon.softwarefactory.core.contracts.AgentRuntime
import nl.vdzon.softwarefactory.core.contracts.ErrorCategory
import nl.vdzon.softwarefactory.core.contracts.IssueProcessResult
import nl.vdzon.softwarefactory.core.contracts.OrchestratorSettings
import nl.vdzon.softwarefactory.core.contracts.StoryPhase
import nl.vdzon.softwarefactory.core.contracts.SubtaskPhase
import nl.vdzon.softwarefactory.core.contracts.StoryRunRecord
import nl.vdzon.softwarefactory.core.contracts.StoryRunRepository
import nl.vdzon.softwarefactory.core.contracts.SubtaskSpec
import nl.vdzon.softwarefactory.core.contracts.SubtaskType
import nl.vdzon.softwarefactory.core.contracts.CompletionProgress
import nl.vdzon.softwarefactory.runtime.SubtaskMaterializationApi
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.tracker.TrackerCapabilities
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.beans.factory.annotation.Autowired
import java.time.Clock
import java.time.OffsetDateTime

/** Markers waarmee de refiner het voorgestelde story-description-blok afbakent in zijn comment. */
private const val PROPOSED_DESCRIPTION_START = "<!-- proposed-description:start -->"
private const val PROPOSED_DESCRIPTION_END = "<!-- proposed-description:end -->"

/** Markers waarmee de refiner de korte samenvatting voor de aanvrager afbakent in zijn comment. */
private const val PROPOSED_SUMMARY_START = "<!-- proposed-summary:start -->"
private const val PROPOSED_SUMMARY_END = "<!-- proposed-summary:end -->"

/** Onzichtbare sentinel bovenaan een gepromote description; maakt promotie idempotent. */
private const val REFINED_DESCRIPTION_MARKER = "<!-- refined-by-factory -->"

/**
 * Fase 2 — de story-fase-staatmachine (`Story Phase`): refine- en plan-stap, recovery van een
 * actieve fase, auto-approve en het promoten van het refiner-voorstel naar de description.
 * Dispatchen van de refiner/planner gebeurt via de gedeelde [AgentDispatcher].
 */
@Component
class StoryRefinementCoordinator(
    private val issueTrackerClient: TrackerCapabilities,
    private val agentRuntime: AgentRuntime,
    private val storyRunRepository: StoryRunRepository,
    private val agentRunRepository: AgentRunRepository,
    private val settings: OrchestratorSettings,
    private val clock: Clock,
    private val dispatcher: AgentDispatcher,
    // SF-1959 — alleen nodig voor de hotfix-tak. Optionele laatste parameter zodat bestaande
    // directe constructie (tests) ongewijzigd blijft; Spring injecteert de bean
    // (SubtaskPlanMaterializer) wél. Ontbreekt 'ie tóch, dan faalt de hotfix-start luid.
    private val subtaskMaterialization: SubtaskMaterializationApi? = null,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private var completionProgress: CompletionProgress = CompletionProgress.none()

    @Autowired(required = false)
    private fun configureCompletionProgress(progress: CompletionProgress?) {
        completionProgress = progress ?: CompletionProgress.none()
    }

    /**
     * Fase 2a — refine-stap op het `Story Phase`-veld; fase 2b — plan-stap.
     */
    fun processStoryRefinement(issue: TrackerIssue): IssueProcessResult {
        if (completionProgress.hasUnfinishedForStory(issue.key)) {
            return IssueProcessResult.Skipped(issue.key, "awaiting-durable-completion")
        }
        return quotaWaitOutcome(issue) ?: processStoryPhase(issue)
    }

    private fun processStoryPhase(issue: TrackerIssue): IssueProcessResult =
        when (StoryPhase.fromTracker(issue.fields.storyPhase)) {
            // Lege fase = nog niet starten; pas bij fase `start` pakt de orchestrator 'm op.
            null -> IssueProcessResult.Skipped(issue.key, "not-started")
            // In de wachtrij: de per-repo promotor in OrchestratorService zet 'm op `start` zodra
            // de target-repo vrij is (geen andere story met een open story_run erop). Zelf niets doen.
            StoryPhase.START_NEXT -> IssueProcessResult.Skipped(issue.key, "queued-start-next")
            // SF-1959 — hotfix takt hier af: geen refiner/planner, maar direct de kale keten
            // [hotfix, merge, deploy]. Alleen op `start`; `start-next` blijft ongewijzigd (de
            // per-repo wachtrij-promotor zet de story eerst op `start`).
            StoryPhase.START -> startStory(issue)
            StoryPhase.QUESTIONS_ANSWERED,
            StoryPhase.REFINED_REJECTED,
            -> dispatchRefiner(issue)
            StoryPhase.REFINED_WITH_QUESTIONS -> questionsOutcome(issue)
            StoryPhase.REFINED -> autoAdvanceStory(issue, StoryPhase.REFINED_APPROVED)
            StoryPhase.REFINING -> recoverActiveStoryPhase(issue, StoryPhase.REFINING)
            // Plan-stap (fase 2b): refined-approved start de planner. Bij de approve-overgang
            // promoten we eerst het door de mens goedgekeurde refiner-voorstel naar de description.
            StoryPhase.REFINED_APPROVED -> {
                promoteRefinedDescription(issue)
                dispatcher.dispatch(AgentDispatchContext(
                    issue,
                    AgentRole.PLANNER,
                    sourcePhase = null,
                    phaseField = TrackerField.STORY_PHASE,
                    activePhaseValue = StoryPhase.PLANNING.trackerValue,
                ))
            }
            StoryPhase.PLANNING_QUESTIONS_ANSWERED,
            StoryPhase.PLANNING_REJECTED,
            -> dispatcher.dispatch(AgentDispatchContext(
                issue,
                AgentRole.PLANNER,
                sourcePhase = null,
                phaseField = TrackerField.STORY_PHASE,
                activePhaseValue = StoryPhase.PLANNING.trackerValue,
            ))
            StoryPhase.PLANNED_WITH_QUESTIONS -> questionsOutcome(issue)
            StoryPhase.PLANNED -> autoAdvanceStory(issue, StoryPhase.PLANNING_APPROVED)
            StoryPhase.PLANNING -> recoverActiveStoryPhase(issue, StoryPhase.PLANNING)
            // Terminaal: refinement klaar. Bij goedkeuring=automatisch/alleen-manual-poort direct
            // development starten.
            StoryPhase.PLANNING_APPROVED,
            StoryPhase.IN_PROGRESS,
            -> terminalStoryPhaseOutcome(issue, autoApproveActive(issue)) { autoStartDevelopment(issue) }
        }

    private fun dispatchRefiner(issue: TrackerIssue): IssueProcessResult =
        dispatcher.dispatch(
            AgentDispatchContext(
                issue,
                AgentRole.REFINER,
                sourcePhase = null,
                phaseField = TrackerField.STORY_PHASE,
                activePhaseValue = StoryPhase.REFINING.trackerValue,
            ),
        )

    private fun startStory(issue: TrackerIssue): IssueProcessResult =
        if (issue.fields.hotfix) startHotfixChain(issue) else dispatchRefiner(issue)

    /**
     * SF-1959 — hotfix-start: materialiseer EXACT `[hotfix, merge, deploy]` (het exact-list-pad voegt
     * bewust niets toe — geen review/test/summary/documentation/manual-approve, ongeacht
     * [ApprovalMode]), zet de eerste subtaak op `start` en de story op `in-progress`. Er draait dus
     * géén refiner en géén planner.
     *
     * Idempotent: [SubtaskMaterializationApi.materializeFromSpecs] slaat bestaande titels over, en na
     * de overgang naar `in-progress` komt de story niet meer in deze tak.
     */
    private fun startHotfixChain(issue: TrackerIssue): IssueProcessResult {
        val materialization = subtaskMaterialization
        return if (materialization == null) {
            hotfixMaterializationUnavailable(issue)
        } else {
            materializeHotfixChain(issue, materialization)
        }
    }

    private fun hotfixMaterializationUnavailable(issue: TrackerIssue): IssueProcessResult {
        val message = "[ORCHESTRATOR] Hotfix-story kan niet starten: geen SubtaskMaterializationApi beschikbaar."
        issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
        return IssueProcessResult.Errored(issue.key, message)
    }

    private fun materializeHotfixChain(
        issue: TrackerIssue,
        materialization: SubtaskMaterializationApi,
    ): IssueProcessResult {
        materialization.materializeFromSpecs(issue.key, HOTFIX_CHAIN_SPECS)
        val subtasks = runCatching { issueTrackerClient.subtasksOf(issue.key) }.onFailure {
            logger.warn("Hotfix-start: kon subtaken niet ophalen voor {}.", issue.key, it)
        }.getOrNull()
        return if (subtasks == null) {
            IssueProcessResult.Skipped(issue.key, "subtasks-unavailable")
        } else {
            startFirstHotfixSubtask(issue, subtasks)
        }
    }

    private fun startFirstHotfixSubtask(
        issue: TrackerIssue,
        subtasks: List<TrackerIssue>,
    ): IssueProcessResult {
        val first = subtasks.firstOrNull { SubtaskPhase.fromTracker(it.fields.subtaskPhase)?.isTerminal != true }
        return if (first == null) {
            IssueProcessResult.Skipped(issue.key, "no-open-subtask")
        } else {
            if (first.fields.subtaskPhase.isNullOrBlank()) {
                issueTrackerClient.updateIssueFields(
                    first.key,
                    TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.START.trackerValue),
                )
            }
            issueTrackerClient.updateIssueFields(
                issue.key,
                TrackerFieldUpdate.of(TrackerField.STORY_PHASE to StoryPhase.IN_PROGRESS.trackerValue),
            )
            logger.info("Hotfix: story {} slaat refine/plan over; subtaak {} gestart.", issue.key, first.key)
            IssueProcessResult.Recovered(issue.key, StoryPhase.IN_PROGRESS.trackerValue)
        }
    }

    /** Quota-wachten gaat vóór actieve-fase recovery en dus vóór de hard-timeoutcontrole. */
    private fun quotaWaitOutcome(issue: TrackerIssue): IssueProcessResult? {
        val retryAfter = issue.fields.retryAfter ?: return null
        val now = OffsetDateTime.now(clock)
        return if (now.isBefore(retryAfter)) {
            IssueProcessResult.Skipped(issue.key, "claude-quota-until:$retryAfter")
        } else {
            issueTrackerClient.updateIssueFields(
                issue.key,
                TrackerFieldUpdate.of(
                    TrackerField.RETRY_AFTER to null,
                    TrackerField.AGENT_STARTED_AT to null,
                ),
            )
            val active = StoryPhase.fromTracker(issue.fields.storyPhase)?.takeIf { it.isActive }
            if (active == null) {
                IssueProcessResult.Recovered(issue.key, issue.fields.storyPhase ?: "<empty>")
            } else {
                dispatcher.dispatch(
                    AgentDispatchContext(
                        issue = issue,
                        role = requireNotNull(active.activeRole),
                        sourcePhase = null,
                        phaseField = TrackerField.STORY_PHASE,
                        activePhaseValue = active.trackerValue,
                    ),
                )
            }
        }
    }

    /** SF-1261 — as 2 (Goedkeuring). Story-niveau: geen parent-lookup nodig. */
    private fun autoApproveActive(issue: TrackerIssue): Boolean =
        ApprovalMode.fromTracker(issue.fields.approvalMode) != ApprovalMode.EVERY_STEP

    /**
     * SF-1261 — uitkomst van een `*_WITH_QUESTIONS`-story-fase. Bij vragen=uit wachten we niet op
     * een mens maar zetten we de story in [TrackerField.ERROR] met de vragen (uit de laatste agent-comment)
     * als — clarification-gemarkeerde — error-tekst. Vragen=aan: bestaand wacht-gedrag.
     */
    private fun questionsOutcome(issue: TrackerIssue): IssueProcessResult {
        if (issueTrackerClient.effectiveQuestionsAllowed(issue)) {
            return IssueProcessResult.Skipped(issue.key, "waiting-for-user")
        }
        val questions = issue.comments.lastOrNull { it.isAgentComment }?.body?.takeIf { it.isNotBlank() }
        val message = ErrorCategory.clarificationText(questions)
        issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
        logger.info(
            "Vragen uit: story {} kreeg vragen ({}); in clarification-error gezet i.p.v. wachten.",
            issue.key,
            StoryPhase.fromTracker(issue.fields.storyPhase)?.trackerValue,
        )
        return IssueProcessResult.Errored(issue.key, message)
    }

    /**
     * Auto-start development na planning-approved bij auto-approve=aan.
     * Idempotent: als al een subtaak een fase heeft, wordt er niets gestart.
     */
    private fun autoStartDevelopment(issue: TrackerIssue): IssueProcessResult {
        val subtasks = runCatching { issueTrackerClient.subtasksOf(issue.key) }.getOrElse {
            logger.warn("Auto-start: kon subtaken niet ophalen voor {}.", issue.key, it)
            return IssueProcessResult.Skipped(issue.key, "subtasks-unavailable")
        }
        if (subtasks.any { !it.fields.subtaskPhase.isNullOrBlank() }) {
            return IssueProcessResult.Skipped(issue.key, "development-already-started")
        }
        val first = subtasks.firstOrNull { SubtaskPhase.fromTracker(it.fields.subtaskPhase)?.isTerminal != true }
            ?: return IssueProcessResult.Skipped(issue.key, "no-open-subtask")
        issueTrackerClient.updateIssueFields(
            first.key,
            TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.START.trackerValue),
        )
        issueTrackerClient.updateIssueFields(
            issue.key,
            TrackerFieldUpdate.of(TrackerField.STORY_PHASE to StoryPhase.IN_PROGRESS.trackerValue),
        )
        logger.info("Auto-start: eerste subtaak {} gestart; story {} naar in-progress.", first.key, issue.key)
        return IssueProcessResult.Recovered(issue.key, StoryPhase.IN_PROGRESS.trackerValue)
    }

    /**
     * SF-12 — auto-approve op story-niveau. Bij auto-approve=aan advancet een `*-ed`-status direct
     * naar het bijbehorende `*-approved`. Default uit = bestaand gedrag (waiting).
     */
    private fun autoAdvanceStory(issue: TrackerIssue, approved: StoryPhase): IssueProcessResult {
        if (!autoApproveActive(issue)) {
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
     * Promoot het door de refiner voorgestelde description-blok (en de korte samenvatting voor de
     * aanvrager) naar de story bij approve. Idempotent: een al gepromote description (herkend aan
     * [REFINED_DESCRIPTION_MARKER]) blijft ongemoeid. Na promotie bevat de description alleen nog het
     * refiner-voorstel; alleen technische Product Factory-markers worden behouden. De oorspronkelijke
     * aanvraag blijft beschikbaar via de tracker-history.
     */
    private fun promoteRefinedDescription(issue: TrackerIssue) {
        runCatching {
            val fresh = issueTrackerClient.getIssue(issue.key)
            val current = fresh.description.orEmpty()
            if (current.contains(REFINED_DESCRIPTION_MARKER)) {
                return
            }
            val proposal = latestProposedBlock(fresh, PROPOSED_DESCRIPTION_START, PROPOSED_DESCRIPTION_END) ?: run {
                logger.info(
                    "Geen proposed-description-blok in refiner-comment voor {}; description ongewijzigd.",
                    issue.key,
                )
                return
            }
            val newDescription = buildString {
                append(REFINED_DESCRIPTION_MARKER)
                append("\n\n")
                append(proposal)
                productFactoryMetadataBlock(current)?.let { metadata ->
                    append("\n\n")
                    append(metadata)
                }
            }
            issueTrackerClient.updateIssueDescription(issue.key, newDescription)
            logger.info("Refiner-voorstel naar story-description gepromoot voor {}.", issue.key)

            val summary = latestProposedBlock(fresh, PROPOSED_SUMMARY_START, PROPOSED_SUMMARY_END)
            if (summary == null) {
                logger.info(
                    "Geen proposed-summary-blok in refiner-comment voor {}; samenvatting ongewijzigd.",
                    issue.key,
                )
            } else {
                issueTrackerClient.updateIssueDescriptionSummary(issue.key, summary)
                logger.info("Refiner-samenvatting naar story gepromoot voor {}.", issue.key)
            }
        }.onFailure { exception ->
            logger.warn("Kon refiner-voorstel niet naar description promoten voor {}.", issue.key, exception)
        }
    }

    /** Het blok tussen [start]/[end] uit de meest recente [AgentRole.REFINER]-comment, of null. */
    private fun latestProposedBlock(issue: TrackerIssue, start: String, end: String): String? =
        issue.comments
            .filter { it.body.trimStart().startsWith(AgentRole.REFINER.commentPrefix, ignoreCase = true) }
            .lastOrNull()
            ?.let { extractBlock(it.body, start, end) }

    private fun productFactoryMetadataBlock(description: String): String? {
        val markers = description.lineSequence()
            .map(String::trim)
            .filter { line -> PRODUCT_FACTORY_METADATA_PREFIXES.any(line::startsWith) }
            .toList()
        return markers.joinToString("\n")
            .takeIf { markers.any { line -> line == PRODUCT_FACTORY_V2_MARKER } }
    }

    private fun extractBlock(body: String, start: String, end: String): String? {
        val startIndex = body.indexOf(start)
        val endIndex = body.indexOf(end)
        if (startIndex < 0 || endIndex < 0 || endIndex <= startIndex) {
            return null
        }
        return body.substring(startIndex + start.length, endIndex).trim().takeIf { it.isNotBlank() }
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
        // refining -> start (refiner), planning -> refined-approved (planner). Nooit leeg: een
        // lege Story Phase betekent "nooit meer oppakken" en blokkeert de start-next-wachtrij.
        val retryReset: String =
            if (phase == StoryPhase.PLANNING) StoryPhase.REFINED_APPROVED.trackerValue else StoryPhase.START.trackerValue

        if (startedAt != null && startedAt.plus(settings.hardTimeout).isBefore(now)) {
            val message =
                "[ORCHESTRATOR] Hard timeout: ${phase.trackerValue} loopt langer dan " +
                    "${settings.hardTimeout.toMinutes()} minuten zonder voortgang."
            issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
            return IssueProcessResult.Errored(issue.key, message)
        }

        if (latestRun != null && latestRun.endedAt == null) {
            return IssueProcessResult.Skipped(issue.key, "awaiting-agent-completion")
        }

        if (latestRun != null && latestRun.isSuccessful()) {
            return recoveredFromSuccess(issue, phase, latestRun, completedDefault)
        }

        if (latestRun != null && latestRun.isRetryableFailure()) {
            return recoveredFromRetryableFailure(issue, role, storyRun, retryReset)
        }

        if (startedAt != null && startedAt.plus(settings.activePhaseRecoveryDelay).isAfter(now)) {
            return IssueProcessResult.Skipped(issue.key, "waiting-for-active-phase-recovery")
        }

        issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.STORY_PHASE to retryReset))
        return IssueProcessResult.Recovered(issue.key, retryReset)
    }

    private fun recoveredFromSuccess(
        issue: TrackerIssue,
        phase: StoryPhase,
        latestRun: AgentRunRecord,
        completedDefault: StoryPhase,
    ): IssueProcessResult {
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
        issueTrackerClient.updateIssueFields(
            issue.key,
            TrackerFieldUpdate.of(TrackerField.STORY_PHASE to completed.trackerValue),
        )
        return IssueProcessResult.Recovered(issue.key, completed.trackerValue)
    }

    private fun recoveredFromRetryableFailure(
        issue: TrackerIssue,
        role: AgentRole,
        storyRun: StoryRunRecord,
        retryReset: String,
    ): IssueProcessResult {
        val transientFailures = agentRunRepository.recentForRole(
            storyRun.id,
            role,
            settings.maxTransientRetries + 1,
        ).takeWhile { it.isRetryableFailure() }.size

        if (transientFailures <= settings.maxTransientRetries) {
            issueTrackerClient.updateIssueFields(
                issue.key,
                TrackerFieldUpdate.of(TrackerField.STORY_PHASE to retryReset),
            )
            return IssueProcessResult.Recovered(issue.key, retryReset)
        }

        val message =
            "[ORCHESTRATOR] Transient retry cap bereikt (${settings.maxTransientRetries}x) " +
                "voor ${role.markerKeyPart}; handmatige triage nodig."
        issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
        return IssueProcessResult.Errored(issue.key, message)
    }

    private fun AgentRunRecord.isSuccessful(): Boolean =
        endedAt != null &&
            outcome?.contains("error", ignoreCase = true) != true &&
            outcome?.contains("failed", ignoreCase = true) != true

    private fun AgentRunRecord.isRetryableFailure(): Boolean =
        AgentFailurePolicy.isRetryable(outcome, summaryText)

    companion object {
        /**
         * De volledige hotfix-keten. Bewust exact deze drie: het exact-list-pad van
         * [SubtaskMaterializationApi.materializeFromSpecs] voegt niets auto toe, dus merge en deploy
         * moeten hier expliciet staan. Titels zijn stabiel — de materialisatie is idempotent op titel;
         * merge/deploy dragen dezelfde titels als in de normale keten (`SubtaskPlanMaterializer`).
         */
        val HOTFIX_CHAIN_SPECS: List<SubtaskSpec> = listOf(
            SubtaskSpec(
                SubtaskType.HOTFIX,
                "Hotfix uitvoeren",
                "Voer de gevraagde wijziging uit zoals beschreven in de story en draai de bestaande " +
                    "projecttests. Geen refine/plan/review/test/documentatie-stap: dit is een hotfix.",
            ),
            SubtaskSpec(
                SubtaskType.MERGE,
                "Merge story-branch",
                "Merge de story-branch (handmatig of automatisch, volgens projects.yaml).",
            ),
            SubtaskSpec(
                SubtaskType.DEPLOY,
                "Deploy naar productie",
                "Deploy de gemergede code naar productie (volgens projects.yaml: skip/rest-restart/openshift-watch).",
            ),
        )
    }
}

private const val PRODUCT_FACTORY_V2_MARKER = "Product-Factory-Api-Version: 2"
private val PRODUCT_FACTORY_METADATA_PREFIXES = listOf(
    "Product-Factory-Api-Version:",
    "Product-Factory-Product-Id:",
    "Product-Factory-Source-Story-Id:",
    "Product-Factory-Source-Story-Version:",
    "Product-Factory-Idempotency-Key:",
    "Product-Factory-Package-Sha256:",
)

private fun terminalStoryPhaseOutcome(
    issue: TrackerIssue,
    autoApprove: Boolean,
    autoStart: () -> IssueProcessResult,
): IssueProcessResult =
    if (StoryPhase.fromTracker(issue.fields.storyPhase) == StoryPhase.PLANNING_APPROVED) {
        if (autoApprove) {
            autoStart()
        } else {
            IssueProcessResult.Skipped(issue.key, "refinement-done")
        }
    } else {
        IssueProcessResult.Skipped(issue.key, "development-in-progress")
    }
