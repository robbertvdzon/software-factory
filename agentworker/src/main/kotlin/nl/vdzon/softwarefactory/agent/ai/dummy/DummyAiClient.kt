package nl.vdzon.softwarefactory.agent.ai.dummy

import nl.vdzon.softwarefactory.agent.AgentContext
import nl.vdzon.softwarefactory.agent.AgentOutcome
import nl.vdzon.softwarefactory.agent.AgentSubtaskSpec
import nl.vdzon.softwarefactory.agent.AgentUsage
import nl.vdzon.softwarefactory.agent.AiClient
import nl.vdzon.softwarefactory.core.AgentRole
import kotlin.random.Random

class DummyAiClient(
    private val random: Random = Random.Default,
) : AiClient {
    override val supplier: String = "mock"

    // Mock-runs krijgen expliciet gesimuleerde usage; de AgentOutcome-default is bewust ZERO.
    override fun run(context: AgentContext): AgentOutcome =
        outcome(context).copy(usage = AgentUsage.random(random))

    private fun outcome(context: AgentContext): AgentOutcome =
        if (context.forcedOutcome == "credits-exhausted") {
            AgentOutcome(
                phase = null,
                comment = "(dummy) AI-credits zijn uitgeput; probeer later opnieuw.",
                outcome = "credits-exhausted",
                exitCode = 1,
            )
        } else when (context.role) {
            AgentRole.REFINER -> refiner(context)
            AgentRole.PLANNER -> planner(context)
            AgentRole.DEVELOPER -> developer(context)
            AgentRole.REVIEWER -> reviewer(context)
            AgentRole.TESTER -> tester(context)
            AgentRole.SUMMARIZER -> summarizer()
            AgentRole.DOCUMENTER -> documenter(context)
            AgentRole.ASSISTANT, // assistent draait server-side, nooit via de agentworker-CLI
            AgentRole.COST_MONITOR,
            AgentRole.ORCHESTRATOR,
            -> error("Role ${context.role} is not an agent role.")
        }

    private fun refiner(context: AgentContext): AgentOutcome =
        when (context.forcedOutcome ?: weighted("ok", "questions")) {
            "questions" -> AgentOutcome(
                phase = "refined-with-questions",
                comment = "(dummy) vraag aan PO: kun je de gewenste acceptatiecriteria bevestigen?",
                outcome = "questions",
            )
            "error" -> errorOutcome("refiner")
            else -> AgentOutcome("refined", "(dummy) refinement OK", "ok")
        }

    private fun planner(context: AgentContext): AgentOutcome =
        when (context.forcedOutcome ?: weighted("ok", "questions")) {
            "questions" -> AgentOutcome(
                phase = "planned-with-questions",
                comment = "(dummy) vraag aan PO: welke aanpak heeft de voorkeur?",
                outcome = "questions",
            )
            "error" -> errorOutcome("planner")
            else -> AgentOutcome(
                phase = "planned",
                comment = "(dummy) plan opgesteld",
                outcome = "ok",
                subtasks = listOf(
                    AgentSubtaskSpec("development", "(dummy) Implementeer de hoofdwijziging"),
                    AgentSubtaskSpec("review", "(dummy) Story-brede review"),
                    AgentSubtaskSpec("test", "(dummy) Story-brede test"),
                    AgentSubtaskSpec("summary", "(dummy) Eindsamenvatting"),
                ),
            )
        }

    private fun developer(context: AgentContext): AgentOutcome =
        when (context.forcedOutcome) {
            "error" -> errorOutcome("developer")
            "questions" -> AgentOutcome(
                phase = "developed-with-questions",
                comment = "(dummy) vraag aan PO: klopt deze aanpak?",
                outcome = "questions",
            )
            else -> AgentOutcome("developed", "(dummy) placeholder-wijziging gepushed", "ok")
        }

    private fun reviewer(context: AgentContext): AgentOutcome =
        when (context.forcedOutcome ?: "ok") {
            "feedback" -> AgentOutcome(
                phase = "review-rejected",
                comment = "(dummy) findings: controleer de edge cases rond lege input.",
                outcome = "review-rejected",
            )
            "questions" -> AgentOutcome(
                phase = "reviewed-with-questions",
                comment = "(dummy) vraag aan PO: is deze afhandeling gewenst?",
                outcome = "questions",
            )
            "error" -> errorOutcome("reviewer")
            else -> AgentOutcome("reviewed", "(dummy) review OK", "ok")
        }

    private fun tester(context: AgentContext): AgentOutcome =
        when (context.forcedOutcome ?: "ok") {
            "bug" -> AgentOutcome(
                phase = "test-rejected",
                comment = "(dummy) bug: reproductie faalt op het happy path.",
                outcome = "test-rejected",
            )
            "questions" -> AgentOutcome(
                phase = "tested-with-questions",
                comment = "(dummy) vraag aan PO: welke testdata gebruiken?",
                outcome = "questions",
            )
            "error" -> errorOutcome("tester")
            else -> AgentOutcome("tested", "(dummy) tests OK", "ok")
        }

    private fun summarizer(): AgentOutcome =
        AgentOutcome(
            phase = "summarized",
            comment = "(dummy) Eindsamenvatting: de story is verfijnd, ontwikkeld, gereviewd en succesvol getest.",
            outcome = "summarized",
        )

    private fun documenter(context: AgentContext): AgentOutcome =
        when (context.forcedOutcome ?: "ok") {
            "questions" -> AgentOutcome(
                phase = "documentation-with-questions",
                comment = "(dummy) vraag aan PO: welke documentatie wil je bijgewerkt zien?",
                outcome = "questions",
            )
            "error" -> errorOutcome("documenter")
            else -> AgentOutcome(
                phase = "documented",
                comment = "(dummy) documentatie bijgewerkt obv de story.",
                outcome = "documented",
            )
        }

    private fun weighted(ok: String, other: String): String =
        if (random.nextInt(100) < 70) ok else other

    private fun errorOutcome(role: String): AgentOutcome =
        AgentOutcome(
            phase = null,
            comment = "(dummy) $role blokkeerde met een geforceerde fout.",
            outcome = "error",
            exitCode = 1,
        )
}
