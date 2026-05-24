package nl.vdzon.softwarefactory.orchestrator.models

import nl.vdzon.softwarefactory.youtrack.AgentRole

enum class AiPhase(val trackerValue: String, val activeRole: AgentRole? = null) {
    REFINING("refining", AgentRole.REFINER),
    DEVELOPING("developing", AgentRole.DEVELOPER),
    REVIEWING("reviewing", AgentRole.REVIEWER),
    TESTING("testing", AgentRole.TESTER),
    REFINED_WITH_QUESTIONS_FOR_USER("refined-with-questions-for-user"),
    REFINED_FINISHED("refined-finished"),
    DEVELOPED("developed"),
    REVIEWED_WITH_FEEDBACK_FOR_DEVELOPER("reviewed-with-feedback-for-developer"),
    REVIEW_FINISHED("review-finished"),
    TESTED_WITH_FEEDBACK_FOR_DEVELOPER("tested-with-feedback-for-developer"),
    TESTED_SUCCESSFULLY("tested-successfully"),
    QUESTIONS_ANSWERED_FOR_REFINEMENT("questions-answered-for-refinement");

    val isActive: Boolean = activeRole != null

    companion object {
        fun fromTracker(value: String?): AiPhase? =
            value?.takeIf { it.isNotBlank() }?.let { phase ->
                entries.firstOrNull { it.trackerValue == phase }
            }

        fun activeFor(role: AgentRole): AiPhase =
            requireNotNull(entries.firstOrNull { it.activeRole == role }) {
                "No active phase exists for role $role."
            }

        fun completedAfterSuccessful(role: AgentRole, outcome: String?): AiPhase =
            outcome?.let { fromTracker(it) }?.takeUnless { it.isActive } ?: when (role) {
                AgentRole.REFINER -> REFINED_FINISHED
                AgentRole.DEVELOPER -> DEVELOPED
                AgentRole.REVIEWER -> REVIEW_FINISHED
                AgentRole.TESTER -> TESTED_SUCCESSFULLY
                AgentRole.COST_MONITOR,
                AgentRole.ORCHESTRATOR,
                -> error("Role $role does not have a completed phase.")
            }

        fun previousCompletedBeforeRetry(activePhase: AiPhase): AiPhase? =
            when (activePhase) {
                REFINING -> null
                DEVELOPING -> REFINED_FINISHED
                REVIEWING -> DEVELOPED
                TESTING -> REVIEW_FINISHED
                else -> error("Phase ${activePhase.trackerValue} is not active.")
            }
    }
}
