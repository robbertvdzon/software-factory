package nl.vdzon.softwarefactory.orchestrator.services

import nl.vdzon.softwarefactory.orchestrator.IssueProcessResult
import nl.vdzon.softwarefactory.orchestrator.AgentRuntime
import nl.vdzon.softwarefactory.orchestrator.StoryRunRecord
import nl.vdzon.softwarefactory.orchestrator.StoryRunRepository
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.youtrack.AgentRole
import nl.vdzon.softwarefactory.youtrack.AiLevelTrigger
import nl.vdzon.softwarefactory.youtrack.AiSupplierTrigger
import nl.vdzon.softwarefactory.youtrack.FactoryCommand
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.youtrack.TrackerCommandInstruction
import nl.vdzon.softwarefactory.youtrack.TrackerCommentInstruction
import nl.vdzon.softwarefactory.youtrack.TrackerFieldUpdate
import nl.vdzon.softwarefactory.youtrack.TrackerIssue
import nl.vdzon.softwarefactory.youtrack.TrackerField
import nl.vdzon.softwarefactory.youtrack.ProcessedCommentsApi
import nl.vdzon.softwarefactory.preview.PreviewApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime

interface ManualCommandProcessor {
    fun apply(issue: TrackerIssue): ManualCommandApplication
}

data class ManualCommandApplication(
    val issue: TrackerIssue,
    val stopResult: IssueProcessResult? = null,
)

