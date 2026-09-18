package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.core.contracts.AgentDispatchRequest
import nl.vdzon.softwarefactory.runtime.models.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.models.AgentRunSubtaskPayload
import nl.vdzon.softwarefactory.core.AgentRole

/**
 * Configureerbaar, deterministisch script voor [TestAgentRuntime]. Per test stel je in:
 *  - welke rollen op hun **eerste poging een vraag stellen** (`*-with-questions`) en daarna afronden;
 *  - welke **subtaken** de planner declareert.
 *
 * De agent produceert altijd de "kale" eindfasen (`refined`, `developed`, `reviewed`, `tested`,
 * `summarized`); de **approve/reject-gate** ligt daarna bij de orchestrator (auto-approve) of de
 * gebruiker (via de UI). Testerafwijzingen en beperkingen kunnen via testerPhases worden gescript; overige rejects komen via de UI.
 *
 * De `attempt`-teller telt per `(serializationKey, role)` op vanaf 1 (bijgehouden door
 * [TestAgentRuntime]), zodat dezelfde rol eerst een vraag kan stellen en bij de vervolg-dispatch
 * afrondt.
 */
class AgentScript {

    /** Rollen die op attempt 1 een vraag stellen (`*-with-questions`), daarna afronden. */
    var refinerAsksQuestion: Boolean = true
    var plannerAsksQuestion: Boolean = false
    var developerAsksQuestion: Boolean = true
    var reviewerAsksQuestion: Boolean = false
    var testerAsksQuestion: Boolean = false
    var testerPhases: List<String> = emptyList()
    var summarizerAsksQuestion: Boolean = false
    var documenterAsksQuestion: Boolean = false

    /** De subtaken die de planner declareert (volgorde = keten-volgorde). */
    var plannedSubtasks: List<AgentRunSubtaskPayload> = DEFAULT_SUBTASKS

    /**
     * Simuleert de deterministische verificatie-poort die in [AgentCli] ná de DEVELOPER draait: zijn
     * de projecttests rood, dan wordt de eigen `developed`-conclusie overruled naar
     * `development-rejected` mét een `[FACTORY VERIFICATION]`-diagnose. Aan = elke developer-run
     * blijft rood (loopback tot de cap).
     */
    var developerVerificationFails: Boolean = false

    /** Verificatie-uitkomst per developer-attempt; `true` simuleert blijvend rood. */
    var developerVerificationFailures: List<Boolean> = emptyList()

    fun developerVerificationFails(attempt: Int): Boolean =
        developerVerificationFailures.getOrNull(attempt - 1) ?: developerVerificationFails

    fun resultFor(request: AgentDispatchRequest, attempt: Int): AgentRunCompleteRequest {
        val base = AgentRunCompleteRequest(
            storyKey = request.storyKey,
            role = request.role.markerKeyPart,
            containerName = "",
            outcome = "ok",
        )
        return when (request.role) {
            AgentRole.REFINER ->
                base.withQuestionOr(refinerAsksQuestion, attempt, "refined-with-questions", "Wil je dat ik doorga met de standaard-aanpak?", resolved = "refined")
            AgentRole.PLANNER ->
                // De planner stelt op attempt 1 een vraag (`planned-with-questions`, géén subtaken),
                // daarna levert 'ie het plan met subtaken. Spiegelt de refiner-vraagflow.
                if (plannerAsksQuestion && attempt <= 1) {
                    base.copy(phase = "planned-with-questions", outcome = "questions", summaryText = "Welke scope wil je dat ik plan?")
                } else {
                    base.copy(phase = "planned", subtasks = plannedSubtasks)
                }
            AgentRole.DEVELOPER -> {
                val developed = base.withQuestionOr(developerAsksQuestion, attempt, "developed-with-questions", "Welke variant wil je geïmplementeerd hebben?", resolved = "developed")
                val result = if (developerVerificationFails(attempt) && developed.phase == "developed") {
                    developed.copy(
                        phase = "development-rejected",
                        outcome = "development-rejected",
                        summaryText = "[FACTORY VERIFICATION] mvn verify: 1 failure.",
                    )
                } else {
                    developed
                }
                result
            }
            AgentRole.REVIEWER ->
                base.withQuestionOr(reviewerAsksQuestion, attempt, "reviewed-with-questions", "Is deze review-aanpak akkoord?", resolved = "reviewed")
            AgentRole.TESTER ->
                base.withQuestionOr(testerAsksQuestion, attempt, "tested-with-questions", "Welke testdekking verwacht je precies?", resolved = testerPhases.getOrNull(attempt - 1) ?: testerPhases.lastOrNull() ?: "tested")
            AgentRole.SUMMARIZER ->
                base.withQuestionOr(summarizerAsksQuestion, attempt, "summary-with-questions", "Moet de samenvatting ook de openstaande risico's bevatten?", resolved = "summarized")
            AgentRole.DOCUMENTER ->
                base.withQuestionOr(documenterAsksQuestion, attempt, "documentation-with-questions", "Welke documentatie wil je dat ik bijwerk?", resolved = "documented")
            else ->
                base.copy(phase = request.phase)
        }
    }

    private fun AgentRunCompleteRequest.withQuestionOr(
        asksQuestion: Boolean,
        attempt: Int,
        questionPhase: String,
        questionText: String,
        resolved: String,
    ): AgentRunCompleteRequest =
        if (asksQuestion && attempt <= 1) {
            copy(phase = questionPhase, outcome = "questions", summaryText = questionText)
        } else {
            copy(phase = resolved)
        }

    companion object {
        val DEFAULT_SUBTASKS: List<AgentRunSubtaskPayload> = subtasks("development", "review", "test", "summary")

        /** Bouwt een subtask-lijst uit type-namen (trackerValue), bv. `subtasks("review")`. */
        fun subtasks(vararg types: String): List<AgentRunSubtaskPayload> =
            types.map { type ->
                AgentRunSubtaskPayload(type = type, title = "${type.replaceFirstChar { it.uppercase() }} subtask")
            }
    }
}
