package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.github.PullRequestClient
import nl.vdzon.softwarefactory.jira.AgentRole
import nl.vdzon.softwarefactory.jira.AiLevelTrigger
import nl.vdzon.softwarefactory.jira.FactoryCommand
import nl.vdzon.softwarefactory.jira.JiraClient
import nl.vdzon.softwarefactory.jira.JiraCommandInstruction
import nl.vdzon.softwarefactory.jira.JiraCommentInstruction
import nl.vdzon.softwarefactory.jira.JiraCommentParser
import nl.vdzon.softwarefactory.jira.JiraFieldUpdate
import nl.vdzon.softwarefactory.jira.JiraIssue
import nl.vdzon.softwarefactory.jira.JiraKnownField
import nl.vdzon.softwarefactory.jira.ProcessedCommentService
import nl.vdzon.softwarefactory.preview.PreviewEnvironmentCleaner
import nl.vdzon.softwarefactory.preview.PreviewTemplateRenderer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime

interface ManualCommandProcessor {
    fun apply(issue: JiraIssue): ManualCommandApplication
}

data class ManualCommandApplication(
    val issue: JiraIssue,
    val stopResult: IssueProcessResult? = null,
)

@Service
class ManualCommandService(
    private val jiraClient: JiraClient,
    private val processedCommentService: ProcessedCommentService,
    private val agentRuntime: AgentRuntime,
    private val storyRunRepository: StoryRunRepository,
    private val pullRequestClient: PullRequestClient,
    private val previewEnvironmentCleaner: PreviewEnvironmentCleaner,
    private val clock: Clock,
) : ManualCommandProcessor {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun apply(issue: JiraIssue): ManualCommandApplication {
        var current = issue
        issue.comments.forEach { comment ->
            if (processedCommentService.isProcessed(issue.key, comment.id, AgentRole.ORCHESTRATOR)) {
                return@forEach
            }

            val instructions = JiraCommentParser.parseInstructions(comment.body)
                .filter { it is JiraCommandInstruction || it is AiLevelTrigger }
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
        issue: JiraIssue,
        instructions: List<JiraCommentInstruction>,
    ): ManualCommandApplication {
        var current = issue
        instructions.forEach { instruction ->
            when (instruction) {
                is AiLevelTrigger -> current = setAiLevel(current, instruction.level)
                is JiraCommandInstruction -> {
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

    private fun applyCommand(issue: JiraIssue, command: FactoryCommand): ManualCommandApplication =
        when (command) {
            FactoryCommand.PAUSE -> {
                val updated = updateIssue(issue, JiraKnownField.PAUSED to true)
                ManualCommandApplication(updated, IssueProcessResult.Skipped(issue.key, "paused"))
            }
            FactoryCommand.RESUME -> {
                val updated = updateIssue(issue, JiraKnownField.PAUSED to false, JiraKnownField.ERROR to null)
                ManualCommandApplication(updated)
            }
            FactoryCommand.KILL -> {
                agentRuntime.killForStory(issue.key)
                val updated = updateIssue(issue, JiraKnownField.PAUSED to true)
                ManualCommandApplication(updated, IssueProcessResult.Skipped(issue.key, "killed"))
            }
            FactoryCommand.DELETE -> delete(issue)
            FactoryCommand.MERGE -> merge(issue)
            FactoryCommand.RE_IMPLEMENT -> reImplement(issue)
        }

    private fun setAiLevel(issue: JiraIssue, level: Int): JiraIssue {
        jiraClient.updateIssueFields(issue.key, JiraFieldUpdate.of(JiraKnownField.AI_LEVEL to level))
        return issue.copy(fields = issue.fields.copy(aiLevel = level))
    }

    private fun delete(issue: JiraIssue): ManualCommandApplication {
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
            jiraClient.updateIssueSummary(issue.key, summary)
        }
        run?.let { storyRunRepository.close(it.id, "deleted", OffsetDateTime.now(clock)) }
        jiraClient.transitionIssue(issue.key, "Done")
        return ManualCommandApplication(
            issue.copy(status = "Done", summary = summary),
            IssueProcessResult.Skipped(issue.key, "deleted"),
        )
    }

    private fun merge(issue: JiraIssue): ManualCommandApplication {
        val run = activeRun(issue.key)
            ?: throw IllegalStateException("Geen actieve story-run gevonden om te mergen.")
        val prNumber = run.prNumber
            ?: throw IllegalStateException("Geen actieve PR gevonden om te mergen.")
        agentRuntime.killForStory(issue.key)
        pullRequestClient.mergePullRequest(run.targetRepo, prNumber)
        cleanupPreview(run)
        storyRunRepository.close(run.id, "merged", OffsetDateTime.now(clock))
        jiraClient.transitionIssue(issue.key, "Done")
        return ManualCommandApplication(
            issue.copy(status = "Done"),
            IssueProcessResult.Merged(issue.key, prNumber),
        )
    }

    private fun reImplement(issue: JiraIssue): ManualCommandApplication {
        val run = activeRun(issue.key)
        agentRuntime.killForStory(issue.key)
        closePullRequest(run)
        deleteBranch(run)
        cleanupPreview(run)
        jiraClient.deleteAgentComments(issue.key)
        run?.let { storyRunRepository.close(it.id, "re-implement", OffsetDateTime.now(clock)) }
        val updated = updateIssue(
            issue,
            JiraKnownField.AI_PHASE to null,
            JiraKnownField.PAUSED to false,
            JiraKnownField.ERROR to null,
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
        val namespace = PreviewTemplateRenderer.render(run?.previewNamespaceTemplate, run?.prNumber) ?: return
        previewEnvironmentCleaner.cleanup(namespace)
    }

    private fun updateIssue(issue: JiraIssue, vararg updates: Pair<JiraKnownField, Any?>): JiraIssue {
        jiraClient.updateIssueFields(issue.key, JiraFieldUpdate.of(*updates))
        var fields = issue.fields
        updates.forEach { (field, value) ->
            fields = when (field) {
                JiraKnownField.AI_PHASE -> fields.copy(aiPhase = value as String?)
                JiraKnownField.AI_LEVEL -> fields.copy(aiLevel = value as Int?)
                JiraKnownField.AI_TOKEN_BUDGET -> fields.copy(aiTokenBudget = value as Long?)
                JiraKnownField.AI_TOKENS_USED -> fields.copy(aiTokensUsed = value as Long?)
                JiraKnownField.AGENT_STARTED_AT -> fields.copy(agentStartedAt = value as OffsetDateTime?)
                JiraKnownField.PAUSED -> fields.copy(paused = value as Boolean)
                JiraKnownField.ERROR -> fields.copy(error = value as String?)
                JiraKnownField.TARGET_REPO -> fields.copy(targetRepo = value as String?)
            }
        }
        return issue.copy(fields = fields)
    }

    private fun failed(issue: JiraIssue, exception: Throwable): ManualCommandApplication {
        val message = "[ORCHESTRATOR] Handmatig commando faalde: ${exception.message ?: exception::class.simpleName}"
        jiraClient.updateIssueFields(issue.key, JiraFieldUpdate.of(JiraKnownField.ERROR to message))
        return ManualCommandApplication(
            issue.copy(fields = issue.fields.copy(error = message)),
            IssueProcessResult.Errored(issue.key, message),
        )
    }

    companion object {
        private const val CANCELLED_PREFIX = "(CANCELLED)"
    }
}
