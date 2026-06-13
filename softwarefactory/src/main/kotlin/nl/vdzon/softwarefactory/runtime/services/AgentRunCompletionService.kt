package nl.vdzon.softwarefactory.runtime.services

import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.knowledge.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.core.AgentFailurePolicy
import nl.vdzon.softwarefactory.core.StoryPhase
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.SubtaskSpec
import nl.vdzon.softwarefactory.core.SubtaskType
import nl.vdzon.softwarefactory.runtime.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.AgentRunCompleteResponse
import nl.vdzon.softwarefactory.runtime.AgentRunEventPayload
import nl.vdzon.softwarefactory.runtime.workspaces.AgentWorkspaceCleaner
import nl.vdzon.softwarefactory.core.StoryWorkspaceApi
import nl.vdzon.softwarefactory.runtime.RuntimeApi
import nl.vdzon.softwarefactory.support.SupportApi
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.youtrack.ProcessedCommentsApi
import nl.vdzon.softwarefactory.runtime.repositories.AgentEventRepository
import nl.vdzon.softwarefactory.core.AgentRunCompletionRecord
import nl.vdzon.softwarefactory.core.AgentRunRepository
import nl.vdzon.softwarefactory.core.CompletedAgentRun
import nl.vdzon.softwarefactory.core.CostMonitor
import nl.vdzon.softwarefactory.core.CreditsPauseCoordinator
import nl.vdzon.softwarefactory.core.FactoryStateChangedEvent
import nl.vdzon.softwarefactory.core.StoryRunRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.OffsetDateTime
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

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
    private val storyWorkspaceService: StoryWorkspaceApi? = null,
    private val costMonitor: CostMonitor,
    private val creditsPauseCoordinator: CreditsPauseCoordinator,
    private val factoryEnvironmentProvider: ConfigApi,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
    private val eventPublisher: ApplicationEventPublisher? = null,
) : RuntimeApi {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val maxTransientRetries: Int by lazy {
        factoryEnvironmentProvider.resolvedValues()
            .getOrDefault("SF_MAX_TRANSIENT_RETRIES", "2")
            .toIntOrNull()
            ?.takeIf { it >= 0 }
            ?: 2
    }
    private val autoSyncAfterAgent: Boolean by lazy {
        factoryEnvironmentProvider.resolvedValues().boolean("SF_AUTO_SYNC_AFTER_AGENT", default = true)
    }

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
        writeFinalStoryAfterSummarizer(request, completed)
        val repositorySynced = syncRepositoryAfterAgent(request, completed)
        request.events.firstOrNull { it.kind == "github-pr" || it.kind == "repository-branch" }?.let { event ->
            val root = objectMapper.readTree(event.payload)
            storyRunRepository.updatePullRequest(
                storyRunId = completed.storyRunId,
                branchName = root.path("branchName").asText(),
                prNumber = root.optionalInt("prNumber"),
                prUrl = root.path("prUrl").asText().takeIf { it.isNotBlank() && it != "null" },
                baseBranch = root.optionalText("baseBranch"),
                branchPrefix = root.optionalText("branchPrefix"),
                previewUrlTemplate = root.optionalText("previewUrlTemplate"),
                previewNamespaceTemplate = root.optionalText("previewNamespaceTemplate"),
                previewDbSecretRecipe = root.optionalText("previewDbSecretRecipe"),
            )
            logger.info(
                "Agent reported repository branch: story={} role={} agentRunId={} storyRunId={} branch={} prNumber={} prUrl={}",
                request.storyKey,
                request.role,
                completed.agentRunId,
                completed.storyRunId,
                root.path("branchName").asText("<unknown>"),
                root.optionalInt("prNumber") ?: 0,
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
        syncTesterScreenshots(request, completed)
        if (repositorySynced) {
            updateTracker(request, completed.storyRunId)
            persistKnowledgeUpdates(request, completed.storyRunId)
            markProcessedTrackerComments(request)
            markClaimedPrComments(request, completed.storyRunId)
        }
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

        // Wek de orchestrator-poller direct: de agent is klaar en heeft de story/subtask bijgewerkt,
        // dus de keten kan meteen door zonder te wachten op het volgende poll-interval.
        runCatching { eventPublisher?.publishEvent(FactoryStateChangedEvent("agent-complete:${request.storyKey}")) }
            .onFailure { logger.debug("Kon FactoryStateChangedEvent niet publiceren (genegeerd).", it) }

        return ResponseEntity.ok(AgentRunCompleteResponse(completed.agentRunId, completed.storyRunId))
    }

    private fun syncRepositoryAfterAgent(request: AgentRunCompleteRequest, completed: CompletedAgentRun): Boolean {
        if (!request.isSuccessful()) {
            return true
        }
        val role = AgentRole.entries.firstOrNull { it.markerKeyPart == request.role } ?: return true
        // Refinement-agents (refiner/planner) raken de repo niet — op story-niveau bestaat er nog
        // geen gecloonde workspace. Een sync zou hier falen ("repository is missing") en daardoor
        // de fase-update (Story Phase + subtaken + comment) blokkeren. Sla 'm dus over.
        if (role == AgentRole.REFINER || role == AgentRole.PLANNER) {
            return true
        }
        val storyRun = storyRunRepository.get(completed.storyRunId) ?: return true
        val workspaceService = storyWorkspaceService ?: return true
        if (!autoSyncAfterAgent) {
            logger.info(
                "Repository sync deferred until manual command: story={} role={} storyRunId={} repo={}",
                request.storyKey,
                request.role,
                completed.storyRunId,
                SupportApi.default().redact(storyRun.targetRepo),
            )
            return true
        }
        return runCatching {
            val sync = workspaceService.syncAfterAgent(storyRun, role)
            storyRunRepository.updatePullRequest(
                storyRunId = completed.storyRunId,
                branchName = sync.branchName,
                prNumber = sync.prNumber,
                prUrl = sync.prUrl,
                baseBranch = sync.baseBranch,
                branchPrefix = sync.branchPrefix,
                previewUrlTemplate = sync.deploymentConfig.previewUrlTemplate,
                previewNamespaceTemplate = sync.deploymentConfig.previewNamespaceTemplate,
                previewDbSecretRecipe = sync.deploymentConfig.previewDbSecretRecipe,
            )
            logger.info(
                "Repository synced after agent: story={} role={} branch={} committed={} pushed={} prNumber={} repo={}",
                request.storyKey,
                request.role,
                sync.branchName,
                sync.committed,
                sync.pushed,
                sync.prNumber ?: "<none>",
                SupportApi.default().redact(storyRun.targetRepo),
            )
            true
        }.getOrElse { exception ->
            val message = "[ORCHESTRATOR] Git sync na ${role.markerKeyPart} faalde: ${exception.message}"
            logger.warn("Repository sync failed after agent completion for {} {}", request.storyKey, role, exception)
            issueTrackerClient.updateIssueFields(request.storyKey, TrackerFieldUpdate.of(TrackerField.ERROR to message))
            false
        }
    }

    private fun updateTracker(request: AgentRunCompleteRequest, storyRunId: Long) {
        val role = AgentRole.entries.firstOrNull { it.markerKeyPart == request.role } ?: return
        runCatching {
            if (request.isSuccessful()) {
                val updates = mutableListOf<Pair<TrackerField, Any?>>()
                request.phase?.takeIf { it.isNotBlank() }?.let { phase ->
                    // Story-refinement-status (refiner/planner) → `Story Phase`;
                    // subtask-status (developer/reviewer/tester/summarizer) →
                    // `Subtask Phase`; legacy `AiPhase`-waarden → `AI Phase`.
                    val field = when {
                        StoryPhase.fromTracker(phase) != null -> TrackerField.STORY_PHASE
                        SubtaskPhase.fromTracker(phase) != null -> TrackerField.SUBTASK_PHASE
                        else -> TrackerField.AI_PHASE
                    }
                    updates += field to phase
                }
                if (updates.isNotEmpty()) {
                    issueTrackerClient.updateIssueFields(request.storyKey, TrackerFieldUpdate.of(*updates.toTypedArray()))
                }
                materializeSubtasksIfPlanned(request, role)
                issueTrackerClient.postAgentComment(request.storyKey, role, commentTextForTracker(role, request.summaryText.orEmpty()))
            } else if (request.isRetryableFailure() && retryableFailureCount(storyRunId, role) <= maxTransientRetries) {
                // v2: veld-agnostisch. Laat de actieve fase (Story Phase/Subtask Phase)
                // staan en leeg alleen `Error`; de recovery-poll herstart de actieve rol.
                issueTrackerClient.updateIssueFields(
                    request.storyKey,
                    TrackerFieldUpdate.of(TrackerField.ERROR to null),
                )
                logger.info(
                    "Retryable agent failure cleared error to retry via recovery: story={} role={} maxRetries={}",
                    request.storyKey,
                    request.role,
                    maxTransientRetries,
                )
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

    /**
     * Fase 3 — materialiseer de door de planner gedeclareerde subtaken, maar alleen
     * wanneer de planner `planned` bereikt (niet `planned-with-questions`). Idempotent:
     * sla specs over waarvan de titel al als subtask onder de parent bestaat.
     */
    private fun materializeSubtasksIfPlanned(request: AgentRunCompleteRequest, role: AgentRole) {
        if (role != AgentRole.PLANNER || request.phase != StoryPhase.PLANNED.trackerValue || request.subtasks.isEmpty()) {
            return
        }
        val existing = runCatching { issueTrackerClient.existingSubtaskTitles(request.storyKey) }
            .getOrElse { exception ->
                logger.warn("Kon bestaande subtaken niet ophalen voor {}; sla materialisatie over.", request.storyKey, exception)
                return
            }
        // Subtaken erven de AI-supplier van de story (README §7), anders pikt de
        // poller ze niet op (de supplier-check staat vóór de router).
        val parentSupplier = runCatching { issueTrackerClient.getIssue(request.storyKey).fields.aiSupplier }.getOrNull()
        request.subtasks
            .filter { it.title.isNotBlank() && it.title !in existing }
            .forEach { spec ->
                val subtaskType = SubtaskType.fromTracker(spec.type)
                if (subtaskType == null) {
                    logger.warn("Onbekend Subtask Type '{}' voor story {}; subtask overgeslagen.", spec.type, request.storyKey)
                    return@forEach
                }
                runCatching {
                    issueTrackerClient.createSubtask(
                        request.storyKey,
                        SubtaskSpec(subtaskType, spec.title, spec.description, spec.model, spec.effort),
                        supplier = parentSupplier,
                    )
                }.onFailure { exception ->
                    logger.warn("Subtask aanmaken faalde voor {} ({}).", request.storyKey, spec.title, exception)
                }
            }
    }

    private fun retryableFailureCount(storyRunId: Long, role: AgentRole): Int =
        agentRunRepository.recentForRole(storyRunId, role, maxTransientRetries + 1)
            .takeWhile { AgentFailurePolicy.isRetryable(it.outcome, it.summaryText) }
            .size

    private fun writeFinalStoryAfterSummarizer(request: AgentRunCompleteRequest, completed: CompletedAgentRun) {
        if (request.role != AgentRole.SUMMARIZER.markerKeyPart || !request.isSuccessful()) {
            return
        }
        val storyRun = storyRunRepository.get(completed.storyRunId) ?: return
        val workspaceService = storyWorkspaceService ?: return
        runCatching {
            val issue = issueTrackerClient.getIssue(request.storyKey)
            val finalStory = workspaceService.writeFinalStory(
                storyRun = storyRun,
                summary = issue.summary,
                description = issue.description,
                finalSummary = finalSummaryText(request.summaryText.orEmpty()),
            )
            logger.info(
                "Final story document written: story={} storyRunId={} path={}",
                request.storyKey,
                completed.storyRunId,
                finalStory ?: "<none>",
            )
        }.onFailure { exception ->
            logger.warn("Failed to write final story document for {}", request.storyKey, exception)
            issueTrackerClient.updateIssueFields(
                request.storyKey,
                TrackerFieldUpdate.of(
                    TrackerField.ERROR to "[ORCHESTRATOR] Definitief story-document schrijven faalde: ${exception.message}",
                ),
            )
        }
    }

    private fun commentTextForTracker(role: AgentRole, rawSummary: String): String =
        if (role == AgentRole.SUMMARIZER) finalSummaryText(rawSummary) else rawSummary

    private fun finalSummaryText(rawSummary: String): String =
        rawSummary
            .lines()
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.startsWith("{") &&
                    trimmed.endsWith("}") &&
                    (trimmed.contains("\"phase\"") || trimmed.contains("\"agent_tips_update\""))
            }
            .joinToString("\n")
            .trim()
            .ifBlank { rawSummary.trim() }

    private fun syncTesterScreenshots(request: AgentRunCompleteRequest, completed: CompletedAgentRun) {
        if (request.role != AgentRole.TESTER.markerKeyPart) {
            return
        }
        val screenshots = screenshotFiles(completed.workspacePath)
        runCatching {
            val oldAttachments = issueTrackerClient.listIssueAttachments(request.storyKey)
                .filter { it.name.startsWith(TESTER_SCREENSHOT_ATTACHMENT_PREFIX) }
            oldAttachments.forEach { attachment ->
                issueTrackerClient.deleteIssueAttachment(request.storyKey, attachment.id)
            }
            screenshots.forEachIndexed { index, screenshot ->
                val name = testerScreenshotAttachmentName(request.storyKey, completed.agentRunId, index + 1, screenshot)
                val uploaded = issueTrackerClient.uploadIssueAttachment(
                    issueKey = request.storyKey,
                    name = name,
                    mimeType = screenshotMimeType(screenshot),
                    bytes = Files.readAllBytes(screenshot),
                )
                agentEventRepository.append(
                    agentRunId = completed.agentRunId,
                    kind = "tester-screenshot",
                    payload = mapOf(
                        "name" to uploaded.name,
                        "attachmentId" to uploaded.id,
                        "url" to uploaded.url,
                    ),
                )
            }
            logger.info(
                "Tester screenshots synced: story={} agentRunId={} deleted={} uploaded={}",
                request.storyKey,
                completed.agentRunId,
                oldAttachments.size,
                screenshots.size,
            )
        }.onFailure { exception ->
            logger.warn("Failed to sync tester screenshots for {}", request.storyKey, exception)
        }
    }

    private fun screenshotFiles(workspacePath: String?): List<Path> {
        if (workspacePath.isNullOrBlank()) {
            return emptyList()
        }
        val root = Path.of(workspacePath).resolve("screenshots")
        if (!Files.exists(root)) {
            return emptyList()
        }
        return Files.walk(root).use { paths ->
            paths
                .filter { it.isRegularFile() }
                .filter { it.extension.lowercase() in screenshotExtensions }
                .sorted()
                .toList()
        }
    }

    private fun testerScreenshotAttachmentName(storyKey: String, agentRunId: Long, index: Int, path: Path): String {
        val extension = path.extension.lowercase().ifBlank { "png" }
        val base = path.name
            .substringBeforeLast('.', path.name)
            .replace(Regex("[^A-Za-z0-9_.-]+"), "-")
            .trim('-')
            .take(60)
            .ifBlank { "screenshot" }
        return "$TESTER_SCREENSHOT_ATTACHMENT_PREFIX${storyKey}__run-${agentRunId}__${index.toString().padStart(2, '0')}__$base.$extension"
    }

    private fun screenshotMimeType(path: Path): String =
        when (path.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }

    private fun AgentRunCompleteRequest.isRetryableFailure(): Boolean =
        AgentFailurePolicy.isRetryable(outcome, summaryText)

    private fun Map<String, String>.boolean(key: String, default: Boolean): Boolean {
        val value = this[key]?.takeIf { it.isNotBlank() } ?: return default
        return value.toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("$key must be either 'true' or 'false'.")
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

    private fun com.fasterxml.jackson.databind.JsonNode.optionalInt(fieldName: String): Int? =
        path(fieldName).takeIf { it.isInt }?.asInt()

    private companion object {
        const val TESTER_SCREENSHOT_ATTACHMENT_PREFIX = "factory-tester-screenshot__"
        val screenshotExtensions = setOf("png", "jpg", "jpeg", "webp")
    }
}
