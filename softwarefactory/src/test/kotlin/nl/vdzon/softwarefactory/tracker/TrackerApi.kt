package nl.vdzon.softwarefactory.tracker

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.IssueType
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.contracts.TrackerComment
import nl.vdzon.softwarefactory.core.contracts.TrackerCommentInstruction
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.contracts.TrackerProject
import nl.vdzon.softwarefactory.core.contracts.TrackerAttachment
import nl.vdzon.softwarefactory.core.contracts.SubtaskSpec
import nl.vdzon.softwarefactory.core.contracts.ProcessedCommentMarker
import nl.vdzon.softwarefactory.tracker.services.AgentCommentContext
import nl.vdzon.softwarefactory.core.contracts.TrackerCommentParser

/**
 * Public API of the tracker module.
 *
 * The tracker module owns issue tracker communication: issue lookup, field
 * updates, comments, transitions, project bootstrap and comment processing
 * markers. Backed by [nl.vdzon.softwarefactory.tracker.clients.PostgresTrackerClient]
 * (the factory's own Postgres tables).
 */
@Deprecated("Inject the smallest tracker capability required by the consumer")
interface TrackerApi : TrackerCapabilities {
    override fun isAgentComment(body: String): Boolean =
        TrackerCommentParser.isAgentComment(body)

    override fun agentRole(body: String): AgentRole? =
        TrackerCommentParser.agentRole(body)

    override fun parseInstructions(body: String): List<TrackerCommentInstruction> =
        TrackerCommentParser.parseInstructions(body)

    override fun taskComments(
        issue: TrackerIssue,
        role: AgentRole,
        isProcessed: (TrackerComment, AgentRole) -> Boolean,
    ): List<TrackerComment> =
        AgentCommentContext.taskComments(issue, role, isProcessed)

    override fun processableComments(
        issue: TrackerIssue,
        role: AgentRole,
        isProcessed: (TrackerComment, AgentRole) -> Boolean,
    ): List<TrackerComment> =
        AgentCommentContext.processableComments(issue, role, isProcessed)

    override fun ensureConfiguredProjects(): List<TrackerProject> = emptyList()

    /**
     * [includeFinished] onderscheidt twee heel verschillende consumenten: de orchestrator-poller
     * wil alleen actief werk zien (default false — afgeronde issues blijven buiten beeld, en dus
     * ook buiten de poll-logging), terwijl het dashboard-stories-overzicht juist ALLE stories wil
     * tonen (incl. de "Klaar"-tab) en `true` doorgeeft.
     */
    override fun findWorkIssues(maxResults: Int, includeFinished: Boolean): List<TrackerIssue> =
        findAiIssues(maxResults = maxResults, includeFinished = includeFinished)

    override fun findAiIssues(projectKey: String, maxResults: Int, includeFinished: Boolean): List<TrackerIssue> =
        emptyList()

    override fun getIssue(issueKey: String): TrackerIssue

    /**
     * Maakt een subtask aan onder [parentKey]: nieuw issue met `Type = Task`,
     * `Subtask Type` + optioneel model/effort, gekoppeld via de Subtask-link.
     * [supplier] zet de `AI-supplier` (story-default, README §7), zodat de subtask
     * door de poller opgepakt wordt. Zet bewust GEEN work-tag (inert tot `ai-development`).
     */
    override fun createSubtask(parentKey: String, spec: SubtaskSpec, supplier: String?): TrackerIssue {
        throw UnsupportedOperationException("Creating subtasks is not supported by this TrackerApi.")
    }

    /**
     * Maakt een nieuwe STORY (Type = User Story) aan in [projectKey]. Zet optioneel het `Repo`-veld
     * (projectnaam uit projects.yaml of repo-URL), de `AI-supplier`, en — als [start] — meteen de
     * Story Phase op `start` zodat de orchestrator 'm oppakt.
     */
    override fun createStory(
        projectKey: String,
        title: String,
        description: String?,
        repo: String?,
        aiSupplier: String?,
        aiModel: String?,
        start: Boolean,
        silent: Boolean,
    ): TrackerIssue {
        throw UnsupportedOperationException("Creating stories is not supported by this TrackerApi.")
    }

