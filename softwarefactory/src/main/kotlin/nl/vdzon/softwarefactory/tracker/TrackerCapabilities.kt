package nl.vdzon.softwarefactory.tracker

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.IssueType
import nl.vdzon.softwarefactory.core.ProcessedCommentMarker
import nl.vdzon.softwarefactory.core.SubtaskSpec
import nl.vdzon.softwarefactory.core.TrackerAttachment
import nl.vdzon.softwarefactory.core.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerCommentInstruction
import nl.vdzon.softwarefactory.core.TrackerCommentParser
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerProject
import nl.vdzon.softwarefactory.tracker.services.AgentCommentContext

interface IssueReader {
    fun ensureConfiguredProjects(): List<TrackerProject>
    fun findWorkIssues(maxResults: Int = 50, includeFinished: Boolean = false): List<TrackerIssue>
    fun findAiIssues(projectKey: String = "KAN", maxResults: Int = 50, includeFinished: Boolean = false): List<TrackerIssue>
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
        silent: Boolean = false,
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
    fun effectiveSilent(issue: TrackerIssue): Boolean {
        if (issue.fields.silent) return true
        if (issue.issueType != IssueType.SUBTASK) return false
        val parentKey = runCatching { parentStoryKey(issue.key) }.getOrNull() ?: return false
        return runCatching { getIssue(parentKey).fields.silent }.getOrDefault(false)
    }
}

interface ProcessedCommentsApi {
    fun isProcessed(storyKey: String, commentId: String, role: AgentRole): Boolean
    fun markProcessed(storyKey: String, commentId: String, role: AgentRole): ProcessedCommentMarker
}
