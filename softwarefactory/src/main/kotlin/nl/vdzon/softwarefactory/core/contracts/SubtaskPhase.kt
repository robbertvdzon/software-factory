package nl.vdzon.softwarefactory.core.contracts

import nl.vdzon.softwarefactory.core.*
import nl.vdzon.softwarefactory.core.contracts.*

import nl.vdzon.softwarefactory.core.AgentRole

/**
 * Subtask-niveau lifecycle. Een subtask is een ketting van AI-stappen; elke stap
 * volgt het patroon:
 * `*-ing -> (*-with-questions <-> *-questions-answered) -> *-ed -> [goedkeuring] *-approved | *-rejected`.
 *
 * De terminale status per type is het laatste `*-approved` (development/review:
 * REVIEW_APPROVED; test: TEST_APPROVED; summary: SUMMARY_APPROVED); manual eindigt
 * op MANUAL_ACTION_DONE. Zie de per-type status->actie-tabellen in
 * `specs/v2-plan/fase-5-subtask-execution-coordinator.md`.
 *
 * `activeRole` markeert de statussen waarin een agent draait.
 */
enum class SubtaskPhase(val trackerValue: String, val activeRole: AgentRole? = null) {
    // Expliciete start: een subtaak wordt PAS opgepakt als de fase op `start` staat.
    // Lege fase = nog niet starten. De keten zet de volgende subtaak op `start`.
    START("start"),
    // developer-stap
    DEVELOPING("developing", AgentRole.DEVELOPER),
    DEVELOPED("developed"),
    DEVELOPED_WITH_QUESTIONS("developed-with-questions"),
    DEVELOPMENT_QUESTIONS_ANSWERED("development-questions-answered"),
    DEVELOPMENT_APPROVED("development-approved"),
    DEVELOPMENT_REJECTED("development-rejected"),
    // reviewer-stap
    REVIEWING("reviewing", AgentRole.REVIEWER),
    REVIEWED("reviewed"),
    REVIEWED_WITH_QUESTIONS("reviewed-with-questions"),
    REVIEW_QUESTIONS_ANSWERED("review-questions-answered"),
    REVIEW_APPROVED("review-approved"),
    REVIEW_REJECTED("review-rejected"),
    // tester-stap
    TESTING("testing", AgentRole.TESTER),
    TESTED("tested"),
    TESTED_WITH_QUESTIONS("tested-with-questions"),
    TEST_QUESTIONS_ANSWERED("test-questions-answered"),
    TEST_APPROVED("test-approved"),
    TEST_REJECTED("test-rejected"),
    // summary-stap
    SUMMARIZING("summarizing", AgentRole.SUMMARIZER),
    SUMMARIZED("summarized"),
    SUMMARY_WITH_QUESTIONS("summary-with-questions"),
    SUMMARY_QUESTIONS_ANSWERED("summary-questions-answered"),
    SUMMARY_APPROVED("summary-approved"),
    SUMMARY_REJECTED("summary-rejected"),
    // documentation-stap (SF-213): gemodelleerd naar summary; geen reject-tak.
    DOCUMENTING("documenting", AgentRole.DOCUMENTER),
    DOCUMENTED("documented"),
    DOCUMENTATION_WITH_QUESTIONS("documentation-with-questions"),
    DOCUMENTATION_QUESTIONS_ANSWERED("documentation-questions-answered"),
    DOCUMENTATION_APPROVED("documentation-approved"),
    // manual (geen agent)
    AWAITING_HUMAN("awaiting-human"),
    MANUAL_ACTION_DONE("manual-action-done"),
    // manual-approve-poort (geen agent): wachten op een handmatige goedkeuring vlak vóór de merge.
    // `manually-approved` is terminaal (keten gaat door); `manually-not-approved` is transient en
    // triggert een volledige story-reset.
    MANUAL_APPROVE_NEEDED("manual-approve-needed"),
    MANUALLY_APPROVED("manually-approved"),
    MANUALLY_NOT_APPROVED("manually-not-approved"),
    // merge-stap (geen agent)
    MERGING("merging"),
    MERGE_APPROVED("merge-approved"),
    // deploy-stap (geen agent)
    DEPLOYING("deploying"),
    DEPLOY_APPROVED("deploy-approved"),
    DEPLOY_FAILED("deploy-failed");

    val isActive: Boolean = activeRole != null

    /**
     * Eindstatus van een subtask (fase 4 — keten advancet hierop). Per type het
     * laatste `*-approved`; manual eindigt op `manual-action-done`.
     */
    val isTerminal: Boolean
        get() = this == REVIEW_APPROVED ||
            this == TEST_APPROVED ||
            this == SUMMARY_APPROVED ||
            this == DOCUMENTATION_APPROVED ||
            this == MANUAL_ACTION_DONE ||
            this == MANUALLY_APPROVED ||
            this == MERGE_APPROVED ||
            this == DEPLOY_APPROVED ||
            this == DEPLOY_FAILED

    companion object {
        fun fromTracker(value: String?): SubtaskPhase? =
            value?.takeIf { it.isNotBlank() }?.let { v -> entries.firstOrNull { it.trackerValue == v } }
    }
}
