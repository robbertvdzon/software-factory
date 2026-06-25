package nl.vdzon.softwarefactory.youtrack

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerCommentInstruction
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerProject
import nl.vdzon.softwarefactory.core.TrackerAttachment
import nl.vdzon.softwarefactory.core.SubtaskSpec
import nl.vdzon.softwarefactory.core.ProcessedCommentMarker
import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.youtrack.clients.YouTrackClient
import nl.vdzon.softwarefactory.youtrack.services.AgentCommentContext
import nl.vdzon.softwarefactory.core.TrackerCommentParser

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
     * [supplier] zet de `AI-supplier` (story-default, README §7), zodat de subtask
     * door de poller opgepakt wordt. Zet bewust GEEN work-tag (inert tot `ai-development`).
     */
    fun createSubtask(parentKey: String, spec: SubtaskSpec, supplier: String? = null): TrackerIssue {
        throw UnsupportedOperationException("Creating subtasks is not supported by this YouTrackApi.")
    }

    /**
     * Maakt een nieuwe STORY (Type = User Story) aan in [projectKey]. Zet optioneel het `Repo`-veld
     * (projectnaam uit projects.yaml of repo-URL), de `AI-supplier`, en — als [start] — meteen de
     * Story Phase op `start` zodat de orchestrator 'm oppakt.
     */
    fun createStory(
        projectKey: String,
        title: String,
        description: String? = null,
        repo: String? = null,
        aiSupplier: String? = null,
        aiModel: String? = null,
        start: Boolean = false,
    ): TrackerIssue {
        throw UnsupportedOperationException("Creating stories is not supported by this YouTrackApi.")
    }

    /** Summaries van bestaande subtaken (Subtask-children) van [parentKey], voor idempotente creatie. */
    fun existingSubtaskTitles(parentKey: String): Set<String> = emptySet()

    /** De parent-story van een subtask (Subtask `INWARD`-link), of null. */
    fun parentStoryKey(subtaskKey: String): String? = null

    /** De subtaken (Subtask-children) van [parentKey] in aanmaakvolgorde. */
    fun subtasksOf(parentKey: String): List<TrackerIssue> = emptyList()

    /** Voeg een tag toe aan een issue (fase 4 — keten). */
    fun addTag(issueKey: String, tag: String) {}

    /** Verwijder een tag van een issue (fase 4 — keten). */
    fun removeTag(issueKey: String, tag: String) {}

    fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate)

    fun updateIssueSummary(issueKey: String, summary: String) {
        throw UnsupportedOperationException("Updating issue tracker summary is not supported by this YouTrackApi.")
    }

    fun updateIssueDescription(issueKey: String, description: String) {
        throw UnsupportedOperationException("Updating issue tracker description is not supported by this YouTrackApi.")
    }

    fun transitionIssue(issueKey: String, statusName: String)

    fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment

    fun postComment(issueKey: String, message: String): TrackerComment {
        throw UnsupportedOperationException("Posting plain issue tracker comments is not supported by this YouTrackApi.")
    }

    fun listIssueAttachments(issueKey: String): List<TrackerAttachment> = emptyList()

    /**
     * Downloadt de ruwe bytes van [attachment] (via diens `url`). Soft-fail: geeft `null` terug bij een
     * ontbrekende URL of een mislukte download i.p.v. te gooien, zodat callers (zoals de Telegram-melding)
     * netjes kunnen degraderen.
     */
    fun downloadAttachmentBytes(attachment: TrackerAttachment): ByteArray? = null

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

    /** Verwijdert een issue (bv. een subtask) volledig uit de tracker. Onomkeerbaar. */
    fun deleteIssue(issueKey: String) {
        throw UnsupportedOperationException("Deleting issue tracker issues is not supported by this YouTrackApi.")
    }

    companion object {
        fun isAgentComment(body: String): Boolean =
            TrackerCommentParser.isAgentComment(body)

        fun agentRole(body: String): AgentRole? =
            TrackerCommentParser.agentRole(body)

        fun parseCommentInstructions(body: String): List<TrackerCommentInstruction> =
            TrackerCommentParser.parseInstructions(body)

        fun default(): YouTrackApi {
            val config = ConfigApi.default()
            return YouTrackClient(config.loadSecrets(), config.loadProjectRepoResolver())
        }
    }
}

interface ProcessedCommentsApi {
    fun isProcessed(storyKey: String, commentId: String, role: AgentRole): Boolean

    fun markProcessed(storyKey: String, commentId: String, role: AgentRole): ProcessedCommentMarker
}
