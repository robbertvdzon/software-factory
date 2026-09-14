package nl.vdzon.softwarefactory.runtime.v2

import com.fasterxml.jackson.databind.JsonNode
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.runtime.models.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.models.AgentRunKnowledgeUpdatePayload
import nl.vdzon.softwarefactory.runtime.models.AgentRunSubtaskPayload
import java.math.BigDecimal
import java.time.Duration

class AgentRuntimeV2ResultMapper {
    fun completed(
        storyKey: String,
        role: AgentRole,
        runtimeJobId: String,
        job: RuntimeJobView,
        result: RuntimeJobResultView,
    ): AgentRunCompleteRequest {
        val payload = result.result
        val verification = result.verificationResult
        val verificationFailed = verification?.blocksPublication() == true
        // Alleen de developer heeft een domeinloopback (development-rejected). Voor andere muterende
        // rollen (documenter) is een rode verificatie een gewone mislukte run met zichtbare Error:
        // de agentfase ("documented") mag niet doorschuiven terwijl er niets gepusht is.
        val developerLoopback = verificationFailed && role == AgentRole.DEVELOPER
        val verificationFailure = verificationFailed && !developerLoopback
        return AgentRunCompleteRequest(
            storyKey = storyKey,
            role = role.markerKeyPart,
            containerName = runtimeJobId,
            phase = when {
                developerLoopback -> "development-rejected"
                verificationFailure -> null
                else -> payload.text("phase")
            },
            outcome = when {
                developerLoopback -> "development-rejected"
                verificationFailure -> "verification-failed"
                else -> payload.text("outcome") ?: job.status.name.lowercase()
            },
            summaryText = if (verificationFailure) {
                verificationFailureSummary(runtimeJobId, requireNotNull(verification))
            } else {
                verificationSummary(summaryWithQuestions(payload), verification)
            },
            exitCode = if (!verificationFailure && (job.status == RuntimeJobStatus.SUCCEEDED || developerLoopback)) 0 else 1,
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
                .ifBlank { endedWithoutDetails(job) },
            exitCode = 1,
        )

    /**
     * Een job die CANCELLED/TIMED_OUT eindigt zonder foutdetails is vrijwel altijd door de harde
     * time-out van de factory afgebroken (`executionTimeoutSeconds` = `SF_AGENT_HARD_TIMEOUT_MINUTES`,
     * zie AgentRuntimeV2Adapter). Zeg dat er dan bij, mét de looptijd, zodat de fout op de subtaak
     * direct te duiden is i.p.v. alleen "ended as CANCELLED".
     */
    private fun endedWithoutDetails(job: RuntimeJobView): String {
        val base = "Agent Runtime job ended as ${job.status}"
        if (job.status != RuntimeJobStatus.CANCELLED && job.status != RuntimeJobStatus.TIMED_OUT) return base
        val endedAt = job.completedAt ?: job.updatedAt
        val minutes = Duration.between(job.createdAt, endedAt).toMinutes()
        return "$base after $minutes min — most likely the factory's hard timeout " +
            "(SF_AGENT_HARD_TIMEOUT_MINUTES); retry the subtask or raise the timeout."
    }

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

    /**
     * Bewust zonder output-tails: die belanden in het Error-veld en de faalclassificatie
     * (AgentFailurePolicy) zou op willekeurige woorden als "quota" of "rate limit" in buildoutput
     * kunnen aanslaan. De volledige tails staan in het opgeslagen Runtime-resultaat van de job.
     */
    private fun verificationFailureSummary(runtimeJobId: String, verification: RuntimeVerificationResult): String {
        val red = verification.commands
            .filter { it.status == RuntimeVerificationCommandStatus.FAILED || it.status == RuntimeVerificationCommandStatus.TIMEOUT }
            .joinToString(", ") { "${it.id} (${it.status}, exitCode=${it.exitCode ?: "none"})" }
            .ifBlank { "geen commando-details" }
        return "Repositoryverificatie bleef ${verification.status} na ${verification.agentRounds} agentronde(s); " +
            "er is niets gecommit of gepusht. Rood: $red. Output staat in het Runtime-resultaat van job $runtimeJobId."
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
}
