package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.orchestrator.AgentRunCompletionRecord
import nl.vdzon.softwarefactory.orchestrator.AgentRunRepository
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.OffsetDateTime

@RestController
class AgentRunCompletionController(
    private val completionService: AgentRunCompletionService,
) {
    @PostMapping("/agent-run/complete")
    fun complete(@RequestBody request: AgentRunCompleteRequest): ResponseEntity<AgentRunCompleteResponse> =
        completionService.complete(request)
}

@Service
class AgentRunCompletionService(
    private val agentRunRepository: AgentRunRepository,
    private val agentEventRepository: AgentEventRepository,
    private val clock: Clock,
) {
    fun complete(request: AgentRunCompleteRequest): ResponseEntity<AgentRunCompleteResponse> {
        val completion = request.toCompletionRecord()
        val completed = agentRunRepository.complete(
            containerName = request.containerName,
            completion = completion,
            endedAt = OffsetDateTime.now(clock),
        ) ?: return ResponseEntity.notFound().build()

        agentRunRepository.addUsageToStoryRun(completed.storyRunId, completion)
        request.events.forEach { event ->
            agentEventRepository.append(
                agentRunId = completed.agentRunId,
                kind = event.kind,
                payload = mapOf("payload" to SecretRedactor.redact(event.payload)),
            )
        }

        return ResponseEntity.ok(AgentRunCompleteResponse(completed.agentRunId, completed.storyRunId))
    }
}

data class AgentRunCompleteRequest(
    val storyKey: String,
    val role: String,
    val containerName: String,
    val outcome: String,
    val summaryText: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadInputTokens: Int = 0,
    val cacheCreationInputTokens: Int = 0,
    val numTurns: Int = 0,
    val durationMs: Int = 0,
    val costUsdEst: Double = 0.0,
    val events: List<AgentRunEventPayload> = emptyList(),
) {
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

data class AgentRunCompleteResponse(
    val agentRunId: Long,
    val storyRunId: Long,
)
