package nl.vdzon.softwarefactory.orchestrator.services

import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.orchestrator.AgentDispatchRequest
import nl.vdzon.softwarefactory.orchestrator.AgentFailurePolicy
import nl.vdzon.softwarefactory.orchestrator.AgentRunRecord
import nl.vdzon.softwarefactory.orchestrator.AgentRunRepository
import nl.vdzon.softwarefactory.orchestrator.AgentRuntime
import nl.vdzon.softwarefactory.orchestrator.AiPhase
import nl.vdzon.softwarefactory.orchestrator.services.AiRouting
import nl.vdzon.softwarefactory.orchestrator.CostMonitor
import nl.vdzon.softwarefactory.orchestrator.CreditsPauseCoordinator
import nl.vdzon.softwarefactory.orchestrator.IssueProcessResult
import nl.vdzon.softwarefactory.orchestrator.services.ManualCommandProcessor
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.orchestrator.OrchestratorPollResult
import nl.vdzon.softwarefactory.orchestrator.models.OrchestratorSettings
import nl.vdzon.softwarefactory.orchestrator.StoryRunRecord
import nl.vdzon.softwarefactory.orchestrator.StoryRunRepository
import nl.vdzon.softwarefactory.youtrack.AgentRole
import nl.vdzon.softwarefactory.youtrack.FactoryCommand
import nl.vdzon.softwarefactory.youtrack.IssueType
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.youtrack.TrackerComment
import nl.vdzon.softwarefactory.youtrack.TrackerFieldUpdate
import nl.vdzon.softwarefactory.youtrack.TrackerIssue
import nl.vdzon.softwarefactory.youtrack.TrackerField
import nl.vdzon.softwarefactory.youtrack.ProcessedCommentsApi
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.orchestrator.PreparedStoryWorkspace
import nl.vdzon.softwarefactory.orchestrator.StoryWorkspaceApi
import nl.vdzon.softwarefactory.support.SupportApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime

