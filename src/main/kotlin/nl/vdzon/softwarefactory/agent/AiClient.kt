package nl.vdzon.softwarefactory.agent

import nl.vdzon.softwarefactory.runtime.AgentRunEventPayload
import nl.vdzon.softwarefactory.youtrack.AgentRole
import java.nio.file.Path
import kotlin.random.Random

interface AiClient {
    val supplier: String

    fun run(context: AgentContext): AgentOutcome
}

data class AgentContext(
    val ticketKey: String,
    val role: AgentRole,
    val taskMarkdown: String,
    val forcedOutcome: String?,
    val repoRoot: Path? = null,
    val supplier: String? = null,
    val model: String? = null,
    val effort: String? = null,
)

data class AgentOutcome(
    val phase: String?,
    val comment: String,
    val outcome: String,
    val exitCode: Int = 0,
    val usage: AgentUsage = AgentUsage.random(),
    val knowledgeUpdates: List<AgentKnowledgeDraft> = emptyList(),
    val events: List<AgentRunEventPayload> = emptyList(),
)

data class AgentKnowledgeDraft(
    val category: String,
    val key: String,
    val content: String,
)

data class AgentUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadInputTokens: Int,
    val cacheCreationInputTokens: Int,
    val numTurns: Int,
    val durationMs: Int,
    val costUsdEst: Double,
) {
    companion object {
        fun random(random: Random = Random.Default): AgentUsage =
            AgentUsage(
                inputTokens = random.nextInt(1000, 5001),
                outputTokens = random.nextInt(500, 2001),
                cacheReadInputTokens = random.nextInt(0, 501),
                cacheCreationInputTokens = random.nextInt(0, 301),
                numTurns = random.nextInt(1, 6),
                durationMs = random.nextInt(5000, 15001),
                costUsdEst = random.nextDouble(0.01, 0.25),
            )
    }
}

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

class NotImplementedAiClient(
    override val supplier: String,
) : AiClient {
    override fun run(context: AgentContext): AgentOutcome =
        AgentOutcome(
            phase = null,
            comment = "AI supplier '$supplier' is nog niet geimplementeerd.",
            outcome = "error-supplier-not-implemented",
            exitCode = 1,
        )
}

object AiClientFactory {
    fun create(env: Map<String, String>): AiClient =
        when (val supplier = normalizedSupplier(env["SF_AI_SUPPLIER"])) {
            "mock",
            "dummy",
            "none",
            "",
            -> DummyAiClient()
            "claude" -> ClaudeCodeAiClient(env = env)
            "openai",
            "microsoft",
            -> NotImplementedAiClient(supplier)
            else -> NotImplementedAiClient(supplier)
        }

    fun normalizedSupplier(value: String?): String =
        value?.trim()?.lowercase().orEmpty()

    fun eventSupplier(value: String?): String =
        when (val supplier = normalizedSupplier(value)) {
            "",
            "dummy",
            "none",
            -> "mock"
            else -> supplier
        }
}
