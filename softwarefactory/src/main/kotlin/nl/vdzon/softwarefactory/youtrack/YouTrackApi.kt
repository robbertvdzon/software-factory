package nl.vdzon.softwarefactory.youtrack

import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.youtrack.clients.YouTrackClient
import nl.vdzon.softwarefactory.youtrack.services.AgentCommentContext
import nl.vdzon.softwarefactory.youtrack.parsers.TrackerCommentParser

/**
 * Public API of the YouTrack module.
 *
 * The YouTrack module owns issue tracker communication: issue lookup, field
 * updates, comments, transitions, project bootstrap and comment processing
 * markers.
 */
interface YouTrackApi {
    fun isAgentComment(body: String): Boolean =
        TrackerCommentParser.isAgentComment(body)

    fun agentRole(body: String): AgentRole? =
        TrackerCommentParser.agentRole(body)

    fun parseInstructions(body: String): List<TrackerCommentInstruction> =
        TrackerCommentParser.parseInstructions(body)

    fun taskComments(
        issue: TrackerIssue,
        role: AgentRole,
        isProcessed: (TrackerComment, AgentRole) -> Boolean,
    ): List<TrackerComment> =
        AgentCommentContext.taskComments(issue, role, isProcessed)

    fun processableComments(
        issue: TrackerIssue,
        role: AgentRole,
        isProcessed: (TrackerComment, AgentRole) -> Boolean,
    ): List<TrackerComment> =
        AgentCommentContext.processableComments(issue, role, isProcessed)

    fun ensureConfiguredProjects(): List<TrackerProject> = emptyList()

    fun findWorkIssues(maxResults: Int = 50): List<TrackerIssue> =
        findAiIssues(maxResults = maxResults)

    fun findAiIssues(projectKey: String = "KAN", maxResults: Int = 50): List<TrackerIssue> = emptyList()

    fun getIssue(issueKey: String): TrackerIssue

    /**
     * Maakt een subtask aan onder [parentKey]: nieuw issue met `Type = Task`,
     * `Subtask Type` + optioneel model/effort, gekoppeld via de Subtask-link.
     * Zet bewust GEEN work-tag (de subtask is inert tot 'ie `ai-development` krijgt).
     */
    fun createSubtask(parentKey: String, spec: SubtaskSpec): TrackerIssue {
        throw UnsupportedOperationException("Creating subtasks is not supported by this YouTrackApi.")
    }

    /** Summaries van bestaande subtaken (Subtask-children) van [parentKey], voor idempotente creatie. */
    fun existingSubtaskTitles(parentKey: String): Set<String> = emptySet()

    fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate)

    fun updateIssueSummary(issueKey: String, summary: String) {
        throw UnsupportedOperationException("Updating issue tracker summary is not supported by this YouTrackApi.")
    }

    fun transitionIssue(issueKey: String, statusName: String)

    fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment

    fun postComment(issueKey: String, message: String): TrackerComment {
        throw UnsupportedOperationException("Posting plain issue tracker comments is not supported by this YouTrackApi.")
    }

    fun listIssueAttachments(issueKey: String): List<TrackerAttachment> = emptyList()

    fun uploadIssueAttachment(issueKey: String, name: String, mimeType: String, bytes: ByteArray): TrackerAttachment {
        throw UnsupportedOperationException("Uploading issue tracker attachments is not supported by this YouTrackApi.")
    }

    fun deleteIssueAttachment(issueKey: String, attachmentId: String) {
        throw UnsupportedOperationException("Deleting issue tracker attachments is not supported by this YouTrackApi.")
    }

    fun hasProcessedCommentMarker(issueKey: String, commentId: String, role: AgentRole): Boolean =
        hasProcessedCommentMarker(commentId, role)

    fun hasProcessedCommentMarker(commentId: String, role: AgentRole): Boolean = false

    fun markCommentProcessed(issueKey: String, commentId: String, role: AgentRole): Boolean =
        markCommentProcessed(commentId, role)

    fun markCommentProcessed(commentId: String, role: AgentRole): Boolean = false

    fun deleteAgentComments(issueKey: String): Int {
        throw UnsupportedOperationException("Deleting issue tracker agent comments is not supported by this YouTrackApi.")
    }

    companion object {
        fun isAgentComment(body: String): Boolean =
            TrackerCommentParser.isAgentComment(body)

        fun agentRole(body: String): AgentRole? =
            TrackerCommentParser.agentRole(body)

        fun parseCommentInstructions(body: String): List<TrackerCommentInstruction> =
            TrackerCommentParser.parseInstructions(body)

        fun default(): YouTrackApi = YouTrackClient(ConfigApi.default().loadSecrets())
    }
}

data class TrackerProject(
    val id: String,
    val key: String,
    val name: String,
    val targetRepo: String?,
)

data class TrackerAttachment(
    val id: String,
    val name: String,
    val url: String?,
    val mimeType: String?,
    val size: Long?,
    val created: Long?,
)

interface ProcessedCommentsApi {
    fun isProcessed(storyKey: String, commentId: String, role: AgentRole): Boolean

    fun markProcessed(storyKey: String, commentId: String, role: AgentRole): ProcessedCommentMarker
}

enum class ProcessedCommentMarker {
    TRACKER_COMMENT_MARKER,
    DATABASE_FALLBACK,
}