@Service
class OrchestratorService(
    private val issueTrackerClient: YouTrackApi,
    private val agentRuntime: AgentRuntime,
    private val storyRunRepository: StoryRunRepository,
    private val agentRunRepository: AgentRunRepository,
    private val pullRequestClient: GitHubApi,
    private val processedCommentService: ProcessedCommentsApi,
    private val previewApi: PreviewApi,
    private val storyWorkspaceService: StoryWorkspaceApi,
    private val costMonitor: CostMonitor,
    private val creditsPauseCoordinator: CreditsPauseCoordinator,
    private val manualCommandProcessor: ManualCommandProcessor,
    private val settings: OrchestratorSettings,
    private val clock: Clock,
) : OrchestratorApi {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun pollOnce(projectKey: String): OrchestratorPollResult {
        val issues = issueTrackerClient.findWorkIssues()
        val activeCreditsPause = creditsPauseCoordinator.activePause(OffsetDateTime.now(clock))
        if (activeCreditsPause != null) {
            return OrchestratorPollResult(issues.map { IssueProcessResult.Skipped(it.key, "credits-paused") })
        }
        val results = issues.map { processIssue(it) } + monitorPullRequests(issues.map { it.key }.toSet())
        return OrchestratorPollResult(results)
    }

    override fun processIssue(issue: TrackerIssue): IssueProcessResult {
        val manualCommandApplication = manualCommandProcessor.apply(issue)
        manualCommandApplication.stopResult?.let { return it }

        val currentIssue = costMonitor.applyBudgetTriggers(manualCommandApplication.issue)
        if (currentIssue.fields.paused) {
            return IssueProcessResult.Skipped(currentIssue.key, "paused")
        }
        if (!currentIssue.fields.error.isNullOrBlank()) {
            recoverRetryableIssueError(currentIssue)?.let { return it }
            return IssueProcessResult.Skipped(currentIssue.key, "error")
        }
        if (currentIssue.fields.aiSupplier.isNullOrBlank() || currentIssue.fields.aiSupplier.equals("none", ignoreCase = true)) {
            return IssueProcessResult.Skipped(currentIssue.key, "ai-supplier")
        }

        // Fase 1 — dunne router op IssueType (afgeleid uit het `Type`-veld).
        return when (currentIssue.fields.issueType) {
            IssueType.STORY -> processStory(currentIssue)
            // Subtask-uitvoering komt in fase 5; tot dan overslaan.
            IssueType.SUBTASK -> IssueProcessResult.Skipped(currentIssue.key, "subtask-execution-not-yet-implemented")
        }
    }

    private fun processStory(currentIssue: TrackerIssue): IssueProcessResult {
        val phase = AiPhase.fromTracker(currentIssue.fields.aiPhase)
        if (phase == null && !currentIssue.fields.aiPhase.isNullOrBlank()) {
            val message = "[ORCHESTRATOR] Onbekende AI Phase '${currentIssue.fields.aiPhase}'. Corrigeer het veld en leeg `Error` om opnieuw te proberen."
            issueTrackerClient.updateIssueFields(currentIssue.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
            return IssueProcessResult.Errored(currentIssue.key, message)
        }

        return when (phase) {
            null -> dispatchIfAllowed(currentIssue, AgentRole.REFINER, sourcePhase = null)
            AiPhase.QUESTIONS_ANSWERED_FOR_REFINEMENT -> dispatchIfAllowed(currentIssue, AgentRole.REFINER, phase)
            AiPhase.REFINED_FINISHED -> dispatchIfAllowed(currentIssue, AgentRole.DEVELOPER, phase)
            AiPhase.DEVELOPED -> dispatchIfAllowed(currentIssue, AgentRole.REVIEWER, phase)
            AiPhase.REVIEW_FINISHED -> dispatchIfAllowed(currentIssue, AgentRole.TESTER, phase)
            AiPhase.REVIEWED_WITH_FEEDBACK_FOR_DEVELOPER,
            AiPhase.TESTED_WITH_FEEDBACK_FOR_DEVELOPER,
            -> dispatchIfAllowed(currentIssue, AgentRole.DEVELOPER, phase)
            AiPhase.REFINED_WITH_QUESTIONS_FOR_USER -> IssueProcessResult.Skipped(currentIssue.key, "waiting-for-user")
            AiPhase.TESTED_SUCCESSFULLY -> dispatchIfAllowed(currentIssue, AgentRole.SUMMARIZER, phase)
            AiPhase.SUMMARY_FINISHED -> IssueProcessResult.Skipped(currentIssue.key, "summary-finished")
            AiPhase.REFINING,
            AiPhase.DEVELOPING,
            AiPhase.REVIEWING,
            AiPhase.TESTING,
            AiPhase.SUMMARIZING,
            -> recoverActivePhase(currentIssue, phase)
        }
    }

    override fun queueCommand(storyKey: String, command: FactoryCommand) {
        issueTrackerClient.postComment(storyKey, "@factory:command:${command.token}")
    }

    private fun dispatchIfAllowed(issue: TrackerIssue, role: AgentRole, sourcePhase: AiPhase?): IssueProcessResult {
        val targetRepo = issue.fields.targetRepo
        if (targetRepo.isNullOrBlank()) {
            val message = "[ORCHESTRATOR] Target repo ontbreekt; zet `factory.repo=...` in de YouTrack-projectbeschrijving en leeg `Error` om opnieuw te proberen."
            issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
            return IssueProcessResult.Errored(issue.key, message)
        }

        val storyRun = storyRunRepository.openOrCreate(issue.key, targetRepo)
        val budgetResult = costMonitor.checkBudget(issue, storyRun)
        if (budgetResult.paused) {
            return IssueProcessResult.Skipped(issue.key, "budget-exceeded")
        }

        if (role == AgentRole.DEVELOPER && sourcePhase.isDeveloperLoopbackPhase()) {
            val developerRuns = agentRunRepository.countForRole(storyRun.id, AgentRole.DEVELOPER)
            val maxDeveloperLoopbacks = issue.fields.developerLoopbackLimit(settings.maxDeveloperLoopbacks)
            if (developerRuns >= maxDeveloperLoopbacks + 1) {
                val message = "[ORCHESTRATOR] Developer-loopback cap bereikt (${maxDeveloperLoopbacks}x). " +
                    "Handmatige triage nodig. Geef feedback en leeg `Error` om opnieuw te proberen, " +
                    "of zet `Paused = true` en parkeer dit ticket."
                issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
                return IssueProcessResult.Errored(issue.key, message)
            }
        }

        if (!canDispatch(issue.key, role)) {
            return IssueProcessResult.Skipped(issue.key, "concurrency-cap")
        }

        val activePhase = AiPhase.activeFor(role)
        val startedAt = OffsetDateTime.now(clock)
        issueTrackerClient.updateIssueFields(
            issue.key,
            TrackerFieldUpdate.of(
                TrackerField.AI_PHASE to activePhase.trackerValue,
                TrackerField.AGENT_STARTED_AT to startedAt,
            ),
        )

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
                activePhase = activePhase,
                sourcePhase = sourcePhase,
            )

            logger.info(
                "Starting agent dispatch: story={} role={} storyRunId={} sourcePhase={} targetPhase={} supplier={} level={} model={} targetRepo={} prNumber={} branch={} workspace={}",
                issue.key,
                role.markerKeyPart,
                storyRun.id,
                sourcePhase?.trackerValue ?: "<empty>",
                activePhase.trackerValue,
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
            )
            logger.info(
                "Agent started: story={} role={} agentRunId={} storyRunId={} container={} workspace={} phase={} supplier={} level={} model={}",
                issue.key,
                role.markerKeyPart,
                agentRunId,
                storyRun.id,
                dispatch.containerName,
                dispatch.workspacePath ?: "<unknown>",
                activePhase.trackerValue,
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
        activePhase: AiPhase,
        sourcePhase: AiPhase?,
    ): AgentDispatchRequest {
        val previewUrl = previewApi.render(workspace.deploymentConfig.previewUrlTemplate, storyRun.prNumber)
        val previewNamespace = previewApi.render(workspace.deploymentConfig.previewNamespaceTemplate, storyRun.prNumber)
        val prCommentContext = prCommentContext(storyRun, role, sourcePhase)
        val aiRoute = AiRouting.resolve(issue.fields.aiLevel, issue.fields.aiSupplier, role)
        return AgentDispatchRequest(
            storyKey = issue.key,
            targetRepo = targetRepo,
            storyRunId = storyRun.id,
            workspacePath = workspace.workspacePath.toString(),
            branchName = workspace.branchName,
            role = role,
            phase = activePhase,
            baseBranch = workspace.baseBranch,
            branchPrefix = workspace.branchPrefix,
            prNumber = storyRun.prNumber,
            previewUrl = previewUrl,
            previewNamespace = previewNamespace,
            developerLoopbackReason = sourcePhase.developerLoopbackReason(),
            agentMode = "comment".takeIf { prCommentContext != null },
            trackerContext = trackerContext(issue, role),
            prCommentContext = prCommentContext,
            aiLevel = aiRoute.level,
            aiSupplier = issue.fields.aiSupplier,
            aiModel = aiRoute.model,
            aiEffort = aiRoute.effort,
        )
    }

    private fun trackerContext(issue: TrackerIssue, role: AgentRole): String =
        buildString {
            appendLine("## Issue Context")
            appendLine()
            appendLine("- Key: `${issue.key}`")
            appendLine("- Summary: ${issue.summary}")
            appendLine("- Status: ${issue.status}")
            appendLine("- Project: `${issue.projectKey}`")
            issue.fields.aiSupplier?.let { appendLine("- AI Supplier: `$it`") }
            issue.fields.aiLevel?.let { appendLine("- AI Level: `$it`") }
            appendLine()
            appendLine("### Description")
            appendLine()
            appendLine(issue.description?.trim()?.takeIf { it.isNotBlank() } ?: "Geen issue tracker-description gevonden.")
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
            return false
        }
        if (agentRuntime.runningCount(role) >= settings.maxParallelFor(role)) {
            return false
        }
        return agentRuntime.runningCount(null) < settings.maxParallelTotal
    }

    private fun monitorPullRequests(activeAiStoryKeys: Set<String>): List<IssueProcessResult> =
        storyRunRepository.activePullRequests()
            .filter { it.storyKey in activeAiStoryKeys }
            .mapNotNull { run ->
                runCatching {
                    monitorPullRequest(run)
                }.onFailure { exception ->
                    logger.warn("PR monitor failed for {}", run.storyKey, exception)
                }.getOrNull()
            }

    private fun monitorPullRequest(run: StoryRunRecord): IssueProcessResult? {
        val prNumber = run.prNumber ?: return null
        if (pullRequestClient.isMerged(run.targetRepo, prNumber)) {
            cleanupPreviewNamespace(run)
            issueTrackerClient.transitionIssue(run.storyKey, "Done")
            storyRunRepository.close(run.id, "merged", OffsetDateTime.now(clock))
            return IssueProcessResult.Merged(run.storyKey, prNumber)
        }

        if (agentRuntime.isAnyAgentRunningForStory(run.storyKey)) {
            return null
        }

        val comments = pullRequestClient.unprocessedFactoryComments(run.targetRepo, prNumber)
        if (comments.isEmpty()) {
            return null
        }
        comments.forEach { comment -> pullRequestClient.markCommentClaimed(run.targetRepo, comment.id) }
        issueTrackerClient.updateIssueFields(
            run.storyKey,
            TrackerFieldUpdate.of(TrackerField.AI_PHASE to AiPhase.TESTED_WITH_FEEDBACK_FOR_DEVELOPER.trackerValue),
        )
        return IssueProcessResult.PrCommentTriggered(run.storyKey, prNumber, comments.size)
    }

    private fun cleanupPreviewNamespace(run: StoryRunRecord): Boolean {
        val namespace = previewApi.render(run.previewNamespaceTemplate, run.prNumber) ?: return false
        return previewApi.cleanup(namespace)
    }

    private fun recoverActivePhase(issue: TrackerIssue, phase: AiPhase): IssueProcessResult {
        val role = requireNotNull(phase.activeRole)
        if (agentRuntime.isAgentRunning(issue.key, role)) {
            return IssueProcessResult.Skipped(issue.key, "agent-running")
        }

        val targetRepo = issue.fields.targetRepo.orEmpty()
        val storyRun = storyRunRepository.openOrCreate(issue.key, targetRepo)
        val latestRun = agentRunRepository.latestForRole(storyRun.id, role)
        val startedAt = issue.fields.agentStartedAt
        val now = OffsetDateTime.now(clock)

        if (startedAt != null && startedAt.plus(settings.hardTimeout).isBefore(now)) {
            val message = "[ORCHESTRATOR] Hard timeout: ${phase.trackerValue} loopt langer dan ${settings.hardTimeout.toMinutes()} minuten zonder voortgang."
            issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
            return IssueProcessResult.Errored(issue.key, message)
        }

        if (latestRun != null && latestRun.endedAt == null) {
            return IssueProcessResult.Skipped(issue.key, "awaiting-agent-completion")
        }

        if (latestRun != null && latestRun.isSuccessful()) {
            val completedPhase = AiPhase.completedAfterSuccessful(role, latestRun.outcome)
            issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.AI_PHASE to completedPhase.trackerValue))
            return IssueProcessResult.Recovered(issue.key, completedPhase.trackerValue)
        }

        if (latestRun != null && latestRun.isRetryableFailure()) {
            val transientFailures = agentRunRepository.recentForRole(
                storyRun.id,
                role,
                settings.maxTransientRetries + 1,
            ).takeWhile { it.isRetryableFailure() }.size

            if (transientFailures <= settings.maxTransientRetries) {
                val previousPhase = AiPhase.previousCompletedBeforeRetry(phase)
                issueTrackerClient.updateIssueFields(
                    issue.key,
                    TrackerFieldUpdate.of(TrackerField.AI_PHASE to previousPhase?.trackerValue),
                )
                return IssueProcessResult.Recovered(issue.key, previousPhase?.trackerValue ?: "<empty>")
            }

            val message = "[ORCHESTRATOR] Transient retry cap bereikt (${settings.maxTransientRetries}x) voor ${role.markerKeyPart}; handmatige triage nodig."
            issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
            return IssueProcessResult.Errored(issue.key, message)
        }

        if (startedAt != null && startedAt.plus(settings.activePhaseRecoveryDelay).isAfter(now)) {
            return IssueProcessResult.Skipped(issue.key, "waiting-for-active-phase-recovery")
        }

        val previousPhase = AiPhase.previousCompletedBeforeRetry(phase)
        issueTrackerClient.updateIssueFields(
            issue.key,
            TrackerFieldUpdate.of(TrackerField.AI_PHASE to previousPhase?.trackerValue),
        )
        return IssueProcessResult.Recovered(issue.key, previousPhase?.trackerValue ?: "<empty>")
    }

    private fun recoverRetryableIssueError(issue: TrackerIssue): IssueProcessResult? {
        val error = issue.fields.error.orEmpty()
        if (!error.contains("[ORCHESTRATOR] Geen actieve container gevonden")) {
            return null
        }
        val phase = AiPhase.fromTracker(issue.fields.aiPhase)?.takeIf { it.isActive } ?: return null
        val previousPhase = AiPhase.previousCompletedBeforeRetry(phase)
        issueTrackerClient.updateIssueFields(
            issue.key,
            TrackerFieldUpdate.of(
                TrackerField.ERROR to null,
                TrackerField.AI_PHASE to previousPhase?.trackerValue,
            ),
        )
        return IssueProcessResult.Recovered(issue.key, previousPhase?.trackerValue ?: "<empty>")
    }

    private fun AiPhase?.isDeveloperLoopbackPhase(): Boolean =
        this == AiPhase.REVIEWED_WITH_FEEDBACK_FOR_DEVELOPER || this == AiPhase.TESTED_WITH_FEEDBACK_FOR_DEVELOPER

    private fun AiPhase?.developerLoopbackReason(): String? =
        when (this) {
            AiPhase.REVIEWED_WITH_FEEDBACK_FOR_DEVELOPER -> "Lees eerst het laatste [REVIEWER]-comment en verwerk die feedback op dezelfde branch en PR."
            AiPhase.TESTED_WITH_FEEDBACK_FOR_DEVELOPER -> "Lees eerst het laatste [TESTER]-comment en verwerk die feedback op dezelfde branch en PR."
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

    private fun AgentRunRecord.isSuccessful(): Boolean =
        endedAt != null && outcome?.contains("error", ignoreCase = true) != true && outcome?.contains("failed", ignoreCase = true) != true

    private fun AgentRunRecord.isRetryableFailure(): Boolean =
        AgentFailurePolicy.isRetryable(outcome, summaryText)
}