@Service
class ManualCommandService(
    private val issueTrackerClient: YouTrackApi,
    private val processedCommentService: ProcessedCommentsApi,
    private val agentRuntime: AgentRuntime,
    private val storyRunRepository: StoryRunRepository,
    private val pullRequestClient: GitHubApi,
    private val previewApi: PreviewApi,
    private val clock: Clock,
) : ManualCommandProcessor {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun apply(issue: TrackerIssue): ManualCommandApplication {
        var current = issue
        issue.comments.forEach { comment ->
            if (processedCommentService.isProcessed(issue.key, comment.id, AgentRole.ORCHESTRATOR)) {
                return@forEach
            }

            val instructions = issueTrackerClient.parseInstructions(comment.body)
                .filter { it is TrackerCommandInstruction || it is AiLevelTrigger || it is AiSupplierTrigger }
            if (instructions.isEmpty()) {
                return@forEach
            }

            val application = runCatching {
                applyInstructions(current, instructions)
            }.getOrElse { exception ->
                logger.warn("Manual command failed for {}", issue.key, exception)
                return failed(current, exception)
            }

            processedCommentService.markProcessed(issue.key, comment.id, AgentRole.ORCHESTRATOR)
            current = application.issue
            if (application.stopResult != null) {
                return application
            }
        }
        return ManualCommandApplication(current)
    }

    private fun applyInstructions(
        issue: TrackerIssue,
        instructions: List<TrackerCommentInstruction>,
    ): ManualCommandApplication {
        var current = issue
        instructions.forEach { instruction ->
            when (instruction) {
                is AiLevelTrigger -> current = setAiLevel(current, instruction.level)
                is AiSupplierTrigger -> current = setAiSupplier(current, instruction.supplier)
                is TrackerCommandInstruction -> {
                    val result = applyCommand(current, instruction.command)
                    current = result.issue
                    if (result.stopResult != null) {
                        return result
                    }
                }
                else -> Unit
            }
        }
        return ManualCommandApplication(current)
    }

    private fun applyCommand(issue: TrackerIssue, command: FactoryCommand): ManualCommandApplication =
        when (command) {
            FactoryCommand.PAUSE -> {
                val updated = updateIssue(issue, TrackerField.PAUSED to true)
                ManualCommandApplication(updated, IssueProcessResult.Skipped(issue.key, "paused"))
            }
            FactoryCommand.RESUME -> {
                val updated = updateIssue(issue, TrackerField.PAUSED to false, TrackerField.ERROR to null)
                ManualCommandApplication(updated)
            }
            FactoryCommand.KILL -> {
                agentRuntime.killForStory(issue.key)
                val updated = updateIssue(issue, TrackerField.PAUSED to true)
                ManualCommandApplication(updated, IssueProcessResult.Skipped(issue.key, "killed"))
            }
            FactoryCommand.DELETE -> delete(issue)
            FactoryCommand.MERGE -> merge(issue)
            FactoryCommand.RE_IMPLEMENT -> reImplement(issue)
        }

    private fun setAiLevel(issue: TrackerIssue, level: Int): TrackerIssue {
        issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.AI_LEVEL to level))
        return issue.copy(fields = issue.fields.copy(aiLevel = level))
    }

    private fun setAiSupplier(issue: TrackerIssue, supplier: String): TrackerIssue {
        issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.AI_SUPPLIER to supplier))
        return issue.copy(fields = issue.fields.copy(aiSupplier = supplier))
    }

    private fun delete(issue: TrackerIssue): ManualCommandApplication {
        val run = activeRun(issue.key)
        agentRuntime.killForStory(issue.key)
        closePullRequest(run)
        deleteBranch(run)
        cleanupPreview(run)
        val summary = if (issue.summary.startsWith(CANCELLED_PREFIX, ignoreCase = true)) {
            issue.summary
        } else {
            "$CANCELLED_PREFIX ${issue.summary}"
        }
        if (summary != issue.summary) {
            issueTrackerClient.updateIssueSummary(issue.key, summary)
        }
        run?.let { storyRunRepository.close(it.id, "deleted", OffsetDateTime.now(clock)) }
        issueTrackerClient.transitionIssue(issue.key, "Done")
        return ManualCommandApplication(
            issue.copy(status = "Done", summary = summary),
            IssueProcessResult.Skipped(issue.key, "deleted"),
        )
    }

    private fun merge(issue: TrackerIssue): ManualCommandApplication {
        val run = activeRun(issue.key)
            ?: throw IllegalStateException("Geen actieve story-run gevonden om te mergen.")
        val prNumber = run.prNumber
            ?: throw IllegalStateException("Geen actieve PR gevonden om te mergen.")
        agentRuntime.killForStory(issue.key)
        pullRequestClient.mergePullRequest(run.targetRepo, prNumber)
        cleanupPreview(run)
        storyRunRepository.close(run.id, "merged", OffsetDateTime.now(clock))
        issueTrackerClient.transitionIssue(issue.key, "Done")
        return ManualCommandApplication(
            issue.copy(status = "Done"),
            IssueProcessResult.Merged(issue.key, prNumber),
        )
    }

    private fun reImplement(issue: TrackerIssue): ManualCommandApplication {
        val run = activeRun(issue.key)
        agentRuntime.killForStory(issue.key)
        closePullRequest(run)
        deleteBranch(run)
        cleanupPreview(run)
        issueTrackerClient.deleteAgentComments(issue.key)
        run?.let { storyRunRepository.close(it.id, "re-implement", OffsetDateTime.now(clock)) }
        val updated = updateIssue(
            issue,
            TrackerField.AI_PHASE to null,
            TrackerField.PAUSED to false,
            TrackerField.ERROR to null,
        )
        return ManualCommandApplication(updated, IssueProcessResult.Skipped(issue.key, "re-implement"))
    }

    private fun activeRun(storyKey: String): StoryRunRecord? =
        storyRunRepository.activeRuns()
            .filter { it.storyKey == storyKey }
            .maxByOrNull { it.id }

    private fun closePullRequest(run: StoryRunRecord?) {
        val prNumber = run?.prNumber ?: return
        pullRequestClient.closePullRequest(run.targetRepo, prNumber)
    }

    private fun deleteBranch(run: StoryRunRecord?) {
        val branchName = run?.branchName?.takeIf { it.isNotBlank() } ?: return
        pullRequestClient.deleteBranch(run.targetRepo, branchName)
    }

    private fun cleanupPreview(run: StoryRunRecord?) {
        val namespace = previewApi.render(run?.previewNamespaceTemplate, run?.prNumber) ?: return
        previewApi.cleanup(namespace)
    }

    private fun updateIssue(issue: TrackerIssue, vararg updates: Pair<TrackerField, Any?>): TrackerIssue {
        issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(*updates))
        var fields = issue.fields
        updates.forEach { (field, value) ->
            fields = when (field) {
                TrackerField.AI_PHASE -> fields.copy(aiPhase = value as String?)
                TrackerField.AI_LEVEL -> fields.copy(aiLevel = value as Int?)
                TrackerField.AI_TOKEN_BUDGET -> fields.copy(aiTokenBudget = value as Long?)
                TrackerField.AI_TOKENS_USED -> fields.copy(aiTokensUsed = value as Long?)
                TrackerField.AGENT_STARTED_AT -> fields.copy(agentStartedAt = value as OffsetDateTime?)
                TrackerField.PAUSED -> fields.copy(paused = value as Boolean)
                TrackerField.ERROR -> fields.copy(error = value as String?)
                TrackerField.AI_SUPPLIER -> fields.copy(aiSupplier = value as String?)
            }
        }
        return issue.copy(fields = fields)
    }

    private fun failed(issue: TrackerIssue, exception: Throwable): ManualCommandApplication {
        val message = "[ORCHESTRATOR] Handmatig commando faalde: ${exception.message ?: exception::class.simpleName}"
        issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.ERROR to message))
        return ManualCommandApplication(
            issue.copy(fields = issue.fields.copy(error = message)),
            IssueProcessResult.Errored(issue.key, message),
        )
    }

    companion object {
        private const val CANCELLED_PREFIX = "(CANCELLED)"
    }
}
