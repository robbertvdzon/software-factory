package nl.vdzon.softwarefactory.web.views.shared

import nl.vdzon.softwarefactory.web.models.UiAgentRun

/*
 * Presentatie van agent-runs (uitkomst-labels, sortering, iteratie-nummering), gedeeld door
 * de agents-pagina en de briefing.
 */

internal data class OutcomePresentation(
    val label: String,
    val code: String?,
    val kind: String,
)

internal fun outcomePresentation(run: UiAgentRun): OutcomePresentation {
    val raw = run.outcome?.trim().orEmpty()
    if (run.endedAt == null && raw.isBlank()) {
        return OutcomePresentation("Loopt", null, "info")
    }
    val normalized = raw.lowercase()
    val role = run.role.lowercase()
    return when {
        normalized == "refined-finished" || (role == "refiner" && normalized == "ok") ->
            OutcomePresentation("Refinement klaar", "REFINED_FINISHED", "ok")
        normalized == "refined-with-questions-for-user" || (role == "refiner" && normalized == "questions") ->
            OutcomePresentation("Vragen voor gebruiker", "REFINED_WITH_QUESTIONS_FOR_USER", "warn")
        normalized == "developed" || (role == "developer" && normalized == "ok") ->
            OutcomePresentation("Ontwikkeld", "DEVELOPED", "ok")
        normalized == "review-finished" || (role == "reviewer" && normalized == "ok") ->
            OutcomePresentation("Review akkoord", "REVIEW_FINISHED", "ok")
        normalized == "reviewed-with-feedback-for-developer" || (role == "reviewer" && normalized == "feedback") ->
            OutcomePresentation("Review met feedback", "REVIEWED_WITH_FEEDBACK_FOR_DEVELOPER", "warn")
        normalized == "tested-successfully" || (role == "tester" && normalized == "ok") ->
            OutcomePresentation("Test geslaagd", "TESTED_SUCCESSFULLY", "ok")
        normalized == "tested-with-feedback-for-developer" || (role == "tester" && normalized == "bug") ->
            OutcomePresentation("Test-feedback", "TESTED_WITH_FEEDBACK_FOR_DEVELOPER", "warn")
        normalized == "summary-finished" || (role == "summarizer" && normalized == "ok") ->
            OutcomePresentation("Samenvatting klaar", "SUMMARY_FINISHED", "ok")
        normalized == "credits-exhausted" ->
            OutcomePresentation("Credits op", "CREDITS_EXHAUSTED", "bad")
        normalized == "stopped-manually" ->
            OutcomePresentation("Handmatig gestopt", "STOPPED_MANUALLY", "warn")
        normalized.contains("error") || normalized.contains("failed") ->
            OutcomePresentation("Mislukt", raw.uppercase().replace("-", "_"), "bad")
        raw.isNotBlank() ->
            OutcomePresentation(raw.replace("-", " ").replaceFirstChar { it.titlecase() }, raw.uppercase().replace("-", "_"), "info")
        else ->
            OutcomePresentation("Afgerond", null, "info")
    }
}

internal fun List<UiAgentRun>.sortedByNewestRun(): List<UiAgentRun> =
    sortedWith(compareByDescending<UiAgentRun> { it.startedAt }.thenByDescending { it.id })

/** Nummer per rol (bv. "2/3"): hoeveelste run van die rol dit chronologisch is. */
internal fun agentRunIterationLabels(runs: List<UiAgentRun>): Map<Long, String> =
    runs.groupBy { it.role.lowercase() }
        .flatMap { (_, roleRuns) ->
            val chronological = roleRuns.sortedWith(compareBy<UiAgentRun> { it.startedAt }.thenBy { it.id })
            chronological.mapIndexed { index, run -> run.id to "${index + 1}/${chronological.size}" }
        }
        .toMap()
