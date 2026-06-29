package nl.vdzon.softwarefactory.pipeline.service

import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.core.AgentDispatchRequest
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.AgentRunRepository
import nl.vdzon.softwarefactory.core.AgentRuntime
import nl.vdzon.softwarefactory.core.AiPhase
import nl.vdzon.softwarefactory.core.AiRouting
import nl.vdzon.softwarefactory.core.CostMonitor
import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.core.OrchestratorSettings
import nl.vdzon.softwarefactory.core.PreparedStoryWorkspace
import nl.vdzon.softwarefactory.core.StoryRunRecord
import nl.vdzon.softwarefactory.core.StoryRunRepository
import nl.vdzon.softwarefactory.core.StoryWorkspaceApi
import nl.vdzon.softwarefactory.core.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.youtrack.ProcessedCommentsApi
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.support.SupportApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime

/**
 * Gedeelde "start een agent"-mechaniek voor de pipeline: budget- en concurrency-checks, fase-veld
 * + AgentStartedAt zetten, workspace prepareren, de dispatch-request bouwen en de agent starten.
 *
 * Gebruikt door zowel [StoryRefinementCoordinator] (story-fasen) als
 * [SubtaskExecutionCoordinator] (subtask-pipeline).
 */
@Component
class AgentDispatcher(
    private val issueTrackerClient: YouTrackApi,
    private val agentRuntime: AgentRuntime,
    private val storyRunRepository: StoryRunRepository,
    private val agentRunRepository: AgentRunRepository,
    private val pullRequestClient: GitHubApi,
    private val processedCommentService: ProcessedCommentsApi,
    private val previewApi: PreviewApi,
    private val storyWorkspaceService: StoryWorkspaceApi,
    private val costMonitor: CostMonitor,
    private val projectRepoResolver: ProjectRepoResolver,
    private val settings: OrchestratorSettings,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // YouTrack State-lane: een agent gaat dit issue actief verwerken → In Progress.
    private val stateInProgress = "In Progress"

    fun dispatch(
        issue: TrackerIssue,
        role: AgentRole,
        sourcePhase: AiPhase?,
        phaseField: TrackerField = TrackerField.AI_PHASE,
        activePhaseValue: String = AiPhase.activeFor(role).trackerValue,
        // Fase 5/6 — voor subtaken draait de agent op de PARENT-branch: storyRun +
        // concurrency-guard keyen op de parent, terwijl velden + result op de subtask
        // (issue.key) blijven. `loopbackCapped` markeert een subtask-fix-developer.
        storyRunKey: String = issue.key,
        loopbackCapped: Boolean = false,
        // Fase 6 — budget hoort op story-niveau: subtaken geven de parent mee.
        budgetIssue: TrackerIssue = issue,
        // Fase 6 — parent story-tekst als extra context voor subtask-agents.
        parentContext: TrackerIssue? = null,
        // De repo wordt afgeleid uit het `Repo`-veld (story) of dat van de parent (subtask),
        // via ProjectRepoResolver. Door de caller meegegeven; null = geen geldige repo → Error.
        targetRepo: String? = projectRepoResolver.resolve(issue.fields.repo),
    ): IssueProcessResult {
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
            ),
        )
        issueTrackerClient.transitionIssue(issue.key, stateInProgress)

        return try {
            val workspace = storyWorkspaceService.prepare(storyRun, role)
            storyWorkspaceService.ensureStoryWorklog(storyRun, issue.summary, issue.description)
            storyRunRepository.updateWorkspace(
                storyRunId = storyRun.id,
                workspacePath = workspace.workspacePath.toString(),
                branchName = workspace.branchName,
                baseBranch = workspace.baseBranch,
                branchPrefix = workspace.branchPrefix,
                previewUrlTemplate = workspace.deploymentConfig.previewUrlTemplate,
                previewNamespaceTemplate = workspace.deploymentConfig.previewNamespaceTemplate,
                previewDbSecretRecipe = workspace.deploymentConfig.previewDbSecretRecipe,
            )
            postWorkspaceLinkIfNew(issue.key, storyRun, workspace)
            val request = dispatchRequest(
                issue = issue,
                targetRepo = targetRepo,
                storyRun = storyRun,
                workspace = workspace,
                role = role,
                activePhaseValue = activePhaseValue,
                sourcePhase = sourcePhase,
                parentContext = parentContext,
            )

            logger.info(
                "Starting agent dispatch: story={} role={} storyRunId={} sourcePhase={} " +
                    "targetPhase={} supplier={} level={} model={} targetRepo={} prNumber={} " +
                    "branch={} workspace={}",
                issue.key,
                role.markerKeyPart,
                storyRun.id,
                sourcePhase?.trackerValue ?: "<empty>",
                activePhaseValue,
                request.aiSupplier?.takeIf { it.isNotBlank() } ?: "<unset>",
                request.aiLevel ?: "<unset>",
                request.aiModel?.takeIf { it.isNotBlank() } ?: "<default>",
                SupportApi.default().redact(targetRepo),
                storyRun.prNumber ?: "<none>",
                workspace.branchName,
                workspace.workspacePath,
            )
            val dispatch = agentRuntime.dispatch(request)
            val agentRunId = agentRunRepository.recordStarted(
                storyRunId = storyRun.id,
                role = role,
                containerName = dispatch.containerName,
                model = request.aiModel,
                effort = request.aiEffort,
                level = request.aiLevel,
                workspacePath = dispatch.workspacePath,
                // Voor subtaken (storyRun keyt op de parent) → markeer de run met de subtask-key.
                subtaskKey = issue.key.takeIf { storyRunKey != issue.key },
            )
            logger.info(
                "Agent started: story={} role={} agentRunId={} storyRunId={} container={} " +
                    "workspace={} phase={} supplier={} level={} model={}",
                issue.key,
                role.markerKeyPart,
                agentRunId,
                storyRun.id,
                dispatch.containerName,
                dispatch.workspacePath ?: "<unknown>",
                activePhaseValue,
                request.aiSupplier?.takeIf { it.isNotBlank() } ?: "<unset>",
                request.aiLevel ?: "<unset>",
                request.aiModel?.takeIf { it.isNotBlank() } ?: "<default>",
            )
            runCatching {
                agentRuntime.captureLogs(dispatch.containerName, agentRunId)
            }.onFailure { exception ->
                logger.warn("Agent log capture could not be started for {}", dispatch.containerName, exception)
            }
            IssueProcessResult.Dispatched(issue.key, role, dispatch.containerName)
        } catch (exception: Exception) {
            val message = "[ORCHESTRATOR] Agent dispatch voor ${role.markerKeyPart} faalde: ${exception.message}"
            logger.warn("Agent dispatch failed for {} {}", issue.key, role, exception)
            issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
            IssueProcessResult.Errored(issue.key, message)
        }
    }

    private fun postWorkspaceLinkIfNew(storyKey: String, storyRun: StoryRunRecord, workspace: PreparedStoryWorkspace) {
        if (!storyRun.workspacePath.isNullOrBlank()) {
            return
        }
        val repoRoot = workspace.repoRoot.toAbsolutePath().normalize()
        val message = """
        [ORCHESTRATOR] Work folder aangemaakt:
        - Repo: [$repoRoot](${repoRoot.toUri()})
        - Open in IntelliJ: `open -a "IntelliJ IDEA" "$repoRoot"`
        """.trimIndent()
        runCatching {
            issueTrackerClient.postComment(storyKey, message)
        }.onFailure { exception ->
            logger.warn("Could not post workspace link for {}", storyKey, exception)
        }
    }

    private fun dispatchRequest(
        issue: TrackerIssue,
        targetRepo: String,
        storyRun: StoryRunRecord,
        workspace: PreparedStoryWorkspace,
        role: AgentRole,
        activePhaseValue: String,
        sourcePhase: AiPhase?,
        parentContext: TrackerIssue? = null,
    ): AgentDispatchRequest {
        val previewUrl = previewApi.render(workspace.deploymentConfig.previewUrlTemplate, storyRun.prNumber)
        val previewNamespace = previewApi.render(workspace.deploymentConfig.previewNamespaceTemplate, storyRun.prNumber)
        val prCommentContext = prCommentContext(storyRun, role, sourcePhase)
        // Subtaken erven supplier/model/effort van de parent-story als ze zelf leeg zijn.
        val supplier = issue.fields.aiSupplier?.takeIf { it.isNotBlank() }
            ?: parentContext?.fields?.aiSupplier?.takeIf { it.isNotBlank() }
        val model = issue.fields.aiModel?.takeIf { it.isNotBlank() }
            ?: parentContext?.fields?.aiModel?.takeIf { it.isNotBlank() }
        val effort = issue.fields.aiReasoningEffort?.takeIf { it.isNotBlank() }
            ?: parentContext?.fields?.aiReasoningEffort?.takeIf { it.isNotBlank() }
        val aiRoute = AiRouting.resolve(issue.fields.aiLevel, supplier, role)
        return AgentDispatchRequest(
            storyKey = issue.key,
            serializationKey = storyRun.storyKey,
            targetRepo = targetRepo,
            storyRunId = storyRun.id,
            workspacePath = workspace.workspacePath.toString(),
            branchName = workspace.branchName,
            role = role,
            phase = activePhaseValue,
            baseBranch = workspace.baseBranch,
            branchPrefix = workspace.branchPrefix,
            prNumber = storyRun.prNumber,
            previewUrl = previewUrl,
            previewNamespace = previewNamespace,
            developerLoopbackReason = sourcePhase.developerLoopbackReason(),
            agentMode = "comment".takeIf { prCommentContext != null },
            trackerContext = trackerContext(issue, role, parentContext),
            prCommentContext = prCommentContext,
            aiLevel = aiRoute.level,
            aiSupplier = supplier,
            // Per-subtask model/effort (planner-keuze) gaat voor; anders parent, anders routing.
            aiModel = model ?: aiRoute.model,
            aiEffort = effort ?: aiRoute.effort,
        )
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
            issue.fields.aiLevel?.let { appendLine("- AI Level: `$it`") }
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
}
