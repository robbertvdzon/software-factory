package nl.vdzon.softwarefactory.core

import nl.vdzon.softwarefactory.core.AgentRole

/**
 * Story-niveau lifecycle = puur het refinement-proces (refiner + planner, elk met
 * een vragen-loop en een goedkeuringsstap). Zie de status->actie-tabel in
 * `specs/v2-plan/README.md` (§3) en de uitwerking in fase 2.
 *
 * `activeRole` markeert de statussen waarin een agent draait (REFINING -> refiner;
 * PLANNING -> planner in fase 2b).
 *
 * `PLANNING_APPROVED` is terminaal voor de orchestrator; development is
 * tag-gedreven (geen developing/done/summarizing op story-niveau).
 */
enum class StoryPhase(val trackerValue: String, val activeRole: AgentRole? = null) {
    // Expliciete start: een story wordt PAS opgepakt als de fase op `start` staat.
    // Lege fase = nog niet starten (zodat je stories kunt aanmaken zonder dat ze meteen lopen).
    START("start"),
    // refine-stap
    REFINING("refining", AgentRole.REFINER),
    REFINED_WITH_QUESTIONS("refined-with-questions"),
    QUESTIONS_ANSWERED("questions-answered"),
    REFINED("refined"),
    REFINED_REJECTED("refined-rejected"),
    REFINED_APPROVED("refined-approved"),
    // plan-stap
    PLANNING("planning", AgentRole.PLANNER),
    PLANNED_WITH_QUESTIONS("planned-with-questions"),
    PLANNING_QUESTIONS_ANSWERED("planning-questions-answered"),
    PLANNED("planned"),
    PLANNING_REJECTED("planning-rejected"),
    PLANNING_APPROVED("planning-approved");

    val isActive: Boolean = activeRole != null

    companion object {
        fun fromTracker(value: String?): StoryPhase? =
            value?.takeIf { it.isNotBlank() }?.let { v -> entries.firstOrNull { it.trackerValue == v } }
    }
}
