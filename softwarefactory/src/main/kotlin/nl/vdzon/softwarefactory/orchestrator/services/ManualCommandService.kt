package nl.vdzon.softwarefactory.orchestrator.services

import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.core.AgentRuntime
import nl.vdzon.softwarefactory.core.StoryRunRecord
import nl.vdzon.softwarefactory.core.StoryRunRepository
import nl.vdzon.softwarefactory.core.OrchestratorSettings
import nl.vdzon.softwarefactory.core.ManualCommandProcessor
import nl.vdzon.softwarefactory.core.ManualCommandApplication
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.github.GitHubClientException
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.AiLevelTrigger
import nl.vdzon.softwarefactory.core.AiSupplierTrigger
import nl.vdzon.softwarefactory.core.AutoApproveTrigger
import nl.vdzon.softwarefactory.core.FactoryCommand
import nl.vdzon.softwarefactory.core.IssueType
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.core.TrackerCommandInstruction
import nl.vdzon.softwarefactory.core.TrackerCommentInstruction
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.youtrack.ProcessedCommentsApi
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.core.StoryWorkspaceApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime

@Service
class ManualCommandService(
    private val issueTrackerClient: YouTrackApi,
    private val processedCommentService: ProcessedCommentsApi,
    private val agentRuntime: AgentRuntime,
    private val storyRunRepository: StoryRunRepository,
    private val pullRequestClient: GitHubApi,
    private val previewApi: PreviewApi,
    private val storyWorkspaceService: StoryWorkspaceApi? = null,
    private val settings: OrchestratorSettings,
    private val clock: Clock,
) : ManualCommandProcessor {
    private val logger = LoggerFactory.getLogger(javaClass)

    // YouTrack State-lane (board-kolom) waar een re-implement de issue in terugzet: de 'todo'-kolom.
    private val STATE_TODO = "Open"

    override fun apply(issue: TrackerIssue): ManualCommandApplication {
        var current = issue
        issue.comments.forEach { comment ->
            val instructions = issueTrackerClient.parseInstructions(comment.body)
                .filter { it is TrackerCommandInstruction || it is AiLevelTrigger || it is AiSupplierTrigger || it is AutoApproveTrigger }
            if (instructions.isEmpty()) {
                return@forEach
            }

            if (processedCommentService.isProcessed(issue.key, comment.id, AgentRole.ORCHESTRATOR)) {
                return@forEach
            }

            val application = runCatching {
                applyInstructions(current, instructions, comment.body)
            }.getOrElse { exception ->
                logger.warn("Manual command failed for {}", issue.key, exception)
                // Markeer óók een gefaald command als verwerkt: anders leest elke volgende poll dezelfde
                // command-comment opnieuw en blijft 'ie eindeloos falen. Het draait dus één keer, post de
                // fout, en stopt; de gebruiker kan het bewust opnieuw geven.
                runCatching { processedCommentService.markProcessed(issue.key, comment.id, AgentRole.ORCHESTRATOR) }
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
        commentBody: String,
    ): ManualCommandApplication {
        var current = issue
        instructions.forEach { instruction ->
            when (instruction) {
                is AiLevelTrigger -> current = setAiLevel(current, instruction.level)
                is AiSupplierTrigger -> current = setAiSupplier(current, instruction.supplier)
                is AutoApproveTrigger -> current = setAutoApprove(current, instruction.enabled)
                is TrackerCommandInstruction -> {
                    val result = applyCommand(current, instruction.command, commentBody)
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

    private fun applyCommand(issue: TrackerIssue, command: FactoryCommand, commentBody: String): ManualCommandApplication =
        when (command) {
            FactoryCommand.APPROVE -> manualApprove(issue)
            FactoryCommand.REJECT -> manualReject(issue, reasonFrom(commentBody))
            FactoryCommand.PAUSE -> {
                val updated = updateIssue(issue, TrackerField.PAUSED to true)
                ManualCommandApplication(updated, IssueProcessResult.Skipped(issue.key, "paused"))
            }
            FactoryCommand.RESUME -> {
                resume(issue)
            }
            FactoryCommand.KILL -> {
                agentRuntime.killForStory(issue.key)
                val updated = updateIssue(issue, TrackerField.PAUSED to true)
                ManualCommandApplication(updated, IssueProcessResult.Skipped(issue.key, "killed"))
            }
            FactoryCommand.DELETE -> delete(issue)
            FactoryCommand.MERGE -> merge(issue)
            FactoryCommand.RE_IMPLEMENT -> reImplement(issue)
            FactoryCommand.CLEAR_ERROR -> clearError(issue)
            FactoryCommand.RETRY_CURRENT_STEP -> retryCurrentStep(issue)
        }

    private fun setAiLevel(issue: TrackerIssue, level: Int): TrackerIssue {
        issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.AI_LEVEL to level))
        return issue.copy(fields = issue.fields.copy(aiLevel = level))
    }

    private fun setAiSupplier(issue: TrackerIssue, supplier: String): TrackerIssue {
        issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.AI_SUPPLIER to supplier))
        return issue.copy(fields = issue.fields.copy(aiSupplier = supplier))
    }

    private fun setAutoApprove(issue: TrackerIssue, enabled: Boolean): TrackerIssue {
        if (issue.fields.autoApprove == enabled) {
            return issue
        }
        issueTrackerClient.updateIssueFields(
            issue.key,
            TrackerFieldUpdate.of(TrackerField.AUTO_APPROVE to if (enabled) "on" else "off"),
        )
        return issue.copy(fields = issue.fields.copy(autoApprove = enabled))
    }

    private fun delete(issue: TrackerIssue): ManualCommandApplication {
        val run = activeRun(issue.key)
        agentRuntime.killForStory(issue.key)
        closePullRequest(run)
        deleteBranch(run)
        cleanupPreview(run)
        cleanupWorkspace(issue.key)
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

        try {
            // `gh pr merge --squash` voert de merge volledig op de GitHub-remote uit en werkt main
            // daar meteen bij. Een lokale `git push origin main` daarna is overbodig én faalt altijd
            // (remote main is net opgeschoven → non-fast-forward), dus die doen we bewust niet.
            logger.info("Merge: merging PR #{} voor {}", prNumber, issue.key)
            pullRequestClient.mergePullRequest(run.targetRepo, prNumber)
            logger.info("Merge: PR #{} merged successfully voor {}", prNumber, issue.key)
        } catch (e: GitHubClientException) {
            // Merge-conflicten of andere GitHub-fouten → zet in error en return
            val errorMsg = "[ORCHESTRATOR] Merge faalde: ${e.message ?: "GitHub API error"}"
            logger.warn("Merge conflict detected for {}: {}", issue.key, e.message)
            issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.ERROR to errorMsg))
            return ManualCommandApplication(
                issue.copy(fields = issue.fields.copy(error = errorMsg)),
                IssueProcessResult.Errored(issue.key, errorMsg),
            )
        } catch (e: Exception) {
            // Andere fouten (fetch/push) → ook in error
            val errorMsg = "[ORCHESTRATOR] Merge workflow faalde: ${e.message ?: "Git command failed"}"
            logger.warn("Merge workflow error for {}: {}", issue.key, e.message, e)
            issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(TrackerField.ERROR to errorMsg))
            return ManualCommandApplication(
                issue.copy(fields = issue.fields.copy(error = errorMsg)),
                IssueProcessResult.Errored(issue.key, errorMsg),
            )
        }

        // De PR is nu gemerged (onomkeerbaar). Opruimen is best-effort: faalt het (bv. een verlopen
        // OpenShift-token bij de preview-cleanup), dan ronden we de merge alsnog netjes af i.p.v. 'm te
        // laten falen en eindeloos opnieuw te proberen.
        runCatching { cleanupPreview(run) }
            .onFailure { logger.warn("Merge: preview-cleanup faalde voor {} (merge is al klaar, genegeerd): {}", issue.key, it.message) }
        runCatching { cleanupWorkspace(issue.key) }
            .onFailure { logger.warn("Merge: workspace-cleanup faalde voor {} (genegeerd): {}", issue.key, it.message) }
        storyRunRepository.close(run.id, "merged", OffsetDateTime.now(clock))
        issueTrackerClient.transitionIssue(issue.key, "Done")
        logger.info("Merge completed successfully for {} with PR #{}", issue.key, prNumber)
        return ManualCommandApplication(
            issue.copy(status = "Done"),
            IssueProcessResult.Merged(issue.key, prNumber),
        )
    }

    private fun reImplement(issue: TrackerIssue): ManualCommandApplication =
        when (issue.issueType) {
            IssueType.SUBTASK -> reImplementSubtask(issue)
            IssueType.STORY -> reImplementStory(issue)
        }

    /** Story opnieuw: gooi de gedeelde branch/PR/run weg en herstart de refine-flow. */
    private fun reImplementStory(issue: TrackerIssue): ManualCommandApplication {
        val run = activeRun(issue.key)
        agentRuntime.killForStory(issue.key)
        cleanupRemotePullRequestForReImplementation(run)
        cleanupPreview(run)
        resetWorkspaceForReImplementation(run)
        issueTrackerClient.deleteAgentComments(issue.key)
        deleteSubtasksForReImplementation(issue.key)
        run?.let { storyRunRepository.delete(it.id) }
        val updated = updateIssue(
            issue,
            TrackerField.STORY_PHASE to null,
            TrackerField.AI_MAX_DEVELOPER_LOOPBACKS to null,
            TrackerField.AI_TOKENS_USED to null,
            TrackerField.AGENT_STARTED_AT to null,
            TrackerField.PAUSED to false,
            TrackerField.ERROR to null,
        )
        // Lege fase = niet opgepakt; zet 'm terug in de todo-lane zodat je 'm daarna handmatig start.
        issueTrackerClient.transitionIssue(issue.key, STATE_TODO)
        return ManualCommandApplication(updated, IssueProcessResult.Skipped(issue.key, "re-implement"))
    }

    /**
     * Subtask opnieuw: herstart de subtask-pipeline (Subtask Phase → leeg) op de
     * gedeelde branch. We laten de story-run/branch/PR staan (siblings delen die);
     * de draaiende agent op de parent wordt wel gekild.
     */
    private fun reImplementSubtask(issue: TrackerIssue): ManualCommandApplication {
        issueTrackerClient.parentStoryKey(issue.key)?.let { agentRuntime.killForStory(it) }
        issueTrackerClient.deleteAgentComments(issue.key)
        val updated = updateIssue(
            issue,
            TrackerField.SUBTASK_PHASE to null,
            TrackerField.AGENT_STARTED_AT to null,
            TrackerField.PAUSED to false,
            TrackerField.ERROR to null,
        )
        // Lege fase = niet opgepakt; zet 'm terug in de todo-lane (consistent met story-re-implement).
        issueTrackerClient.transitionIssue(issue.key, STATE_TODO)
        return ManualCommandApplication(updated, IssueProcessResult.Skipped(issue.key, "re-implement"))
    }

    private fun clearError(issue: TrackerIssue): ManualCommandApplication {
        val updated = updateIssue(issue, TrackerField.ERROR to null)
        // Op story-niveau leegt 'clear error' ook de errors van de subtaken — de error op het
        // storyscherm komt meestal van een vastgelopen subtaak, dus die moet mee.
        if (issue.issueType == IssueType.STORY) {
            runCatching { issueTrackerClient.subtasksOf(issue.key) }
                .getOrDefault(emptyList())
                .filter { !it.fields.error.isNullOrBlank() }
                .forEach { sub ->
                    issueTrackerClient.updateIssueFields(sub.key, TrackerFieldUpdate.of(TrackerField.ERROR to null))
                }
        }
        return ManualCommandApplication(updated)
    }

    private fun resume(issue: TrackerIssue): ManualCommandApplication {
        val updates = mutableListOf<Pair<TrackerField, Any?>>(
            TrackerField.PAUSED to false,
            TrackerField.ERROR to null,
        )
        if (isDeveloperLoopbackCapError(issue.fields.error)) {
            val nextLimit = issue.fields.developerLoopbackLimit(settings.maxDeveloperLoopbacks) + LOOPBACK_RESUME_INCREMENT
            updates += TrackerField.AI_MAX_DEVELOPER_LOOPBACKS to nextLimit
        }
        val updated = updateIssue(issue, *updates.toTypedArray())
        return ManualCommandApplication(updated)
    }

    private fun retryCurrentStep(issue: TrackerIssue): ManualCommandApplication {
        // v2: kill de draaiende agent (subtask = parent-branch) en leeg Error/Started;
        // de actieve Story/Subtask Phase blijft staan → de recovery-poll herstart de stap.
        val killKey = if (issue.issueType == IssueType.SUBTASK) {
            issueTrackerClient.parentStoryKey(issue.key) ?: issue.key
        } else {
            issue.key
        }
        agentRuntime.killForStory(killKey)
        val updated = updateIssue(
            issue,
            TrackerField.AGENT_STARTED_AT to null,
            TrackerField.PAUSED to false,
            TrackerField.ERROR to null,
        )
        return ManualCommandApplication(updated, IssueProcessResult.Skipped(issue.key, "retry-current-step"))
    }

    /**
     * SF-192 — approve via de manual-approve-poort: zet de poort-subtaak op `manually-approved`
     * (terminaal → de coördinator laat de keten doorlopen). No-op als de subtaak niet in
     * `manual-approve-needed` staat, zodat het commando geen andere subtaaktypes raakt.
     */
    private fun manualApprove(issue: TrackerIssue): ManualCommandApplication {
        if (!isManualApproveGate(issue)) {
            return ManualCommandApplication(issue)
        }
        val updated = updateIssue(issue, TrackerField.SUBTASK_PHASE to SubtaskPhase.MANUALLY_APPROVED.trackerValue)
        return ManualCommandApplication(updated, IssueProcessResult.Skipped(issue.key, "manually-approved"))
    }

    /**
     * SF-192 — reject via de manual-approve-poort: schrijf de afkeurreden in een gemarkeerd blok in de
     * story-description (zodat developer/reviewer/tester die meekrijgen) en zet de poort-subtaak op
     * `manually-not-approved` (de coördinator voert daarop de volledige story-reset uit). No-op als de
     * subtaak niet in `manual-approve-needed` staat.
     */
    private fun manualReject(issue: TrackerIssue, reason: String?): ManualCommandApplication {
        if (!isManualApproveGate(issue)) {
            return ManualCommandApplication(issue)
        }
        writeRejectionReasonToStory(issue, reason)
        val updated = updateIssue(issue, TrackerField.SUBTASK_PHASE to SubtaskPhase.MANUALLY_NOT_APPROVED.trackerValue)
        return ManualCommandApplication(updated, IssueProcessResult.Skipped(issue.key, "manually-not-approved"))
    }

    /** Of [issue] de manual-approve-poort is die nú op een mens wacht. */
    private fun isManualApproveGate(issue: TrackerIssue): Boolean =
        SubtaskPhase.fromTracker(issue.fields.subtaskPhase) == SubtaskPhase.MANUAL_APPROVE_NEEDED

    /** De afkeurreden uit de command-comment: alles behalve het `@factory:command:...`-token. */
    private fun reasonFrom(commentBody: String): String? =
        commentBody.replace(Regex("(?i)@factory:command:[a-z-]+"), "").trim().takeIf { it.isNotBlank() }

    /**
     * Werkt de PARENT-story-description bij met de afkeurreden in een herhaalbaar te overschrijven
     * gemarkeerd blok. Bestaat het blok al (vorige afkeuring), dan wordt het vervangen i.p.v. gestapeld.
     */
    private fun writeRejectionReasonToStory(gate: TrackerIssue, reason: String?) {
        val parentKey = issueTrackerClient.parentStoryKey(gate.key) ?: return
        val parent = runCatching { issueTrackerClient.getIssue(parentKey) }.getOrElse {
            logger.warn("Manual-approve reject: kon story {} niet laden voor de reden.", parentKey, it)
            return
        }
        val block = buildString {
            append(MANUAL_APPROVE_FEEDBACK_START)
            append("\n## Handmatige afkeur-feedback\n")
            append(reason?.takeIf { it.isNotBlank() } ?: "(geen reden opgegeven)")
            append("\n")
            append(MANUAL_APPROVE_FEEDBACK_END)
        }
        val existing = parent.description.orEmpty()
        val blockRegex = Regex(
            Regex.escape(MANUAL_APPROVE_FEEDBACK_START) + ".*?" + Regex.escape(MANUAL_APPROVE_FEEDBACK_END),
            RegexOption.DOT_MATCHES_ALL,
        )
        val updatedDescription = if (blockRegex.containsMatchIn(existing)) {
            blockRegex.replace(existing, Regex.escapeReplacement(block))
        } else if (existing.isBlank()) {
            block
        } else {
            "$existing\n\n$block"
        }
        runCatching { issueTrackerClient.updateIssueDescription(parentKey, updatedDescription) }
            .onFailure { logger.warn("Manual-approve reject: kon story-description van {} niet bijwerken.", parentKey, it) }
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

    private fun cleanupRemotePullRequestForReImplementation(run: StoryRunRecord?) {
        if (run == null || !isGithubComRepo(run.targetRepo)) {
            return
        }
        runCatching {
            closePullRequest(run)
        }.onFailure { exception ->
            logger.warn("Failed to close PR during re-implement for {}", run.storyKey, exception)
        }
        runCatching {
            deleteBranch(run)
        }.onFailure { exception ->
            logger.warn("Failed to delete remote branch during re-implement for {}", run.storyKey, exception)
        }
    }

    private fun resetWorkspaceForReImplementation(run: StoryRunRecord?) {
        if (run == null) {
            return
        }
        val workspaceService = storyWorkspaceService ?: return
        runCatching {
            workspaceService.resetForReImplementation(run)
        }.onFailure { exception ->
            logger.warn("Failed to reset story workspace for re-implement: {}", run.storyKey, exception)
        }
    }

    /**
     * Wis de bestaande subtaken bij een story-re-implement: de refine/plan-flow start opnieuw en
     * de planner maakt verse subtaken aan. Onomkeerbaar; per subtask defensief zodat één mislukte
     * verwijdering de re-implement niet blokkeert.
     */
    private fun deleteSubtasksForReImplementation(storyKey: String) {
        val subtasks = runCatching { issueTrackerClient.subtasksOf(storyKey) }
            .getOrElse { exception ->
                logger.warn("Failed to load subtasks for re-implement of {}", storyKey, exception)
                return
            }
        subtasks.forEach { subtask ->
            runCatching { issueTrackerClient.deleteIssue(subtask.key) }
                .onFailure { exception ->
                    logger.warn("Failed to delete subtask {} during re-implement of {}", subtask.key, storyKey, exception)
                }
        }
        if (subtasks.isNotEmpty()) {
            logger.info("Re-implement: removed {} subtask(s) for story {}", subtasks.size, storyKey)
        }
    }

    private fun cleanupPreview(run: StoryRunRecord?) {
        val namespace = previewApi.render(run?.previewNamespaceTemplate, run?.prNumber) ?: return
        previewApi.cleanup(namespace)
    }

    private fun cleanupWorkspace(storyKey: String) {
        runCatching {
            storyWorkspaceService?.cleanup(storyKey)
        }.onFailure { exception ->
            logger.warn("Failed to cleanup story workspace for {}", storyKey, exception)
        }
    }

    private fun isGithubComRepo(targetRepo: String): Boolean =
        targetRepo.contains("github.com", ignoreCase = true)

    private fun updateIssue(issue: TrackerIssue, vararg updates: Pair<TrackerField, Any?>): TrackerIssue {
        issueTrackerClient.updateIssueFields(issue.key, TrackerFieldUpdate.of(*updates))
        var fields = issue.fields
        updates.forEach { (field, value) ->
            fields = when (field) {
                TrackerField.AI_PHASE -> fields.copy(aiPhase = value as String?)
                TrackerField.AI_LEVEL -> fields.copy(aiLevel = value as Int?)
                TrackerField.AI_MAX_DEVELOPER_LOOPBACKS -> fields.copy(aiMaxDeveloperLoopbacks = value as Int?)
                TrackerField.AI_TOKEN_BUDGET -> fields.copy(aiTokenBudget = value as Long?)
                TrackerField.AI_TOKENS_USED -> fields.copy(aiTokensUsed = value as Long?)
                TrackerField.AGENT_STARTED_AT -> fields.copy(agentStartedAt = value as OffsetDateTime?)
                TrackerField.PAUSED -> fields.copy(paused = value as Boolean)
                TrackerField.ERROR -> fields.copy(error = value as String?)
                TrackerField.AI_SUPPLIER -> fields.copy(aiSupplier = value as String?)
                TrackerField.AUTO_APPROVE -> fields.copy(autoApprove = (value as? String)?.equals("on", ignoreCase = true) ?: false)
                TrackerField.AI_MODEL -> fields.copy(aiModel = value as String?)
                TrackerField.AI_REASONING_EFFORT -> fields.copy(aiReasoningEffort = value as String?)
                TrackerField.STORY_PHASE -> fields.copy(storyPhase = value as String?)
                TrackerField.SUBTASK_PHASE -> fields.copy(subtaskPhase = value as String?)
                TrackerField.SUBTASK_TYPE -> fields.copy(subtaskType = value as String?)
                TrackerField.REPO -> fields.copy(repo = value as String?)
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
        private const val LOOPBACK_RESUME_INCREMENT = 5

        // Markers rond het afkeur-feedbackblok in de story-description (SF-192). Stabiel houden: de
        // reject-afhandeling vervangt het blok hierop bij een volgende afkeuring (niet stapelen).
        const val MANUAL_APPROVE_FEEDBACK_START = "<!-- manual-approve-feedback:start -->"
        const val MANUAL_APPROVE_FEEDBACK_END = "<!-- manual-approve-feedback:end -->"

        fun isDeveloperLoopbackCapError(error: String?): Boolean =
            error?.contains("Developer-loopback cap bereikt", ignoreCase = true) == true
    }
}
