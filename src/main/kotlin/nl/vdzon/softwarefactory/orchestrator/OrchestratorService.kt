package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.github.PullRequestClient
import nl.vdzon.softwarefactory.jira.AgentRole
import nl.vdzon.softwarefactory.jira.JiraClient
import nl.vdzon.softwarefactory.jira.JiraFieldUpdate
import nl.vdzon.softwarefactory.jira.JiraIssue
import nl.vdzon.softwarefactory.jira.JiraKnownField
import nl.vdzon.softwarefactory.preview.PreviewEnvironmentCleaner
import nl.vdzon.softwarefactory.preview.PreviewTemplateRenderer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime

@Service
class OrchestratorService(
    private val jiraClient: JiraClient,
    private val agentRuntime: AgentRuntime,
    private val storyRunRepository: StoryRunRepository,
    private val agentRunRepository: AgentRunRepository,
    private val pullRequestClient: PullRequestClient,
    private val previewEnvironmentCleaner: PreviewEnvironmentCleaner,
    private val costMonitor: CostMonitor,
    private val creditsPauseCoordinator: CreditsPauseCoordinator,
    private val settings: OrchestratorSettings,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun pollOnce(projectKey: String = "KAN"): OrchestratorPollResult {
        val issues = jiraClient.findAiIssues(projectKey)
        val activeCreditsPause = creditsPauseCoordinator.activePause(OffsetDateTime.now(clock))
        if (activeCreditsPause != null) {
            return OrchestratorPollResult(issues.map { IssueProcessResult.Skipped(it.key, "credits-paused") })
        }
        val results = issues.map { processIssue(it) } + monitorPullRequests(issues.map { it.key }.toSet())
        return OrchestratorPollResult(results)
    }

    fun processIssue(issue: JiraIssue): IssueProcessResult {
        val currentIssue = costMonitor.applyBudgetTriggers(issue)
        if (currentIssue.fields.paused) {
            return IssueProcessResult.Skipped(currentIssue.key, "paused")
        }
        if (!currentIssue.fields.error.isNullOrBlank()) {
            return IssueProcessResult.Skipped(currentIssue.key, "error")
        }

        val phase = AiPhase.fromJira(currentIssue.fields.aiPhase)
        if (phase == null && !currentIssue.fields.aiPhase.isNullOrBlank()) {
            val message = "[ORCHESTRATOR] Onbekende AI Phase '${currentIssue.fields.aiPhase}'. Corrigeer het veld en leeg `Error` om opnieuw te proberen."
            jiraClient.updateIssueFields(currentIssue.key, JiraFieldUpdate.of(JiraKnownField.ERROR to message))
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
            AiPhase.TESTED_SUCCESSFULLY -> IssueProcessResult.Skipped(currentIssue.key, "tested-successfully")
            AiPhase.REFINING,
            AiPhase.DEVELOPING,
            AiPhase.REVIEWING,
            AiPhase.TESTING,
            -> recoverActivePhase(currentIssue, phase)
        }
    }

    private fun dispatchIfAllowed(issue: JiraIssue, role: AgentRole, sourcePhase: AiPhase?): IssueProcessResult {
        val targetRepo = issue.fields.targetRepo
        if (targetRepo.isNullOrBlank()) {
            val message = "[ORCHESTRATOR] Target Repo ontbreekt; vul het Jira-veld `Target Repo` en leeg `Error` om opnieuw te proberen."
            jiraClient.updateIssueFields(issue.key, JiraFieldUpdate.of(JiraKnownField.ERROR to message))
            return IssueProcessResult.Errored(issue.key, message)
        }

        val storyRun = storyRunRepository.openOrCreate(issue.key, targetRepo)
        val budgetResult = costMonitor.checkBudget(issue, storyRun)
        if (budgetResult.paused) {
            return IssueProcessResult.Skipped(issue.key, "budget-exceeded")
        }

        if (role == AgentRole.DEVELOPER && sourcePhase.isDeveloperLoopbackPhase()) {
            val developerRuns = agentRunRepository.countForRole(storyRun.id, AgentRole.DEVELOPER)
            if (developerRuns >= settings.maxDeveloperLoopbacks + 1) {
                val message = "[ORCHESTRATOR] Developer-loopback cap bereikt (${settings.maxDeveloperLoopbacks}x). " +
                    "Handmatige triage nodig. Geef feedback en leeg `Error` om opnieuw te proberen, " +
                    "of zet `Paused = true` en parkeer dit ticket."
                jiraClient.updateIssueFields(issue.key, JiraFieldUpdate.of(JiraKnownField.ERROR to message))
                return IssueProcessResult.Errored(issue.key, message)
            }
        }

        if (!canDispatch(issue.key, role)) {
            return IssueProcessResult.Skipped(issue.key, "concurrency-cap")
        }

        val activePhase = AiPhase.activeFor(role)
        val startedAt = OffsetDateTime.now(clock)
        jiraClient.updateIssueFields(
            issue.key,
            JiraFieldUpdate.of(
                JiraKnownField.AI_PHASE to activePhase.jiraValue,
                JiraKnownField.AGENT_STARTED_AT to startedAt,
            ),
        )

        return try {
            val dispatch = agentRuntime.dispatch(
                dispatchRequest(
                    issue = issue,
                    targetRepo = targetRepo,
                    storyRun = storyRun,
                    role = role,
                    activePhase = activePhase,
                    sourcePhase = sourcePhase,
                ),
            )
            agentRunRepository.recordStarted(storyRun.id, role, dispatch.containerName, issue.fields.aiLevel)
            IssueProcessResult.Dispatched(issue.key, role, dispatch.containerName)
        } catch (exception: Exception) {
            val message = "[ORCHESTRATOR] Agent dispatch voor ${role.markerKeyPart} faalde: ${exception.message}"
            logger.warn("Agent dispatch failed for {} {}", issue.key, role, exception)
            jiraClient.updateIssueFields(issue.key, JiraFieldUpdate.of(JiraKnownField.ERROR to message))
            IssueProcessResult.Errored(issue.key, message)
        }
    }

    private fun dispatchRequest(
        issue: JiraIssue,
        targetRepo: String,
        storyRun: StoryRunRecord,
        role: AgentRole,
        activePhase: AiPhase,
        sourcePhase: AiPhase?,
    ): AgentDispatchRequest {
        val previewUrl = PreviewTemplateRenderer.render(storyRun.previewUrlTemplate, storyRun.prNumber)
        val previewNamespace = PreviewTemplateRenderer.render(storyRun.previewNamespaceTemplate, storyRun.prNumber)
        return AgentDispatchRequest(
            storyKey = issue.key,
            targetRepo = targetRepo,
            storyRunId = storyRun.id,
            role = role,
            phase = activePhase,
            baseBranch = storyRun.baseBranch,
            branchPrefix = storyRun.branchPrefix,
            prNumber = storyRun.prNumber,
            previewUrl = previewUrl,
            previewNamespace = previewNamespace,
            developerLoopbackReason = sourcePhase.developerLoopbackReason(),
        )
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
            jiraClient.transitionIssue(run.storyKey, "Done")
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
        jiraClient.updateIssueFields(
            run.storyKey,
            JiraFieldUpdate.of(JiraKnownField.AI_PHASE to AiPhase.TESTED_WITH_FEEDBACK_FOR_DEVELOPER.jiraValue),
        )
        return IssueProcessResult.PrCommentTriggered(run.storyKey, prNumber, comments.size)
    }

    private fun cleanupPreviewNamespace(run: StoryRunRecord): Boolean {
        val namespace = PreviewTemplateRenderer.render(run.previewNamespaceTemplate, run.prNumber) ?: return false
        return previewEnvironmentCleaner.cleanup(namespace)
    }

    private fun recoverActivePhase(issue: JiraIssue, phase: AiPhase): IssueProcessResult {
        val role = requireNotNull(phase.activeRole)
        if (agentRuntime.isAgentRunning(issue.key, role)) {
            return IssueProcessResult.Skipped(issue.key, "agent-running")
        }

        val targetRepo = issue.fields.targetRepo.orEmpty()
        val storyRun = storyRunRepository.openOrCreate(issue.key, targetRepo)
        val latestRun = agentRunRepository.latestForRole(storyRun.id, role)
        if (latestRun != null && latestRun.isSuccessful()) {
            val completedPhase = AiPhase.completedAfterSuccessful(role, latestRun.outcome)
            jiraClient.updateIssueFields(issue.key, JiraFieldUpdate.of(JiraKnownField.AI_PHASE to completedPhase.jiraValue))
            return IssueProcessResult.Recovered(issue.key, completedPhase.jiraValue)
        }

        val startedAt = issue.fields.agentStartedAt
        if (startedAt != null && startedAt.plus(settings.hardTimeout).isBefore(OffsetDateTime.now(clock))) {
            val message = "[ORCHESTRATOR] Hard timeout: ${phase.jiraValue} loopt langer dan ${settings.hardTimeout.toMinutes()} minuten zonder voortgang."
            jiraClient.updateIssueFields(issue.key, JiraFieldUpdate.of(JiraKnownField.ERROR to message))
            return IssueProcessResult.Errored(issue.key, message)
        }

        if (latestRun != null && latestRun.isTransientFailure()) {
            val transientFailures = agentRunRepository.recentForRole(
                storyRun.id,
                role,
                settings.maxTransientRetries + 1,
            ).takeWhile { it.isTransientFailure() }.size

            if (transientFailures <= settings.maxTransientRetries) {
                val previousPhase = AiPhase.previousCompletedBeforeRetry(phase)
                jiraClient.updateIssueFields(
                    issue.key,
                    JiraFieldUpdate.of(JiraKnownField.AI_PHASE to previousPhase?.jiraValue),
                )
                return IssueProcessResult.Recovered(issue.key, previousPhase?.jiraValue ?: "<empty>")
            }

            val message = "[ORCHESTRATOR] Transient retry cap bereikt (${settings.maxTransientRetries}x) voor ${role.markerKeyPart}; handmatige triage nodig."
            jiraClient.updateIssueFields(issue.key, JiraFieldUpdate.of(JiraKnownField.ERROR to message))
            return IssueProcessResult.Errored(issue.key, message)
        }

        val message = "[ORCHESTRATOR] Geen actieve container gevonden voor ${phase.jiraValue}; handmatige triage nodig."
        jiraClient.updateIssueFields(issue.key, JiraFieldUpdate.of(JiraKnownField.ERROR to message))
        return IssueProcessResult.Errored(issue.key, message)
    }

    private fun AiPhase?.isDeveloperLoopbackPhase(): Boolean =
        this == AiPhase.REVIEWED_WITH_FEEDBACK_FOR_DEVELOPER || this == AiPhase.TESTED_WITH_FEEDBACK_FOR_DEVELOPER

    private fun AiPhase?.developerLoopbackReason(): String? =
        when (this) {
            AiPhase.REVIEWED_WITH_FEEDBACK_FOR_DEVELOPER -> "Lees eerst het laatste [REVIEWER]-comment en verwerk die feedback op dezelfde branch en PR."
            AiPhase.TESTED_WITH_FEEDBACK_FOR_DEVELOPER -> "Lees eerst het laatste [TESTER]-comment en verwerk die feedback op dezelfde branch en PR."
            else -> null
        }

    private fun AgentRunRecord.isSuccessful(): Boolean =
        endedAt != null && outcome?.contains("error", ignoreCase = true) != true && outcome?.contains("failed", ignoreCase = true) != true

    private fun AgentRunRecord.isTransientFailure(): Boolean {
        val text = listOfNotNull(outcome, summaryText).joinToString(" ").lowercase()
        return transientFailureTokens.any { it in text }
    }

    companion object {
        private val transientFailureTokens = listOf("http 429", "api error 500", "rate limit", "timeout")
    }
}

data class OrchestratorPollResult(
    val issueResults: List<IssueProcessResult>,
)

sealed interface IssueProcessResult {
    val storyKey: String

    data class Dispatched(
        override val storyKey: String,
        val role: AgentRole,
        val containerName: String,
    ) : IssueProcessResult

    data class Skipped(
        override val storyKey: String,
        val reason: String,
    ) : IssueProcessResult

    data class Recovered(
        override val storyKey: String,
        val phase: String,
    ) : IssueProcessResult

    data class Errored(
        override val storyKey: String,
        val message: String,
    ) : IssueProcessResult

    data class Merged(
        override val storyKey: String,
        val prNumber: Int,
    ) : IssueProcessResult

    data class PrCommentTriggered(
        override val storyKey: String,
        val prNumber: Int,
        val commentCount: Int,
    ) : IssueProcessResult
}
