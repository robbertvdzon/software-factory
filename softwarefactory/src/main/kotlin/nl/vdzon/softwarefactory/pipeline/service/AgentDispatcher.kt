package nl.vdzon.softwarefactory.pipeline.service

import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.core.contracts.AgentDispatchRequest
import nl.vdzon.softwarefactory.core.contracts.AgentInputAttachment
import nl.vdzon.softwarefactory.core.contracts.ProductFactoryAttachmentNames
import nl.vdzon.softwarefactory.core.contracts.BoardState
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AgentRunStart
import nl.vdzon.softwarefactory.core.contracts.AgentRunRepository
import nl.vdzon.softwarefactory.core.contracts.recordStarted
import nl.vdzon.softwarefactory.core.contracts.AgentRuntime
import nl.vdzon.softwarefactory.core.contracts.AiPhase
import nl.vdzon.softwarefactory.core.contracts.CostMonitor
import nl.vdzon.softwarefactory.core.contracts.IssueProcessResult
import nl.vdzon.softwarefactory.core.contracts.OrchestratorSettings
import nl.vdzon.softwarefactory.core.contracts.StoryRunRecord
import nl.vdzon.softwarefactory.core.contracts.StoryRunRepository
import nl.vdzon.softwarefactory.core.contracts.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.tracker.TrackerCapabilities
import nl.vdzon.softwarefactory.tracker.ProcessedCommentsApi
import nl.vdzon.softwarefactory.config.ProjectRepositoryCatalog
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.support.SupportApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime

data class AgentDispatchContext(
    val issue: TrackerIssue,
    val role: AgentRole,
    val sourcePhase: AiPhase?,
    val phaseField: TrackerField = TrackerField.AI_PHASE,
    val activePhaseValue: String = AiPhase.activeFor(role).trackerValue,
    val storyRunKey: String = issue.key,
    val loopbackCapped: Boolean = false,
    // Expliciete reden voor een loopback bij de v2-subtaakflow (waar sourcePhase altijd null is,
    // dus AiPhase.developerLoopbackReason() nooit iets oplevert) — zie
    // SubtaskExecutionCoordinator.developmentRejectedReason.
    val loopbackReason: String? = null,
    val budgetIssue: TrackerIssue = issue,
    val parentContext: TrackerIssue? = null,
    val targetRepo: String? = null,
)

/**
 * Gedeelde "start een agent"-mechaniek voor de pipeline: budget- en concurrency-checks, fase-veld
 * + AgentStartedAt zetten, de remote branch reserveren, de dispatch-request bouwen en de agent starten.
 *
 * Gebruikt door zowel [StoryRefinementCoordinator] (story-fasen) als
 * [SubtaskExecutionCoordinator] (subtask-pipeline).
 */
