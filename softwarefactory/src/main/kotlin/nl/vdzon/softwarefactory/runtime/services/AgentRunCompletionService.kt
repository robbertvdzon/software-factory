package nl.vdzon.softwarefactory.runtime.services

import nl.vdzon.softwarefactory.runtime.models.*
import nl.vdzon.softwarefactory.runtime.types.*

import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.knowledge.models.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.core.contracts.AgentFailurePolicy
import nl.vdzon.softwarefactory.core.contracts.TesterScreenshots
import nl.vdzon.softwarefactory.core.contracts.StoryPhase
import nl.vdzon.softwarefactory.core.contracts.SubtaskPhase
import nl.vdzon.softwarefactory.runtime.models.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.types.CompletionOutcome
import nl.vdzon.softwarefactory.runtime.workspaces.AgentWorkspaceCleaner
import nl.vdzon.softwarefactory.core.contracts.StoryWorkspaceApi
import nl.vdzon.softwarefactory.runtime.RuntimeApi
import nl.vdzon.softwarefactory.support.SupportApi
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.tracker.TrackerCapabilities
import nl.vdzon.softwarefactory.tracker.ProcessedCommentsApi
import nl.vdzon.softwarefactory.runtime.repositories.AgentEventRepository
import nl.vdzon.softwarefactory.core.contracts.AgentRunRepository
import nl.vdzon.softwarefactory.core.contracts.CompletedAgentRun
import nl.vdzon.softwarefactory.core.contracts.CostMonitor
import nl.vdzon.softwarefactory.core.contracts.CreditsPauseCoordinator
import nl.vdzon.softwarefactory.core.contracts.FactoryStateChangedEvent
import nl.vdzon.softwarefactory.core.contracts.StoryRunRepository
import nl.vdzon.softwarefactory.core.contracts.StoryRunPullRequestUpdate
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
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
    private val issueTrackerClient: TrackerCapabilities,
    private val processedCommentService: ProcessedCommentsApi,
    private val pullRequestClient: GitHubApi,
    private val knowledgeApi: KnowledgeApi,
    private val agentWorkspaceCleaner: AgentWorkspaceCleaner,
    private val storyWorkspaceService: StoryWorkspaceApi? = null,
    private val costMonitor: CostMonitor,
    private val creditsPauseCoordinator: CreditsPauseCoordinator,
    private val factoryEnvironmentProvider: ConfigApi,
    // Verplicht (Spring injecteert de @Component-bean): de vroegere default construeerde stil een
    // materializer met een LEGE ProjectConfiguration, waardoor een vergeten bean onopgemerkt
    // verkeerde (lege) project-config zou gebruiken.
    private val subtaskPlanMaterializer: SubtaskPlanMaterializer,
    private val testerVerificationEvidenceValidator: TesterVerificationEvidenceValidator =
        TesterVerificationEvidenceValidator(agentRunRepository, nl.vdzon.softwarefactory.git.GitApi.default()),
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

    /**
     * Hoofdflow van een agent-afronding. Elke stap heeft z'n eigen soft-fail-semantiek
     * (runCatching + log) in de stap-functie zelf; alleen "geen actieve run" breekt de flow af.
     */
    override fun complete(request: AgentRunCompleteRequest): CompletionOutcome {
        val validatedRequest = testerVerificationEvidenceValidator.enforce(request)
        logCompletionReceived(validatedRequest)
        val completed = persistCompletion(validatedRequest) ?: return CompletionOutcome.NoActiveRun
        registerUsageAndCosts(validatedRequest, completed)
        writeFinalStoryAfterSummarizer(validatedRequest, completed)
        val repositorySynced = syncRepositoryAfterAgent(validatedRequest, completed)
        recordReportedBranch(validatedRequest, completed)
        appendAgentEvents(validatedRequest, completed)
        syncTesterScreenshots(validatedRequest, completed)
        // Na een mislukte repo-sync GEEN tracker-updates: anders schuift de fase door terwijl
        // het werk niet gecommit/gepusht is (de Error-melding staat dan al op de story).
        if (repositorySynced) {
            updateTracker(validatedRequest, completed.storyRunId)
            persistKnowledgeUpdates(validatedRequest, completed.storyRunId)
            markProcessedTrackerComments(validatedRequest)
            markClaimedPrComments(validatedRequest, completed.storyRunId)
        }
        cleanupWorkspace(completed, validatedRequest)
        logAgentFinished(validatedRequest, completed)
        wakeOrchestratorPoller(validatedRequest)
        return CompletionOutcome.Completed(completed.agentRunId, completed.storyRunId)
    }

    private fun logCompletionReceived(request: AgentRunCompleteRequest) {
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
    }

    /** Persisteer de afronding op de actieve run; `null` = geen actieve run gevonden (→ 404 in de web-laag). */
    private fun persistCompletion(request: AgentRunCompleteRequest): CompletedAgentRun? =
        agentRunRepository.complete(
            containerName = request.containerName,
            completion = request.toCompletionRecord(),
            endedAt = OffsetDateTime.now(clock),
        ) ?: run {
            logger.warn(
                "Agent completion ignored because no active run was found: story={} role={} container={} outcome={}",
                request.storyKey,
                request.role,
                request.containerName,
                request.outcome,
            )
            null
        }

    /** Tel het verbruik op bij de story-run, check het kostenbudget en activeer zo nodig de credits-pauze. */
    private fun registerUsageAndCosts(request: AgentRunCompleteRequest, completed: CompletedAgentRun) {
        agentRunRepository.addUsageToStoryRun(completed.storyRunId, request.toCompletionRecord())
        storyRunRepository.get(completed.storyRunId)?.let { storyRun ->
            costMonitor.checkCompletedRun(request.storyKey, storyRun)
        }
        if (request.outcome == "credits-exhausted") {
            creditsPauseCoordinator.handleCreditsExhausted(request.storyKey, request.summaryText)
        }
    }

    /**
     * Een agent kan zelf een branch/PR rapporteren (event `github-pr`/`repository-branch`);
     * neem die gegevens over op de story-run zodat preview/merge ermee verder kunnen.
     */
    private fun recordReportedBranch(request: AgentRunCompleteRequest, completed: CompletedAgentRun) {
        request.events.firstOrNull { it.kind == "github-pr" || it.kind == "repository-branch" }?.let { event ->
            val root = objectMapper.readTree(event.payload)
            storyRunRepository.updatePullRequest(StoryRunPullRequestUpdate(
                storyRunId = completed.storyRunId,
                branchName = root.path("branchName").asText(),
                prNumber = root.optionalInt("prNumber"),
                prUrl = root.path("prUrl").asText().takeIf { it.isNotBlank() && it != "null" },
                baseBranch = root.optionalText("baseBranch"),
                branchPrefix = root.optionalText("branchPrefix"),
                previewUrlTemplate = root.optionalText("previewUrlTemplate"),
                previewNamespaceTemplate = root.optionalText("previewNamespaceTemplate"),
                previewDbSecretRecipe = root.optionalText("previewDbSecretRecipe"),
            ))
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
    }

    /** Bewaar alle agent-events (geredact: tokens/URL's met secrets mogen de database niet in). */
    private fun appendAgentEvents(request: AgentRunCompleteRequest, completed: CompletedAgentRun) {
        request.events.forEach { event ->
            agentEventRepository.append(
                agentRunId = completed.agentRunId,
                kind = event.kind,
                payload = mapOf("payload" to SupportApi.default().redact(event.payload)),
            )
        }
    }

    private fun logAgentFinished(request: AgentRunCompleteRequest, completed: CompletedAgentRun) {
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
    }

    // Wek de orchestrator-poller direct: de agent is klaar en heeft de story/subtask bijgewerkt,
    // dus de keten kan meteen door zonder te wachten op het volgende poll-interval.
    private fun wakeOrchestratorPoller(request: AgentRunCompleteRequest) {
        runCatching { eventPublisher?.publishEvent(FactoryStateChangedEvent("agent-complete:${request.storyKey}")) }
            .onFailure { logger.debug("Kon FactoryStateChangedEvent niet publiceren (genegeerd).", it) }
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
        return runCatching {
            val sync = workspaceService.syncAfterAgent(storyRun, role)
            storyRunRepository.updatePullRequest(StoryRunPullRequestUpdate(
                storyRunId = completed.storyRunId,
                branchName = sync.branchName,
                prNumber = sync.prNumber,
                prUrl = sync.prUrl,
                baseBranch = sync.baseBranch,
                branchPrefix = sync.branchPrefix,
                previewUrlTemplate = sync.deploymentConfig.previewUrlTemplate,
                previewNamespaceTemplate = sync.deploymentConfig.previewNamespaceTemplate,
                previewDbSecretRecipe = sync.deploymentConfig.previewDbSecretRecipe,
            ))
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
                subtaskPlanMaterializer.materializeIfPlanned(request, role)
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
            val issue = issueTrackerClient.getIssue(storyRun.storyKey)
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
        // Tester-screenshots horen op de PARENT-story: zowel Telegram (testerScreenshots)
        // als de screenshots-pagina lezen ze daar. De tester draait echter op een
        // test-subtaak, dus `request.storyKey` is die subtaak — resolve de parent.
        // Valt terug op de eigen key als er geen parent is.
        val targetKey = runCatching { issueTrackerClient.parentStoryKey(request.storyKey) }
            .getOrNull() ?: request.storyKey
        runCatching {
            val oldAttachments = issueTrackerClient.listIssueAttachments(targetKey)
                .filter { it.name.startsWith(TesterScreenshots.ATTACHMENT_PREFIX) }
            oldAttachments.forEach { attachment ->
                issueTrackerClient.deleteIssueAttachment(targetKey, attachment.id)
            }
            screenshots.forEachIndexed { index, screenshot ->
                val name = testerScreenshotAttachmentName(targetKey, completed.agentRunId, index + 1, screenshot)
                val uploaded = issueTrackerClient.uploadIssueAttachment(
                    issueKey = targetKey,
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
                targetKey,
                completed.agentRunId,
                oldAttachments.size,
                screenshots.size,
            )
        }.onFailure { exception ->
            logger.warn("Failed to sync tester screenshots for {}", targetKey, exception)
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
                .filter { it.extension.lowercase() in TesterScreenshots.EXTENSIONS }
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
        return "${TesterScreenshots.ATTACHMENT_PREFIX}${storyKey}__run-${agentRunId}__${index.toString().padStart(2, '0')}__$base.$extension"
    }

    private fun screenshotMimeType(path: Path): String =
        when (path.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }

    private fun AgentRunCompleteRequest.isRetryableFailure(): Boolean =
        AgentFailurePolicy.isRetryable(outcome, summaryText)

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
}
