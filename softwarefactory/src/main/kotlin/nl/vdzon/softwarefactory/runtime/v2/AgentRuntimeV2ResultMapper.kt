package nl.vdzon.softwarefactory.runtime.v2

import com.fasterxml.jackson.databind.JsonNode
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.runtime.models.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.models.AgentRunKnowledgeUpdatePayload
import nl.vdzon.softwarefactory.runtime.models.AgentRunSubtaskPayload
import java.math.BigDecimal

class AgentRuntimeV2ResultMapper {
    fun completed(
        storyKey: String,
        role: AgentRole,
        runtimeJobId: String,
        job: RuntimeJobView,
        result: RuntimeJobResultView,
    ): AgentRunCompleteRequest {
        val payload = result.result
        val verificationFailed = result.verificationResult?.status in FAILED_VERIFICATION_STATUSES
        val phase = if (verificationFailed && role == AgentRole.DEVELOPER) {
            "development-rejected"
        } else {
            payload.text("phase")
        }
        return AgentRunCompleteRequest(
            storyKey = storyKey,
            role = role.markerKeyPart,
            containerName = runtimeJobId,
            phase = phase,
            outcome = if (verificationFailed) "development-rejected" else payload.text("outcome") ?: job.status.name.lowercase(),
            summaryText = verificationSummary(summaryWithQuestions(payload), result.verificationResult),
            exitCode = if (job.status == RuntimeJobStatus.SUCCEEDED || verificationFailed) 0 else 1,
            inputTokens = result.usageSummary.metric("INPUT_TOKENS"),
            outputTokens = result.usageSummary.metric("OUTPUT_TOKENS"),
            cacheReadInputTokens = result.usageSummary.metric("CACHE_READ_INPUT_TOKENS"),
            cacheCreationInputTokens = result.usageSummary.metric("CACHE_CREATION_INPUT_TOKENS"),
            numTurns = result.verificationResult?.agentRounds ?: result.usageSummary.attemptCount,
            costUsdEst = result.usageSummary.costs
                .filter { it.currency == "USD" }
                .fold(BigDecimal.ZERO) { total, cost -> total + cost.amount }
                .toDouble(),
            knowledgeUpdates = payload.path("knowledgeUpdates").mapNotNull { node ->
                val category = node.text("category") ?: return@mapNotNull null
                val key = node.text("key") ?: return@mapNotNull null
                val content = node.text("content") ?: return@mapNotNull null
                AgentRunKnowledgeUpdatePayload(category, key, content)
            },
            subtasks = payload.path("subtasks").mapNotNull { node ->
                val type = node.text("type") ?: return@mapNotNull null
                val title = node.text("title") ?: return@mapNotNull null
                AgentRunSubtaskPayload(type, title, node.text("description"))
            },
            descriptionSummary = payload.text("descriptionSummary"),
            shortDescriptionSummary = payload.text("shortDescriptionSummary"),
            runtimeRepositoryResult = result.repositoryResult,
            runtimeVerificationResult = result.verificationResult,
            runtimeArtifacts = result.artifacts,
        )
    }

    fun failed(storyKey: String, role: AgentRole, job: RuntimeJobView): AgentRunCompleteRequest =
        AgentRunCompleteRequest(
            storyKey = storyKey,
            role = role.markerKeyPart,
            containerName = job.id.toString(),
            outcome = "error",
            summaryText = listOfNotNull(job.errorCode, job.errorMessage).joinToString(": ")
                .ifBlank { "Agent Runtime job ended as ${job.status}" },
            exitCode = 1,
        )

    private fun RuntimeUsageSummary.metric(name: String): Int =
        metrics.firstOrNull { it.metric == name }?.quantity?.toInt() ?: 0

    private fun JsonNode.text(name: String): String? =
        path(name).takeUnless { it.isMissingNode || it.isNull }?.asText()?.takeIf(String::isNotBlank)

    private fun verificationSummary(summary: String?, verification: RuntimeVerificationResult?): String? {
        if (verification == null) return summary
        val evidence = buildString {
            appendLine("[FACTORY VERIFICATION EVIDENCE]")
            appendLine("status=${verification.status}; agentRounds=${verification.agentRounds}; configVersion=${verification.configVersion}")
            verification.commands.forEach { command ->
                appendLine("${command.id}: ${command.status}; exitCode=${command.exitCode}; durationMs=${command.durationMillis}")
                command.outputTail?.takeIf(String::isNotBlank)?.let { appendLine(it.take(4_000)) }
            }
        }.trim()
        return listOfNotNull(summary, evidence).joinToString("\n\n")
    }

    private fun summaryWithQuestions(payload: JsonNode): String? {
        val summary = payload.text("summaryText")
        val questions = payload.path("questions").mapNotNull { it.asText().takeIf(String::isNotBlank) }
        if (questions.isEmpty()) return summary
        return listOfNotNull(
            summary,
            questions.joinToString(prefix = "Vragen:\n- ", separator = "\n- "),
        ).joinToString("\n\n")
    }

    companion object {
        private val FAILED_VERIFICATION_STATUSES = setOf(
            RuntimeVerificationStatus.FAILED,
            RuntimeVerificationStatus.CONFIG_MISSING,
            RuntimeVerificationStatus.CONFIG_INVALID,
            RuntimeVerificationStatus.TIMEOUT,
        )
    }
}
