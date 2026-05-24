package nl.vdzon.softwarefactory.agent.ai.dummy

import nl.vdzon.softwarefactory.agent.AgentContext
import nl.vdzon.softwarefactory.agent.AgentOutcome
import nl.vdzon.softwarefactory.agent.AiClient
import nl.vdzon.softwarefactory.youtrack.AgentRole
import kotlin.random.Random

class DummyAiClient(
    private val random: Random = Random.Default,
) : AiClient {
    override val supplier: String = "mock"

    override fun run(context: AgentContext): AgentOutcome =
        if (context.forcedOutcome == "credits-exhausted") {
            AgentOutcome(
                phase = null,
                comment = "(dummy) AI-credits zijn uitgeput; probeer later opnieuw.",
                outcome = "credits-exhausted",
                exitCode = 1,
            )
        } else when (context.role) {
            AgentRole.REFINER -> refiner(context)
            AgentRole.DEVELOPER -> developer(context)
            AgentRole.REVIEWER -> reviewer(context)
            AgentRole.TESTER -> tester(context)
            AgentRole.COST_MONITOR,
            AgentRole.ORCHESTRATOR,
            -> error("Role ${context.role} is not an agent role.")
        }

    private fun refiner(context: AgentContext): AgentOutcome =
        when (context.forcedOutcome ?: weighted("ok", "questions")) {
            "questions" -> AgentOutcome(
                phase = "refined-with-questions-for-user",
                comment = "(dummy) vraag aan PO: kun je de gewenste acceptatiecriteria bevestigen?",
                outcome = "questions",
            )
            "error" -> errorOutcome("refiner")
            else -> AgentOutcome("refined-finished", "(dummy) refinement OK", "ok")
        }

    private fun developer(context: AgentContext): AgentOutcome =
        when (context.forcedOutcome) {
            "error" -> errorOutcome("developer")
            else -> AgentOutcome("developed", "(dummy) placeholder-wijziging gepushed", "ok")
        }

    private fun reviewer(context: AgentContext): AgentOutcome =
        when (context.forcedOutcome ?: weighted("ok", "feedback")) {
            "feedback" -> AgentOutcome(
                phase = "reviewed-with-feedback-for-developer",
                comment = "(dummy) feedback: controleer de edge cases rond lege input.",
                outcome = "feedback",
            )
            "error" -> errorOutcome("reviewer")
            else -> AgentOutcome("review-finished", "(dummy) review OK", "ok")
        }

    private fun tester(context: AgentContext): AgentOutcome =
        when (context.forcedOutcome ?: weighted("ok", "bug")) {
            "bug" -> AgentOutcome(
                phase = "tested-with-feedback-for-developer",
                comment = "(dummy) bug: reproductie faalt op het happy path.",
                outcome = "bug",
            )
            "error" -> errorOutcome("tester")
            else -> AgentOutcome("tested-successfully", "(dummy) tests OK", "ok")
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
