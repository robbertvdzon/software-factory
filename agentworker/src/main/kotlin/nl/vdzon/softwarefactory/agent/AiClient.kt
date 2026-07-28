package nl.vdzon.softwarefactory.agent

import nl.vdzon.softwarefactory.agent.ai.claude.ClaudeCodeAiClient
import nl.vdzon.softwarefactory.agent.ai.codex.CodexAiClient
import nl.vdzon.softwarefactory.agent.ai.copilot.CopilotAiClient
import nl.vdzon.softwarefactory.agent.ai.dummy.DummyAiClient
import nl.vdzon.softwarefactory.agent.ai.unsupported.NotImplementedAiClient
import nl.vdzon.softwarefactory.core.AgentRole
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
    /**
     * Alleen voor AUDITOR: het pad waar de agent zijn rapport naartoe schrijft. Zowel de prompt
     * (die dit pad noemt) als [nl.vdzon.softwarefactory.agentworker.cli.runAgent] (die het bestand
     * terugleest) gebruiken deze ene waarde, zodat ze nooit uiteen kunnen lopen.
     */
    val auditReportPath: String = AgentPaths.AUDIT_REPORT_FILE,
)

/** Vaste paden in de gemounte agent-workspace (`/work`); de CLI mag ze via env overrulen. */
object AgentPaths {
    const val AUDIT_REPORT_FILE = "/work/audit-report.md"
}

data class AgentOutcome(
    val phase: String?,
    val comment: String,
    val outcome: String,
    val exitCode: Int = 0,
    // ZERO als default: een client die usage vergeet te zetten mag nooit verzonnen
    // token-/kostcijfers naar de factory rapporteren. Mock-usage zet DummyAiClient expliciet.
    val usage: AgentUsage = AgentUsage.ZERO,
    val knowledgeUpdates: List<AgentKnowledgeDraft> = emptyList(),
    val events: List<AgentEvent> = emptyList(),
    /** Door de planner gedeclareerde subtaken (fase 3). */
    val subtasks: List<AgentSubtaskSpec> = emptyList(),
    /** Alleen voor AUDITOR: score + korte toelichting, indien de audit een zinnige metric heeft. */
    val auditScore: Double? = null,
    val auditScoreLabel: String? = null,
    /** Alleen voor AUDITOR: hoogstens 1 voorgestelde vervolg-story (title+description), of null. */
    val proposedStoryTitle: String? = null,
    val proposedStoryDescription: String? = null,
)

/**
 * Door de planner gedeclareerde subtask-spec. Wire-formaat: `type` als trackerValue
 * ("development"/"review"/"test"/"manual"/"summary"). De orchestrator
 * materialiseert deze in de tracker-database.
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
        val ZERO: AgentUsage = AgentUsage(0, 0, 0, 0, 0, 0, 0.0)

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
