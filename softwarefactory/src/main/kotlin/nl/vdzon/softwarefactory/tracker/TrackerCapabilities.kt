package nl.vdzon.softwarefactory.tracker

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.IssueType
import nl.vdzon.softwarefactory.core.contracts.NotifyMode
import nl.vdzon.softwarefactory.core.contracts.ProcessedCommentMarker
import nl.vdzon.softwarefactory.core.contracts.SubtaskSpec
import nl.vdzon.softwarefactory.core.contracts.TrackerAttachment
import nl.vdzon.softwarefactory.core.contracts.TrackerComment
import nl.vdzon.softwarefactory.core.contracts.TrackerCommentInstruction
import nl.vdzon.softwarefactory.core.contracts.TrackerCommentParser
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.contracts.TrackerProject
import nl.vdzon.softwarefactory.tracker.services.AgentCommentContext

interface IssueReader {
    fun ensureConfiguredProjects(): List<TrackerProject>
    fun findWorkIssues(maxResults: Int = 50, includeFinished: Boolean = false): List<TrackerIssue>
    fun findAiIssues(maxResults: Int = 50, includeFinished: Boolean = false): List<TrackerIssue>
    fun getIssue(issueKey: String): TrackerIssue
    fun existingSubtaskTitles(parentKey: String): Set<String>
    fun parentStoryKey(subtaskKey: String): String?
    fun subtasksOf(parentKey: String): List<TrackerIssue>
}

interface IssueLifecyclePort {
    fun createSubtask(parentKey: String, spec: SubtaskSpec, supplier: String? = null): TrackerIssue
    fun createStory(
        projectKey: String,
        title: String,
        description: String? = null,
        repo: String? = null,
        aiSupplier: String? = null,
        aiModel: String? = null,
        start: Boolean = false,
        questionsAllowed: Boolean = true,
    ): TrackerIssue
    fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate)
    fun updateIssueSummary(issueKey: String, summary: String)
    fun updateIssueDescription(issueKey: String, description: String)
    fun transitionIssue(issueKey: String, statusName: String)
    fun deleteIssue(issueKey: String)
}

interface CommentPort {
    fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment
    fun postComment(issueKey: String, message: String): TrackerComment
    fun deleteAgentComments(issueKey: String): Int
}

interface AttachmentPort {
    fun listIssueAttachments(issueKey: String): List<TrackerAttachment>
    fun downloadAttachmentBytes(attachment: TrackerAttachment): ByteArray?
    fun uploadIssueAttachment(issueKey: String, name: String, mimeType: String, bytes: ByteArray): TrackerAttachment
    fun deleteIssueAttachment(issueKey: String, attachmentId: String)
}

interface ProcessedCommentPort {
    fun hasProcessedCommentMarker(issueKey: String, commentId: String, role: AgentRole): Boolean
    fun markCommentProcessed(issueKey: String, commentId: String, role: AgentRole): Boolean
}

/** Alleen de composition root implementeert deze bundel; consumers injecteren waar mogelijk een smalle capability. */
interface TrackerCapabilities : IssueReader, IssueLifecyclePort, CommentPort, AttachmentPort, ProcessedCommentPort {
    fun isAgentComment(body: String): Boolean = TrackerCommentParser.isAgentComment(body)
    fun agentRole(body: String): AgentRole? = TrackerCommentParser.agentRole(body)
    fun parseInstructions(body: String): List<TrackerCommentInstruction> = TrackerCommentParser.parseInstructions(body)
    fun taskComments(issue: TrackerIssue, role: AgentRole, isProcessed: (TrackerComment, AgentRole) -> Boolean): List<TrackerComment> =
        AgentCommentContext.taskComments(issue, role, isProcessed)
    fun processableComments(issue: TrackerIssue, role: AgentRole, isProcessed: (TrackerComment, AgentRole) -> Boolean): List<TrackerComment> =
        AgentCommentContext.processableComments(issue, role, isProcessed)
    /**
     * SF-1261 — as 1 (Vragen toestaan): het eigen veld OF — voor een subtaak — dat van de
     * parent-story (best-effort parent-lookup; faalt die, dan het eigen veld). Vervangt
     * `effectiveSilent`: `true` = wachten op de gebruiker via Telegram (bestaand gedrag),
     * `false` = direct een `[CLARIFICATION]`-error i.p.v. wachten.
     */
    fun effectiveQuestionsAllowed(issue: TrackerIssue): Boolean {
        if (issue.issueType != IssueType.SUBTASK) return issue.fields.questionsAllowed
        val parentKey = runCatching { parentStoryKey(issue.key) }.getOrNull() ?: return issue.fields.questionsAllowed
        return runCatching { getIssue(parentKey).fields.questionsAllowed }.getOrDefault(issue.fields.questionsAllowed)
    }

    /**
     * SF-1261 — as 3 (Meldingen): de story leidt, subtaken erven via parent-lookup (best-effort;
     * faalt die, dan het eigen veld).
     */
    fun effectiveNotifyMode(issue: TrackerIssue): NotifyMode {
        val raw = if (issue.issueType != IssueType.SUBTASK) {
            issue.fields.notifyMode
        } else {
            val parentKey = runCatching { parentStoryKey(issue.key) }.getOrNull()
            parentKey?.let { runCatching { getIssue(it).fields.notifyMode }.getOrNull() } ?: issue.fields.notifyMode
        }
        return NotifyMode.fromTracker(raw)
    }
}

interface ProcessedCommentsApi {
    fun isProcessed(storyKey: String, commentId: String, role: AgentRole): Boolean
    fun markProcessed(storyKey: String, commentId: String, role: AgentRole): ProcessedCommentMarker
}
