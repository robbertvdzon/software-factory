package nl.vdzon.softwarefactory.runtime.services

import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.softwarefactory.knowledge.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.runtime.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.AgentRunCompleteResponse
import nl.vdzon.softwarefactory.runtime.AgentRunEventPayload
import nl.vdzon.softwarefactory.runtime.workspaces.AgentWorkspaceCleaner
import nl.vdzon.softwarefactory.runtime.RuntimeApi
import nl.vdzon.softwarefactory.support.SupportApi
import nl.vdzon.softwarefactory.youtrack.AgentRole
import nl.vdzon.softwarefactory.youtrack.TrackerField
import nl.vdzon.softwarefactory.youtrack.TrackerFieldUpdate
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.youtrack.ProcessedCommentsApi
import nl.vdzon.softwarefactory.runtime.repositories.AgentEventRepository
import nl.vdzon.softwarefactory.orchestrator.AgentRunCompletionRecord
import nl.vdzon.softwarefactory.orchestrator.AgentRunRepository
import nl.vdzon.softwarefactory.orchestrator.CompletedAgentRun
import nl.vdzon.softwarefactory.orchestrator.CostMonitor
import nl.vdzon.softwarefactory.orchestrator.CreditsPauseCoordinator
import nl.vdzon.softwarefactory.orchestrator.StoryRunRepository
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime

@Service
class AgentRunCompletionService(
    private val agentRunRepository: AgentRunRepository,
    private val storyRunRepository: StoryRunRepository,
    private val agentEventRepository: AgentEventRepository,
    private val issueTrackerClient: YouTrackApi,
    private val processedCommentService: ProcessedCommentsApi,
    private val pullRequestClient: GitHubApi,
    private val knowledgeApi: KnowledgeApi,
    private val agentWorkspaceCleaner: AgentWorkspaceCleaner,
    private val costMonitor: CostMonitor,
    private val creditsPauseCoordinator: CreditsPauseCoordinator,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
) : RuntimeApi {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun complete(request: AgentRunCompleteRequest): ResponseEntity<AgentRunCompleteResponse> {
        val completion = request.toCompletionRecord()
        logger.info(
            "Agent completion received: story={} role={} container={} outcome={} success={} durationMs={} turns={} totalTokens={} costUsd={} eventCount={}",
            request.storyKey,
            request.role,
            request.containerName,
            request.outcome,
            request.isSuccessful(),
            request.durationMs,
            request.numTurns,
            request.totalTokens,
            request.costUsdEst,
            request.events.size,
        )
        val completed = agentRunRepository.complete(
            containerName = request.containerName,
            completion = completion,
            endedAt = OffsetDateTime.now(clock),
        ) ?: run {
            logger.warn(
                "Agent completion ignored because no active run was found: story={} role={} container={} outcome={}",
                request.storyKey,
                request.role,
                request.containerName,
                request.outcome,
            )
            return ResponseEntity.notFound().build()
        }

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
            logger.info(
                "Agent reported pull request: story={} role={} agentRunId={} storyRunId={} branch={} prNumber={} prUrl={}",
                request.storyKey,
                request.role,
                completed.agentRunId,
                completed.storyRunId,
                root.path("branchName").asText("<unknown>"),
                root.path("prNumber").asInt(),
                SupportApi.default().redact(root.optionalText("prUrl") ?: "<none>"),
            )
        }
        request.events.forEach { event ->
            agentEventRepository.append(
                agentRunId = completed.agentRunId,
                kind = event.kind,
                payload = mapOf("payload" to SupportApi.default().redact(event.payload)),
            )
        }
        updateTracker(request)
        persistKnowledgeUpdates(request, completed.storyRunId)
        markProcessedTrackerComments(request)
        markClaimedPrComments(request, completed.storyRunId)
        cleanupWorkspace(completed, request)

        logger.info(
            "Agent finished: story={} role={} agentRunId={} storyRunId={} container={} outcome={} success={} totalTokens={} inputTokens={} outputTokens={} cacheReadTokens={} cacheCreationTokens={} costUsd={} durationMs={} summary=\"{}\"",
            request.storyKey,
            request.role,
            completed.agentRunId,
            completed.storyRunId,
            request.containerName,
            request.outcome,
            request.isSuccessful(),
            request.totalTokens,
            request.inputTokens,
            request.outputTokens,
            request.cacheReadInputTokens,
            request.cacheCreationInputTokens,
            request.costUsdEst,
            request.durationMs,
            request.summaryForLog(),
        )

        return ResponseEntity.ok(AgentRunCompleteResponse(completed.agentRunId, completed.storyRunId))
    }

    private fun updateTracker(request: AgentRunCompleteRequest) {
        val role = AgentRole.entries.firstOrNull { it.markerKeyPart == request.role } ?: return
        runCatching {
            if (request.isSuccessful()) {
                request.phase?.takeIf { it.isNotBlank() }?.let { phase ->
                    issueTrackerClient.updateIssueFields(request.storyKey, TrackerFieldUpdate.of(TrackerField.AI_PHASE to phase))
                }
                issueTrackerClient.postAgentComment(request.storyKey, role, request.summaryText.orEmpty())
            } else {
                issueTrackerClient.updateIssueFields(
                    request.storyKey,
                    TrackerFieldUpdate.of(TrackerField.ERROR to "${role.commentPrefix} ${request.summaryText.orEmpty()}"),
                )
            }
        }.onFailure { exception ->
            logger.warn("Failed to update Issue after agent completion for {} {}", request.storyKey, role, exception)
        }
    }

    private fun persistKnowledgeUpdates(request: AgentRunCompleteRequest, storyRunId: Long) {
        if (request.knowledgeUpdates.isEmpty()) {
            return
        }
        val storyRun = storyRunRepository.get(storyRunId) ?: return
        request.knowledgeUpdates.forEach { update ->
            runCatching {
                knowledgeApi.upsert(
                    AgentKnowledgeUpdateRequest(
                        targetRepo = storyRun.targetRepo,
                        role = request.role,
                        category = update.category,
                        key = update.key,
                        content = update.content,
                        updatedByStory = request.storyKey,
                    ),
                )
            }.onFailure { exception ->
                logger.warn("Failed to persist agent knowledge update for {} {}", request.storyKey, request.role, exception)
            }
        }
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
            val comments = issueTrackerClient.processableComments(issue, role) { comment, commentRole ->
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