    /** Summaries van bestaande subtaken (Subtask-children) van [parentKey], voor idempotente creatie. */
    override fun existingSubtaskTitles(parentKey: String): Set<String> = emptySet()

    /** De parent-story van een subtask (Subtask `INWARD`-link), of null. */
    override fun parentStoryKey(subtaskKey: String): String? = null

    /** De subtaken (Subtask-children) van [parentKey] in aanmaakvolgorde. */
    override fun subtasksOf(parentKey: String): List<TrackerIssue> = emptyList()

    /**
     * SF-335 — "effectief silent": het eigen `Silent`-veld OF — voor een subtaak — dat van de
     * parent-story (best-effort parent-lookup; faalt die, dan false). Gedeelde helper zodat
     * coördinatoren, notificaties en dashboard dezelfde beslissing nemen, identiek aan de manier
     * waarop auto-approve via de parent wordt bepaald.
     */
    override fun effectiveSilent(issue: TrackerIssue): Boolean {
        if (issue.fields.silent) return true
        if (issue.issueType != IssueType.SUBTASK) return false
        val parentKey = runCatching { parentStoryKey(issue.key) }.getOrNull() ?: return false
        return runCatching { getIssue(parentKey).fields.silent }.getOrDefault(false)
    }

    /** Voeg een tag toe aan een issue (fase 4 — keten). */
    fun addTag(issueKey: String, tag: String) {}

    /** Verwijder een tag van een issue (fase 4 — keten). */
    fun removeTag(issueKey: String, tag: String) {}

    override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate)

    override fun updateIssueSummary(issueKey: String, summary: String) {
        throw UnsupportedOperationException("Updating issue tracker summary is not supported by this TrackerApi.")
    }

    override fun updateIssueDescription(issueKey: String, description: String) {
        throw UnsupportedOperationException("Updating issue tracker description is not supported by this TrackerApi.")
    }

    override fun transitionIssue(issueKey: String, statusName: String)

    override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment

    override fun postComment(issueKey: String, message: String): TrackerComment {
        throw UnsupportedOperationException("Posting plain issue tracker comments is not supported by this TrackerApi.")
    }

    override fun listIssueAttachments(issueKey: String): List<TrackerAttachment> = emptyList()

    /**
     * Downloadt de ruwe bytes van [attachment] (via diens `url`). Soft-fail: geeft `null` terug bij een
     * ontbrekende URL of een mislukte download i.p.v. te gooien, zodat callers (zoals de Telegram-melding)
     * netjes kunnen degraderen.
     */
    override fun downloadAttachmentBytes(attachment: TrackerAttachment): ByteArray? = null

    override fun uploadIssueAttachment(issueKey: String, name: String, mimeType: String, bytes: ByteArray): TrackerAttachment {
        throw UnsupportedOperationException("Uploading issue tracker attachments is not supported by this TrackerApi.")
    }

    override fun deleteIssueAttachment(issueKey: String, attachmentId: String) {
        throw UnsupportedOperationException("Deleting issue tracker attachments is not supported by this TrackerApi.")
    }

    override fun hasProcessedCommentMarker(issueKey: String, commentId: String, role: AgentRole): Boolean =
        hasProcessedCommentMarker(commentId, role)

    fun hasProcessedCommentMarker(commentId: String, role: AgentRole): Boolean = false

    override fun markCommentProcessed(issueKey: String, commentId: String, role: AgentRole): Boolean =
        markCommentProcessed(commentId, role)

    fun markCommentProcessed(commentId: String, role: AgentRole): Boolean = false

    override fun deleteAgentComments(issueKey: String): Int {
        throw UnsupportedOperationException("Deleting issue tracker agent comments is not supported by this TrackerApi.")
    }

    /** Verwijdert een issue (bv. een subtask) volledig uit de tracker. Onomkeerbaar. */
    override fun deleteIssue(issueKey: String) {
        throw UnsupportedOperationException("Deleting issue tracker issues is not supported by this TrackerApi.")
    }

    companion object {
        fun isAgentComment(body: String): Boolean =
            TrackerCommentParser.isAgentComment(body)

        fun agentRole(body: String): AgentRole? =
            TrackerCommentParser.agentRole(body)

        fun parseCommentInstructions(body: String): List<TrackerCommentInstruction> =
            TrackerCommentParser.parseInstructions(body)
    }
}
