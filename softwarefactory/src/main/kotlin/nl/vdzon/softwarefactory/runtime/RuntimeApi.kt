package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.core.AgentRunCompletionRecord
import nl.vdzon.softwarefactory.contract.AgentResultVerificationEvidence
import nl.vdzon.softwarefactory.support.SupportApi

/**
 * Public API of the runtime module.
 *
 * The runtime module owns execution state around agent containers: workspaces,
 * logs, run events and completion handling. Web adapters call this API when an
 * agent run has finished; the runtime also uses it after reading agent result
 * files from completed container workspaces.
 */
interface RuntimeApi {
    fun complete(request: AgentRunCompleteRequest): CompletionOutcome
}

/**
 * Domeinresultaat van [RuntimeApi.complete]. Bewust géén Spring-MVC-type (ResponseEntity):
 * de module-API blijft framework-vrij; de web-adapter vertaalt dit naar HTTP
 * ([Completed] → 200 met [AgentRunCompleteResponse], [NoActiveRun] → 404).
 */
sealed interface CompletionOutcome {
    /** De afronding is verwerkt op een actieve run. */
    data class Completed(val agentRunId: Long, val storyRunId: Long) : CompletionOutcome

    /** Geen actieve run voor deze container: de melding is genegeerd. */
    data object NoActiveRun : CompletionOutcome
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
    val subtasks: List<AgentRunSubtaskPayload> = emptyList(),
    val verificationEvidence: AgentResultVerificationEvidence? = null,
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

/** Door de planner gedeclareerde subtask (fase 3); `type` = trackerValue. */
data class AgentRunSubtaskPayload(
    val type: String,
    val title: String,
    val description: String? = null,
    val model: String? = null,
    val effort: String? = null,
)

data class AgentRunCompleteResponse(
    val agentRunId: Long,
    val storyRunId: Long,
)
