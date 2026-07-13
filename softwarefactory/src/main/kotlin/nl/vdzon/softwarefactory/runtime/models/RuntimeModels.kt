package nl.vdzon.softwarefactory.runtime.models

import nl.vdzon.softwarefactory.runtime.models.*
import nl.vdzon.softwarefactory.runtime.types.*

import nl.vdzon.softwarefactory.contract.AgentResultVerificationEvidence
import nl.vdzon.softwarefactory.core.AgentRunCompletionRecord
import nl.vdzon.softwarefactory.support.SupportApi

data class AgentRunCompleteRequest(
    val storyKey: String, val role: String, val containerName: String, val phase: String? = null,
    val outcome: String, val summaryText: String? = null, val exitCode: Int = 0,
    val inputTokens: Int = 0, val outputTokens: Int = 0, val cacheReadInputTokens: Int = 0,
    val cacheCreationInputTokens: Int = 0, val numTurns: Int = 0, val durationMs: Int = 0,
    val costUsdEst: Double = 0.0, val events: List<AgentRunEventPayload> = emptyList(),
    val knowledgeUpdates: List<AgentRunKnowledgeUpdatePayload> = emptyList(),
    val subtasks: List<AgentRunSubtaskPayload> = emptyList(),
    val verificationEvidence: AgentResultVerificationEvidence? = null,
) {
    val totalTokens: Int = inputTokens + outputTokens + cacheReadInputTokens + cacheCreationInputTokens
    fun isSuccessful(): Boolean = exitCode == 0 && !outcome.contains("error", true) && !outcome.contains("failed", true)
    fun summaryForLog(maxLength: Int = 500): String = SupportApi.default().redact(summaryText.orEmpty())
        .lineSequence().joinToString(" ") { it.trim() }.replace(Regex("\\s+"), " ").take(maxLength)
    fun toCompletionRecord(): AgentRunCompletionRecord = AgentRunCompletionRecord(
        outcome, inputTokens, outputTokens, cacheReadInputTokens, cacheCreationInputTokens,
        numTurns, durationMs, costUsdEst, summaryText,
    )
}

data class AgentRunEventPayload(val kind: String, val payload: String)
data class AgentRunKnowledgeUpdatePayload(val category: String, val key: String, val content: String)
data class AgentRunSubtaskPayload(
    val type: String, val title: String, val description: String? = null,
    val model: String? = null, val effort: String? = null,
)
data class AgentRunCompleteResponse(val agentRunId: Long, val storyRunId: Long)
