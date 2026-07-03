package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.core.AgentDispatchRequest
import nl.vdzon.softwarefactory.runtime.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.AgentRunEventPayload
import nl.vdzon.softwarefactory.runtime.AgentRunSubtaskPayload
import nl.vdzon.softwarefactory.core.AgentRole

/**
 * Configureerbaar, deterministisch script voor [TestAgentRuntime]. Per test stel je in:
 *  - welke rollen op hun **eerste poging een vraag stellen** (`*-with-questions`) en daarna afronden;
 *  - welke **subtaken** de planner declareert.
 *
 * De agent produceert altijd de "kale" eindfasen (`refined`, `developed`, `reviewed`, `tested`,
 * `summarized`); de **approve/reject-gate** ligt daarna bij de orchestrator (auto-approve) of de
 * gebruiker (via de UI). Rejects worden dus via de UI gedreven, niet via dit script.
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
    var summarizerAsksQuestion: Boolean = false
    var documenterAsksQuestion: Boolean = false

    /** De subtaken die de planner declareert (volgorde = keten-volgorde). */
    var plannedSubtasks: List<AgentRunSubtaskPayload> = DEFAULT_SUBTASKS

    /**
     * Laat de developer bij het afronden (`developed`) een `github-pr`-event rapporteren, zoals de
     * echte agentworker doet: het PR-nummer komt uit [E2eTestConfig.FAKE_GITHUB] en bereikt de
     * story-run via het normale completion-pad (`AgentRunCompletionService.recordReportedBranch`),
     * zodat de merge-subtaak later `storyRun.prNumber` kan gebruiken. Default UIT: de meeste
     * flow-tests bewijzen juist óók het foutpad "merge zonder PR-nummer" (SF-244).
     */
    var developerReportsPullRequest: Boolean = false

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
                val result = base.withQuestionOr(developerAsksQuestion, attempt, "developed-with-questions", "Welke variant wil je geïmplementeerd hebben?", resolved = "developed")
                if (developerReportsPullRequest && result.phase == "developed") {
                    result.copy(events = result.events + githubPrEvent(request))
                } else {
                    result
                }
            }
            AgentRole.REVIEWER ->
                base.withQuestionOr(reviewerAsksQuestion, attempt, "reviewed-with-questions", "Is deze review-aanpak akkoord?", resolved = "reviewed")
            AgentRole.TESTER ->
                base.withQuestionOr(testerAsksQuestion, attempt, "tested-with-questions", "Welke testdekking verwacht je precies?", resolved = "tested")
            AgentRole.SUMMARIZER ->
                base.withQuestionOr(summarizerAsksQuestion, attempt, "summary-with-questions", "Moet de samenvatting ook de openstaande risico's bevatten?", resolved = "summarized")
            AgentRole.DOCUMENTER ->
                base.withQuestionOr(documenterAsksQuestion, attempt, "documentation-with-questions", "Welke documentatie wil je dat ik bijwerk?", resolved = "documented")
            else ->
                base.copy(phase = request.phase)
        }
    }

    /**
     * Het `github-pr`-event in hetzelfde wire-formaat als de echte agentworker: JSON met
     * branchName/prNumber/prUrl. De branch komt uit de dispatch-request (de factory geeft de
     * story-branch aan de agent mee); het PR-nummer deelt de fake GitHub-API uit.
     */
    private fun githubPrEvent(request: AgentDispatchRequest): AgentRunEventPayload {
        val branchName = requireNotNull(request.branchName?.takeIf { it.isNotBlank() }) {
            "developerReportsPullRequest vereist een branchName op de dispatch-request"
        }
        val pr = E2eTestConfig.FAKE_GITHUB.openPullRequest(branchName)
        return AgentRunEventPayload(
            kind = "github-pr",
            payload = """{"branchName":"$branchName","prNumber":${pr.number},"prUrl":"${pr.url}"}""",
        )
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
