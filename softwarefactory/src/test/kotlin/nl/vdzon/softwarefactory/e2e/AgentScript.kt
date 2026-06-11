package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.orchestrator.AgentDispatchRequest
import nl.vdzon.softwarefactory.runtime.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.AgentRunSubtaskPayload
import nl.vdzon.softwarefactory.youtrack.AgentRole

/**
 * Deterministisch script voor [TestAgentRuntime]: bepaalt op basis van
 * `(role, attempt)` welk [AgentRunCompleteRequest] een scripted agent-run zou
 * schrijven. Geen LLM, geen toeval — elke (rol, poging) levert exact één
 * resultaat dat het echte completion-pad door de pipeline duwt.
 *
 * De `attempt`-teller telt per `(serializationKey, role)` op vanaf 1 en wordt
 * door [TestAgentRuntime] bijgehouden. Zo kan dezelfde rol eerst een vraag
 * stellen (attempt 1) en bij de vervolg-dispatch (attempt 2) afronden.
 */
object AgentScript {

    /** De vier subtaken die de planner declareert (zie e2e-plan §2b). */
    val plannedSubtasks: List<AgentRunSubtaskPayload> = listOf(
        AgentRunSubtaskPayload(type = "development", title = "Implement the story"),
        AgentRunSubtaskPayload(type = "review", title = "Review the implementation"),
        AgentRunSubtaskPayload(type = "test", title = "Test the implementation"),
        AgentRunSubtaskPayload(type = "summary", title = "Summarize the work"),
    )

    /**
     * Bouwt het resultaat voor één scripted dispatch.
     *
     * @param attempt 1-based poging-teller voor `(serializationKey, role)`.
     */
    fun resultFor(request: AgentDispatchRequest, attempt: Int): AgentRunCompleteRequest {
        val base = AgentRunCompleteRequest(
            storyKey = request.storyKey,
            role = request.role.markerKeyPart,
            containerName = "",
            outcome = "ok",
        )
        return when (request.role) {
            AgentRole.REFINER ->
                if (attempt <= 1) base.copy(
                    phase = "refined-with-questions",
                    outcome = "questions",
                    summaryText = "Wil je dat ik doorga met de standaard-aanpak?",
                ) else base.copy(phase = "refined")

            AgentRole.PLANNER ->
                base.copy(phase = "planned", subtasks = plannedSubtasks)

            AgentRole.DEVELOPER ->
                if (attempt <= 1) base.copy(
                    phase = "developed-with-questions",
                    outcome = "questions",
                    summaryText = "Welke variant wil je geïmplementeerd hebben?",
                ) else base.copy(phase = "developed")

            AgentRole.REVIEWER ->
                base.copy(phase = "review-approved", outcome = "approved")

            AgentRole.TESTER ->
                base.copy(phase = "test-approved", outcome = "approved")

            AgentRole.SUMMARIZER ->
                base.copy(phase = "summarized")

            else ->
                base.copy(phase = request.phase)
        }
    }
}
