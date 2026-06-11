package nl.vdzon.softwarefactory.runtime.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import nl.vdzon.softwarefactory.orchestrator.AgentRunRecord
import nl.vdzon.softwarefactory.orchestrator.AgentRunRepository
import nl.vdzon.softwarefactory.orchestrator.AgentRuntime
import nl.vdzon.softwarefactory.orchestrator.StoryRunRepository
import nl.vdzon.softwarefactory.runtime.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.RuntimeApi
import nl.vdzon.softwarefactory.runtime.repositories.AgentEventRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

@Component
class AgentResultFileCompletionPoller(
    private val agentRunRepository: AgentRunRepository,
    private val storyRunRepository: StoryRunRepository,
    private val agentRuntime: AgentRuntime,
    private val runtimeApi: RuntimeApi,
    private val agentEventRepository: AgentEventRepository,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${softwarefactory.agent-result-poll-ms:2000}")
    fun poll() {
        agentRunRepository.activeRuns().forEach { run ->
            runCatching { process(run) }
                .onFailure { logger.warn("Failed to process agent result for {}", run.containerName, it) }
        }
    }

    private fun process(run: AgentRunRecord) {
        if (agentRuntime.isContainerRunning(run.containerName)) {
            return
        }

        val storyRun = storyRunRepository.get(run.storyRunId) ?: return
        val request = run.workspacePath
            ?.let { Path.of(it).resolve("agent-result.json") }
            ?.let { resultFile ->
                if (resultFile.exists()) {
                    objectMapper.readValue<AgentRunCompleteRequest>(resultFile.readText())
                } else missingResultRequest(run, storyRun.storyKey)
            }
            ?: missingResultRequest(run, storyRun.storyKey)

        // Rond de run af die we hier verwerken: gebruik z'n EIGEN container-naam als identiteit.
        // Het resultaat-bestand in een gedeelde story-workspace kan van een vorige/sibling-run zijn;
        // op die (niet-actieve) container matcht `complete()` niets en blijft de run eeuwig "actief"
        // → de poller pakt 'm elke ronde opnieuw op (de "no active run found"-lus).
        runtimeApi.complete(request.copy(
            storyKey = request.storyKey.ifBlank { storyRun.storyKey },
            role = request.role.ifBlank { run.role.markerKeyPart },
            containerName = run.containerName,
        ))
    }

    private fun missingResultRequest(run: AgentRunRecord, storyKey: String): AgentRunCompleteRequest {
        val recentLogs = recentDockerLogs(run.id)
        val summary = buildString {
            append("Agent container stopped without writing /work/agent-result.json.")
            if (recentLogs.isNotBlank()) {
                append("\n\nLast Docker log lines:\n")
                append(recentLogs)
            }
        }
        logger.warn(
            "Agent result file missing: story={} role={} container={} workspace={}{}",
            storyKey,
            run.role.markerKeyPart,
            run.containerName,
            run.workspacePath ?: "<unknown>",
            recentLogs.takeIf { it.isNotBlank() }?.let { "\nLast Docker log lines:\n$it" }.orEmpty(),
        )
        return AgentRunCompleteRequest(
            storyKey = storyKey,
            role = run.role.markerKeyPart,
            containerName = run.containerName,
            outcome = "error",
            summaryText = summary,
            exitCode = 1,
        )
    }

    private fun recentDockerLogs(agentRunId: Long): String =
        dockerLogEvents(agentRunId)
            .asReversed()
            .mapNotNull { event ->
                val line = objectMapper.readTree(event.payloadText).path("line").asText("").takeIf { it.isNotBlank() }
                line?.let { "${event.kind}: ${it.removeDockerTimestamp().summarizeDockerLine()}" }
            }
            .joinToString("\n")
            .take(4000)

    private fun dockerLogEvents(agentRunId: Long) =
        agentEventRepository
            .recentForAgentRun(agentRunId, kinds = setOf("docker-stderr"), limit = 20)
            .takeIf { it.isNotEmpty() }
            ?: agentEventRepository.recentForAgentRun(agentRunId, kinds = setOf("docker-stdout"), limit = 5)

    private fun String.removeDockerTimestamp(): String =
        replace(Regex("^\\d{4}-\\d{2}-\\d{2}T\\S+\\s+"), "")

    private fun String.summarizeDockerLine(): String {
        val node = runCatching { objectMapper.readTree(this) }.getOrNull() ?: return take(500)
        val type = node.path("type").asText("")
        val messageText = node.path("message").path("content")
            .takeIf { it.isArray }
            ?.firstOrNull { it.path("type").asText("") == "text" }
            ?.path("text")
            ?.asText("")
            ?.takeIf { it.isNotBlank() }
        val toolName = node.path("message").path("content")
            .takeIf { it.isArray }
            ?.firstOrNull { it.path("type").asText("") == "tool_use" }
            ?.let { "${it.path("name").asText("tool")} ${it.path("input").toString().take(180)}" }
        return listOfNotNull(
            type.takeIf { it.isNotBlank() }?.let { "type=$it" },
            messageText,
            toolName,
            node.path("subtype").asText("").takeIf { it.isNotBlank() }?.let { "subtype=$it" },
        ).joinToString(" | ").ifBlank { take(500) }.take(500)
    }
}
