package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.orchestrator.AgentRunCompletionRecord
import nl.vdzon.softwarefactory.support.SupportApi
import org.springframework.http.ResponseEntity

/**
 * Public API of the runtime module.
 *
 * The runtime module owns execution state around agent containers: workspaces,
 * logs, run events and completion handling. Web adapters call this API when an
 * agent run has finished; the runtime also uses it after reading agent result
 * files from completed container workspaces.
 */
interface RuntimeApi {
    fun complete(request: AgentRunCompleteRequest): ResponseEntity<AgentRunCompleteResponse>
}

data class AgentRunCompleteRequest(
    val storyKey: String,
    val role: String,
    val containerName: String,
    val phase: String? = null,
    val outcome: String,
    val summaryText: String? = null,
    val exitCode: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadInputTokens: Int = 0,
    val cacheCreationInputTokens: Int = 0,
    val numTurns: Int = 0,
    val durationMs: Int = 0,
    val costUsdEst: Double = 0.0,
    val events: List<AgentRunEventPayload> = emptyList(),
    val knowledgeUpdates: List<AgentRunKnowledgeUpdatePayload> = emptyList(),
) {
    val totalTokens: Int =
        inputTokens + outputTokens + cacheReadInputTokens + cacheCreationInputTokens

    fun isSuccessful(): Boolean =
        exitCode == 0 &&
            !outcome.contains("error", ignoreCase = true) &&
            !outcome.contains("failed", ignoreCase = true)

    fun summaryForLog(maxLength: Int = 500): String =
        SupportApi.default().redact(summaryText.orEmpty())
            .lineSequence()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .take(maxLength)

    fun toCompletionRecord(): AgentRunCompletionRecord =
        AgentRunCompletionRecord(
            outcome = outcome,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheReadInputTokens = cacheReadInputTokens,
            cacheCreationInputTokens = cacheCreationInputTokens,
            numTurns = numTurns,
            durationMs = durationMs,
            costUsdEst = costUsdEst,
            summaryText = summaryText,
        )
}

data class AgentRunEventPayload(
    val kind: String,
    val payload: String,
)

data class AgentRunKnowledgeUpdatePayload(
    val category: String,
    val key: String,
    val content: String,
)

data class AgentRunCompleteResponse(
    val agentRunId: Long,
    val storyRunId: Long,
)
