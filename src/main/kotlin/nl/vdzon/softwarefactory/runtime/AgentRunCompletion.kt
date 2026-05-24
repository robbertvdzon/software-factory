package nl.vdzon.softwarefactory.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.softwarefactory.github.PullRequestClient
import nl.vdzon.softwarefactory.tracker.AgentCommentContext
import nl.vdzon.softwarefactory.tracker.AgentRole
import nl.vdzon.softwarefactory.tracker.IssueTrackerClient
import nl.vdzon.softwarefactory.tracker.ProcessedCommentService
import nl.vdzon.softwarefactory.orchestrator.AgentRunCompletionRecord
import nl.vdzon.softwarefactory.orchestrator.AgentRunRepository
import nl.vdzon.softwarefactory.orchestrator.CompletedAgentRun
import nl.vdzon.softwarefactory.orchestrator.CostMonitor
import nl.vdzon.softwarefactory.orchestrator.CreditsPauseCoordinator
import nl.vdzon.softwarefactory.orchestrator.StoryRunRepository
import org.slf4j.LoggerFactory
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
    private val storyRunRepository: StoryRunRepository,
    private val agentEventRepository: AgentEventRepository,
    private val issueTrackerClient: IssueTrackerClient,
    private val processedCommentService: ProcessedCommentService,
    private val pullRequestClient: PullRequestClient,
    private val agentWorkspaceCleaner: AgentWorkspaceCleaner,
    private val costMonitor: CostMonitor,
    private val creditsPauseCoordinator: CreditsPauseCoordinator,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun complete(request: AgentRunCompleteRequest): ResponseEntity<AgentRunCompleteResponse> {
        val completion = request.toCompletionRecord()
        val completed = agentRunRepository.complete(
            containerName = request.containerName,
            completion = completion,
            endedAt = OffsetDateTime.now(clock),
        ) ?: return ResponseEntity.notFound().build()

        agentRunRepository.addUsageToStoryRun(completed.storyRunId, completion)
        storyRunRepository.get(completed.storyRunId)?.let { storyRun ->
            costMonitor.checkCompletedRun(request.storyKey, storyRun)
        }
        if (completion.outcome == "credits-exhausted") {
            creditsPauseCoordinator.handleCreditsExhausted(request.storyKey, completion.summaryText)
        }
        request.events.firstOrNull { it.kind == "github-pr" }?.let { event ->
            val root = objectMapper.readTree(event.payload)
            storyRunRepository.updatePullRequest(
                storyRunId = completed.storyRunId,
                branchName = root.path("branchName").asText(),
                prNumber = root.path("prNumber").asInt(),
                prUrl = root.path("prUrl").asText().takeIf { it.isNotBlank() && it != "null" },
                baseBranch = root.optionalText("baseBranch"),
                branchPrefix = root.optionalText("branchPrefix"),
                previewUrlTemplate = root.optionalText("previewUrlTemplate"),
                previewNamespaceTemplate = root.optionalText("previewNamespaceTemplate"),
                previewDbSecretRecipe = root.optionalText("previewDbSecretRecipe"),
            )
        }
        request.events.forEach { event ->
            agentEventRepository.append(
                agentRunId = completed.agentRunId,
                kind = event.kind,
                payload = mapOf("payload" to SecretRedactor.redact(event.payload)),
            )
        }
        markProcessedTrackerComments(request)
        markClaimedPrComments(request, completed.storyRunId)
        cleanupWorkspace(completed, request)

        return ResponseEntity.ok(AgentRunCompleteResponse(completed.agentRunId, completed.storyRunId))
    }

    private fun cleanupWorkspace(completed: CompletedAgentRun, request: AgentRunCompleteRequest) {
        runCatching {
            agentWorkspaceCleaner.cleanup(completed.workspacePath, failed = !request.isSuccessful())
        }.onFailure { exception ->
            logger.warn("Failed to cleanup workspace for {}", request.containerName, exception)
        }
    }

    private fun markProcessedTrackerComments(request: AgentRunCompleteRequest) {
        if (!request.isSuccessful()) {
            return
        }
        val role = AgentRole.entries.firstOrNull { it.markerKeyPart == request.role } ?: return
        runCatching {
            val issue = issueTrackerClient.getIssue(request.storyKey)
            val comments = AgentCommentContext.processableComments(issue, role) { comment, commentRole ->
                processedCommentService.isProcessed(issue.key, comment.id, commentRole)
            }
            comments.forEach { comment ->
                processedCommentService.markProcessed(issue.key, comment.id, role)
            }
        }.onFailure { exception ->
            logger.warn("Failed to mark Issue comments processed for {} {}", request.storyKey, role, exception)
        }
    }

    private fun markClaimedPrComments(request: AgentRunCompleteRequest, storyRunId: Long) {
        if (request.role != "developer") {
            return
        }
        val storyRun = storyRunRepository.get(storyRunId) ?: return
        val prNumber = storyRun.prNumber ?: return
        runCatching {
            val comments = pullRequestClient.claimedFactoryComments(storyRun.targetRepo, prNumber)
            comments.forEach { comment ->
                if (request.isSuccessful()) {
                    pullRequestClient.markCommentDone(storyRun.targetRepo, comment.id)
                } else {
                    pullRequestClient.markCommentFailed(storyRun.targetRepo, comment.id)
                }
            }
        }.onFailure { exception ->
            logger.warn("Failed to mark PR comments for {}", storyRun.storyKey, exception)
        }
    }

    private fun com.fasterxml.jackson.databind.JsonNode.optionalText(fieldName: String): String? =
        path(fieldName).asText().takeIf { it.isNotBlank() && it != "null" }
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
    fun isSuccessful(): Boolean =
        !outcome.contains("error", ignoreCase = true) &&
            !outcome.contains("failed", ignoreCase = true)

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
