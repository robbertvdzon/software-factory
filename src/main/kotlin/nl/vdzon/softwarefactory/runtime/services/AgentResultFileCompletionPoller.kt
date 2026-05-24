package nl.vdzon.softwarefactory.runtime.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import nl.vdzon.softwarefactory.orchestrator.AgentRunRecord
import nl.vdzon.softwarefactory.orchestrator.AgentRunRepository
import nl.vdzon.softwarefactory.orchestrator.AgentRuntime
import nl.vdzon.softwarefactory.orchestrator.StoryRunRepository
import nl.vdzon.softwarefactory.runtime.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.RuntimeApi
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
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${softwarefactory.agent-result-poll-ms:5000}")
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
                } else {
                    missingResultRequest(run, storyRun.storyKey)
                }
            }
            ?: missingResultRequest(run, storyRun.storyKey)

        runtimeApi.complete(request.copy(
            storyKey = request.storyKey.ifBlank { storyRun.storyKey },
            role = request.role.ifBlank { run.role.markerKeyPart },
            containerName = request.containerName.ifBlank { run.containerName },
        ))
    }

    private fun missingResultRequest(run: AgentRunRecord, storyKey: String): AgentRunCompleteRequest =
        AgentRunCompleteRequest(
            storyKey = storyKey,
            role = run.role.markerKeyPart,
            containerName = run.containerName,
            outcome = "error",
            summaryText = "Agent container stopped without writing /work/agent-result.json.",
            exitCode = 1,
        )
}
