package nl.vdzon.softwarefactory.orchestrator

/**
 * Story-niveau lifecycle = puur het refinement-proces (refiner + planner, elk met
 * een vragen-loop en een goedkeuringsstap). Zie de status->actie-tabel in
 * `specs/v2-plan/README.md` (§3) en de uitwerking in fase 2.
 *
 * `PLANNING_APPROVED` is terminaal voor de orchestrator; development is
 * tag-gedreven (geen developing/done/summarizing op story-niveau).
 */
enum class StoryPhase(val trackerValue: String) {
    // refine-stap
    REFINING("refining"),
    REFINED_WITH_QUESTIONS("refined-with-questions"),
    QUESTIONS_ANSWERED("questions-answered"),
    REFINED("refined"),
    REFINED_REJECTED("refined-rejected"),
    REFINED_APPROVED("refined-approved"),
    // plan-stap
    PLANNING("planning"),
    PLANNED_WITH_QUESTIONS("planned-with-questions"),
    PLANNING_QUESTIONS_ANSWERED("planning-questions-answered"),
    PLANNED("planned"),
    PLANNING_REJECTED("planning-rejected"),
    PLANNING_APPROVED("planning-approved");

    companion object {
        fun fromTracker(value: String?): StoryPhase? =
            value?.takeIf { it.isNotBlank() }?.let { v -> entries.firstOrNull { it.trackerValue == v } }
    }
}