@Component
class AgentDispatcher(
    private val issueTrackerClient: TrackerCapabilities,
    private val agentRuntime: AgentRuntime,
    private val storyRunRepository: StoryRunRepository,
    private val agentRunRepository: AgentRunRepository,
    private val pullRequestClient: GitHubApi,
    private val processedCommentService: ProcessedCommentsApi,
    private val previewApi: PreviewApi,
    private val costMonitor: CostMonitor,
    private val projectRepoResolver: ProjectRepositoryCatalog,
    private val settings: OrchestratorSettings,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // tracker State-lane: een agent gaat dit issue actief verwerken → In Progress.
    private val stateInProgress = BoardState.IN_PROGRESS.laneName

    fun dispatch(context: AgentDispatchContext): IssueProcessResult {
        val issue = context.issue
        val role = context.role
        val sourcePhase = context.sourcePhase
        val phaseField = context.phaseField
        val activePhaseValue = context.activePhaseValue
        val storyRunKey = context.storyRunKey
        val loopbackCapped = context.loopbackCapped
        val loopbackReason = context.loopbackReason
        val budgetIssue = context.budgetIssue
        val parentContext = context.parentContext
        val targetRepo = context.targetRepo ?: projectRepoResolver.resolve(issue.fields.repo)
        if (targetRepo.isNullOrBlank()) {
            val message = "[ORCHESTRATOR] Geen repo: vul het `Repo`-veld met een projectnaam uit projects.yaml " +
                "of een repo-URL (subtaken erven de repo van hun parent-story). Leeg `Error` om opnieuw te proberen."
            issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
            return IssueProcessResult.Errored(issue.key, message)
        }

        val storyRun = storyRunRepository.openOrCreate(storyRunKey, targetRepo)
        val budgetResult = costMonitor.checkBudget(budgetIssue, storyRun)
        if (budgetResult.paused) {
            return IssueProcessResult.Skipped(issue.key, "budget-exceeded")
        }

        if (role == AgentRole.DEVELOPER && (sourcePhase.isDeveloperLoopbackPhase() || loopbackCapped)) {
            // De loopback-cap geldt per werk-eenheid. Voor een subtaak (issue.key != storyRunKey)
            // tellen we alleen díé subtaak; anders zou een story met meerdere subtaken het budget
            // delen en zou de eerste reject-loopback al door de cap knallen. Story-niveau telt breed.
            val developerRuns = if (issue.key != storyRunKey) {
                agentRunRepository.countForRoleAndSubtask(storyRun.id, AgentRole.DEVELOPER, issue.key)
            } else {
                agentRunRepository.countForRole(storyRun.id, AgentRole.DEVELOPER)
            }
            val maxDeveloperLoopbacks = issue.fields.developerLoopbackLimit(settings.maxDeveloperLoopbacks)
            if (developerRuns >= maxDeveloperLoopbacks + 1) {
                val message = "[ORCHESTRATOR] Developer-loopback cap bereikt (${maxDeveloperLoopbacks}x). " +
                    "Handmatige triage nodig. Geef feedback en leeg `Error` om opnieuw te proberen, " +
                    "of zet `Paused = true` en parkeer dit ticket."
                issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
                return IssueProcessResult.Errored(issue.key, message)
            }
        }

        if (!canDispatch(storyRunKey, role)) {
            return IssueProcessResult.Skipped(issue.key, "concurrency-cap")
        }

        val startedAt = OffsetDateTime.now(clock)
        issueTrackerClient.updateIssueFields(
            issue.key,
            TrackerFieldUpdate.of(
                phaseField to activePhaseValue,
                TrackerField.AGENT_STARTED_AT to startedAt,
                TrackerField.RETRY_AFTER to null,
            ),
        )
        issueTrackerClient.transitionIssue(issue.key, stateInProgress)

        return try {
            val preparedRun = prepareRemoteBranch(storyRun, role)
            val request = dispatchRequest(
                issue = issue,
                targetRepo = targetRepo,
                storyRun = preparedRun,
                role = role,
                activePhaseValue = activePhaseValue,
                sourcePhase = sourcePhase,
                loopbackReason = loopbackReason,
                parentContext = parentContext,
            )

            logger.info(
                "Starting agent dispatch: story={} role={} storyRunId={} sourcePhase={} " +
                    "targetPhase={} targetRepo={} prNumber={} " +
                    "branch={}",
                issue.key,
                role.markerKeyPart,
                storyRun.id,
                sourcePhase?.trackerValue ?: "<empty>",
                activePhaseValue,
                SupportApi.default().redact(targetRepo),
                storyRun.prNumber ?: "<none>",
                preparedRun.branchName ?: "<none>",
            )
            val dispatch = agentRuntime.dispatch(request)
            val agentRunId = agentRunRepository.recordStarted(AgentRunStart(
                storyRunId = storyRun.id,
                role = role,
                containerName = dispatch.containerName,
                model = dispatch.executionModel,
                effort = dispatch.executionMode,
                level = null,
                workspacePath = dispatch.workspacePath,
                // Voor subtaken (storyRun keyt op de parent) → markeer de run met de subtask-key.
                subtaskKey = issue.key.takeIf { storyRunKey != issue.key },
            ))
            dispatch.idempotencyKey?.let { key ->
                agentRunRepository.recordRuntimeJob(
                    agentRunId = agentRunId,
                    runtimeJobId = dispatch.containerName,
                    idempotencyKey = key,
                    status = "QUEUED",
                    phase = "QUEUED",
                )
            }
            logger.info(
                "Agent started: story={} role={} agentRunId={} storyRunId={} container={} " +
                    "phase={} vendor={} mode={} model={}",
                issue.key,
                role.markerKeyPart,
                agentRunId,
                storyRun.id,
                dispatch.containerName,
                activePhaseValue,
                dispatch.executionVendorId ?: "<unknown>",
                dispatch.executionMode ?: "<unknown>",
                dispatch.executionModel ?: "<unknown>",
            )
            IssueProcessResult.Dispatched(issue.key, role, dispatch.containerName)
        } catch (exception: Exception) {
            val message = "[ORCHESTRATOR] Agent dispatch voor ${role.markerKeyPart} faalde: ${exception.message}"
            logger.warn("Agent dispatch failed for {} {}", issue.key, role, exception)
            issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
            IssueProcessResult.Errored(issue.key, message)
        }
    }

    private fun dispatchRequest(
        issue: TrackerIssue,
        targetRepo: String,
        storyRun: StoryRunRecord,
        role: AgentRole,
        activePhaseValue: String,
        sourcePhase: AiPhase?,
        loopbackReason: String? = null,
        parentContext: TrackerIssue? = null,
    ): AgentDispatchRequest {
        val previewUrl = previewApi.render(storyRun.previewUrlTemplate, storyRun.prNumber)
        val previewNamespace = previewApi.render(storyRun.previewNamespaceTemplate, storyRun.prNumber)
        val prCommentContext = prCommentContext(storyRun, role, sourcePhase)
        // Subtaken erven supplier/model/effort van de parent-story als ze zelf leeg zijn.
        val supplier = issue.fields.aiSupplier?.takeIf { it.isNotBlank() }
            ?: parentContext?.fields?.aiSupplier?.takeIf { it.isNotBlank() }
        val model = issue.fields.aiModel?.takeIf { it.isNotBlank() }
            ?: parentContext?.fields?.aiModel?.takeIf { it.isNotBlank() }
        val effort = issue.fields.aiReasoningEffort?.takeIf { it.isNotBlank() }
            ?: parentContext?.fields?.aiReasoningEffort?.takeIf { it.isNotBlank() }
        return AgentDispatchRequest(
            storyKey = issue.key,
            projectKey = issue.projectKey,
            serializationKey = storyRun.storyKey,
            targetRepo = targetRepo,
            storyRunId = storyRun.id,
            workspacePath = null,
            branchName = storyRun.branchName,
            role = role,
            phase = activePhaseValue,
            baseBranch = storyRun.baseBranch,
            branchPrefix = storyRun.branchPrefix,
            prNumber = storyRun.prNumber,
            previewUrl = previewUrl,
            previewUrlTemplate = storyRun.previewUrlTemplate,
            previewNamespace = previewNamespace,
            // v2-subtaakflow geeft 'm expliciet mee (sourcePhase is daar altijd null); anders de
            // legacy story-niveau AiPhase-afleiding.
            developerLoopbackReason = loopbackReason ?: sourcePhase.developerLoopbackReason(),
            agentMode = "comment".takeIf { prCommentContext != null },
            trackerContext = trackerContext(issue, role, parentContext),
            prCommentContext = prCommentContext,
            inputAttachments = productFactoryAttachments(storyRun.storyKey),
            aiSupplier = supplier,
            // Legacy storymetadata blijft alleen context; Runtimeconfiguratie kiest de uitvoering.
            aiModel = model,
            aiEffort = effort,
            questionsAllowed = issueTrackerClient.effectiveQuestionsAllowed(issue),
        )
    }

    private fun productFactoryAttachments(storyKey: String): List<AgentInputAttachment> =
        issueTrackerClient.listIssueAttachments(storyKey).mapNotNull { attachment ->
            val parsed = ProductFactoryAttachmentNames.parse(attachment.name) ?: return@mapNotNull null
            val bytes = requireNotNull(issueTrackerClient.downloadAttachmentBytes(attachment)) {
                "Product Factory-attachment ${attachment.name} kan niet worden gelezen."
            }
            val stablePart = parsed.attachmentId.lowercase()
                .replace(Regex("[^a-z0-9-]"), "-")
                .trim('-')
                .take(70)
                .ifBlank { "attachment" }
            val extension = parsed.fileName.substringAfterLast('.', "bin").lowercase()
                .replace(Regex("[^a-z0-9]"), "")
                .take(12)
                .ifBlank { "bin" }
            AgentInputAttachment(
                logicalName = "product-factory-$stablePart",
                originalFilename = parsed.fileName,
                uploadFilename = "$stablePart.$extension",
                mimeType = attachment.mimeType?.takeIf(String::isNotBlank) ?: "application/octet-stream",
                bytes = bytes,
            )
        }.also { attachments ->
            require(attachments.map { it.logicalName }.distinct().size == attachments.size) {
                "Product Factory-attachments hebben geen unieke Runtime-objectnamen."
            }
        }

    /**
     * Repositorycontext is voortaan alleen remote state. De Runtime-worker maakt voor iedere job
     * zelf een tijdelijke checkout; de Software Factory bewaart of deelt geen werkmap meer.
     */
    private fun prepareRemoteBranch(storyRun: StoryRunRecord, role: AgentRole): StoryRunRecord {
        if (role !in REPOSITORY_ROLES) return storyRun

        val baseBranch = storyRun.baseBranch?.takeIf(String::isNotBlank) ?: DEFAULT_BASE_BRANCH
        if (role == AgentRole.AUDITOR) {
            return storyRun.copy(baseBranch = baseBranch)
        }
        val branchPrefix = storyRun.branchPrefix?.takeIf(String::isNotBlank) ?: DEFAULT_BRANCH_PREFIX
        val branchName = storyRun.branchName?.takeIf(String::isNotBlank)
            ?: branchPrefix + storyRun.storyKey.replace(Regex("[^A-Za-z0-9._-]"), "-")

        pullRequestClient.ensureRemoteBranch(storyRun.targetRepo, branchName, baseBranch)
        storyRunRepository.updatePullRequest(
            storyRunId = storyRun.id,
            branchName = branchName,
            prNumber = storyRun.prNumber,
            prUrl = storyRun.prUrl,
            baseBranch = baseBranch,
            branchPrefix = branchPrefix,
            previewUrlTemplate = storyRun.previewUrlTemplate,
            previewNamespaceTemplate = storyRun.previewNamespaceTemplate,
            previewDbSecretRecipe = storyRun.previewDbSecretRecipe,
        )
        return storyRun.copy(branchName = branchName, baseBranch = baseBranch, branchPrefix = branchPrefix)
    }

    private fun trackerContext(issue: TrackerIssue, role: AgentRole, parentContext: TrackerIssue? = null): String =
        buildString {
            appendLine("## Issue Context")
            appendLine()
            appendLine("- Key: `${issue.key}`")
            appendLine("- Summary: ${issue.summary}")
            appendLine("- Status: ${issue.status}")
            appendLine("- Project: `${issue.projectKey}`")
            issue.fields.subtaskType?.let { appendLine("- Subtask Type: `$it`") }
            issue.fields.aiSupplier?.let { appendLine("- AI Supplier: `$it`") }
            // Fase 6 — subtask-agent krijgt de (gerefinede) parent story-tekst mee.
            parentContext?.let { parent ->
                appendLine()
                appendLine("### Parent Story (`${parent.key}`): ${parent.summary}")
                appendLine()
                appendLine(parent.description?.trim()?.takeIf { it.isNotBlank() } ?: "Geen parent-description.")
                // Alle subtaken van de story, in uitvoervolgorde, met de huidige gemarkeerd. Zo weet de
                // agent dat 'ie aan één subtaak van een groter geheel werkt en welke dat is.
                val siblings = runCatching { issueTrackerClient.subtasksOf(parent.key) }.getOrDefault(emptyList())
                if (siblings.isNotEmpty()) {
                    appendLine()
                    appendLine("### Subtaken in deze story (uitvoervolgorde)")
                    appendLine()
                    siblings.forEachIndexed { index, sibling ->
                        val type = sibling.fields.subtaskType?.takeIf { it.isNotBlank() } ?: "?"
                        val phase = sibling.fields.subtaskPhase?.takeIf { it.isNotBlank() } ?: "niet gestart"
                        val marker = if (sibling.key == issue.key) "  ← HUIDIGE TAAK" else ""
                        appendLine("${index + 1}. [$type] `${sibling.key}` — ${sibling.summary} (fase: $phase)$marker")
                    }
                }
            }
            appendLine()
            appendLine("### Description")
            appendLine()
            appendLine(
                issue.description?.trim()?.takeIf { it.isNotBlank() }
                    ?: "Geen issue tracker-description gevonden.",
            )
            appendLine()
            appendLine("### Relevant Issue Comments")
            appendLine()
            val comments = issueTrackerClient.taskComments(issue, role) { comment, commentRole ->
                processedCommentService.isProcessed(issue.key, comment.id, commentRole)
            }
            if (comments.isEmpty()) {
                appendLine("Geen nieuwe relevante comments voor deze rol.")
            } else {
                comments.forEach { comment ->
                    appendLine(comment.toTaskMarkdown())
                    appendLine()
                }
            }
        }.trimEnd()

    private fun prCommentContext(storyRun: StoryRunRecord, role: AgentRole, sourcePhase: AiPhase?): String? {
        if (role != AgentRole.DEVELOPER || sourcePhase != AiPhase.TESTED_WITH_FEEDBACK_FOR_DEVELOPER) {
            return null
        }
        val prNumber = storyRun.prNumber ?: return null
        val comments = pullRequestClient.claimedFactoryComments(storyRun.targetRepo, prNumber)
        if (comments.isEmpty()) {
            return null
        }
        return buildString {
            appendLine("## PR Comment Task Bundle")
            appendLine()
            appendLine("Verwerk onderstaande `@factory` PR-comments op dezelfde branch en PR.")
            appendLine()
            comments.forEach { comment ->
                appendLine("### PR comment ${comment.id}")
                appendLine()
                appendLine(comment.body.trim())
                appendLine()
            }
        }.trimEnd()
    }

    private fun canDispatch(storyKey: String, role: AgentRole): Boolean {
        if (agentRuntime.isAnyAgentRunningForStory(storyKey)) {
            logger.info(
                "canDispatch=false story={} role={}: er draait al een agent voor deze story.",
                storyKey,
                role.markerKeyPart,
            )
            return false
        }
        val roleCount = agentRuntime.runningCount(role)
        if (roleCount >= settings.maxParallelFor(role)) {
            logger.info(
                "canDispatch=false story={} role={}: rol-cap bereikt ({}/{}).",
                storyKey,
                role.markerKeyPart,
                roleCount,
                settings.maxParallelFor(role),
            )
            return false
        }
        val totalCount = agentRuntime.runningCount(null)
        if (totalCount >= settings.maxParallelTotal) {
            logger.info(
                "canDispatch=false story={} role={}: totaal-cap bereikt ({}/{}).",
                storyKey,
                role.markerKeyPart,
                totalCount,
                settings.maxParallelTotal,
            )
            return false
        }
        return true
    }

    private fun AiPhase?.isDeveloperLoopbackPhase(): Boolean =
        this == AiPhase.REVIEWED_WITH_FEEDBACK_FOR_DEVELOPER || this == AiPhase.TESTED_WITH_FEEDBACK_FOR_DEVELOPER

    private fun AiPhase?.developerLoopbackReason(): String? =
        when (this) {
            AiPhase.REVIEWED_WITH_FEEDBACK_FOR_DEVELOPER ->
                "Lees eerst het laatste [REVIEWER]-comment en verwerk die feedback op dezelfde branch en PR."
            AiPhase.TESTED_WITH_FEEDBACK_FOR_DEVELOPER ->
                "Lees eerst het laatste [TESTER]-comment en verwerk die feedback op dezelfde branch en PR."
            else -> null
        }

    private fun TrackerComment.toTaskMarkdown(): String =
        buildString {
            appendLine("#### Issue comment $id")
            authorDisplayName?.takeIf { it.isNotBlank() }?.let { appendLine("- Author: $it") }
            created?.let { appendLine("- Created: `$it`") }
            appendLine()
            appendLine(body.trim())
        }.trimEnd()

    private companion object {
        const val DEFAULT_BASE_BRANCH = "main"
        const val DEFAULT_BRANCH_PREFIX = "ai/"
        val REPOSITORY_ROLES = setOf(
            AgentRole.DEVELOPER,
            AgentRole.REVIEWER,
            AgentRole.TESTER,
            AgentRole.DOCUMENTER,
            AgentRole.AUDITOR,
        )
    }
}
