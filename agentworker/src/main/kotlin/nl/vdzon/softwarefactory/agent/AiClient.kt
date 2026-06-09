package nl.vdzon.softwarefactory.agent

import nl.vdzon.softwarefactory.agent.ai.claude.ClaudeCodeAiClient
import nl.vdzon.softwarefactory.agent.ai.codex.CodexAiClient
import nl.vdzon.softwarefactory.agent.ai.copilot.CopilotAiClient
import nl.vdzon.softwarefactory.agent.ai.dummy.DummyAiClient
import nl.vdzon.softwarefactory.agent.ai.unsupported.NotImplementedAiClient
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
    val events: List<AgentEvent> = emptyList(),
    /** Door de planner gedeclareerde subtaken (fase 3). */
    val subtasks: List<AgentSubtaskSpec> = emptyList(),
)

/**
 * Door de planner gedeclareerde subtask-spec. Wire-formaat: `type` als trackerValue
 * ("development"/"review"/"test"/"manual"/"summary"). De orchestrator
 * materialiseert deze in YouTrack.
 */
data class AgentSubtaskSpec(
    val type: String,
    val title: String,
    val description: String? = null,
    val model: String? = null,
    val effort: String? = null,
)

data class AgentEvent(
    val kind: String,
    val payload: String,
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
        /** Vaste mock-duur: de agent slaapt deze tijd in [AgentCli.finish]. Niet meer random. */
        const val MOCK_DURATION_MS: Int = 4_000

        fun random(random: Random = Random.Default): AgentUsage =
            AgentUsage(
                inputTokens = random.nextInt(1000, 5001),
                outputTokens = random.nextInt(500, 2001),
                cacheReadInputTokens = random.nextInt(0, 501),
                cacheCreationInputTokens = random.nextInt(0, 301),
                numTurns = random.nextInt(1, 6),
                durationMs = MOCK_DURATION_MS,
                costUsdEst = random.nextDouble(0.01, 0.25),
            )
    }
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
            "codex",
            -> CodexAiClient(env = env)
            "copilot",
            "github",
            -> CopilotAiClient(env = env)
            "microsoft" -> NotImplementedAiClient(supplier)
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
