package nl.vdzon.softwarefactory.testsupport

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.SubtaskSpec
import nl.vdzon.softwarefactory.core.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerIssueFields
import nl.vdzon.softwarefactory.tracker.TrackerApi

/**
 * In-memory [TrackerApi]-fake met een vaste issue-lijst. Registreert alle veld-updates,
 * lane-transities, comments, tags, description-updates en aangemaakte subtaken zodat tests
 * daarop kunnen asserteren.
 */
class FakeTrackerApi(
    private val issues: List<TrackerIssue>,
    private val parentKey: String? = null,
    private val subtasks: List<TrackerIssue> = emptyList(),
) : TrackerApi {
    val updates: MutableMap<String, MutableList<TrackerFieldUpdate>> = mutableMapOf()
    val transitions: MutableList<Pair<String, String>> = mutableListOf()
    val postedComments: MutableList<Pair<String, String>> = mutableListOf()
    val addedTags: MutableList<Pair<String, String>> = mutableListOf()
    val removedTags: MutableList<Pair<String, String>> = mutableListOf()

    override fun findAiIssues(projectKey: String, maxResults: Int, includeFinished: Boolean): List<TrackerIssue> =
        issues

    override fun getIssue(issueKey: String): TrackerIssue =
        issues.first { it.key == issueKey }

    override fun parentStoryKey(subtaskKey: String): String? = parentKey

    override fun subtasksOf(parentKey: String): List<TrackerIssue> = subtasks

    val createdSubtasks: MutableList<SubtaskSpec> = mutableListOf()

    override fun createSubtask(
        parentKey: String,
        spec: SubtaskSpec,
        supplier: String?,
    ): TrackerIssue {
        createdSubtasks += spec
        return TrackerIssue(
            key = "$parentKey-sub${createdSubtasks.size}",
            summary = spec.title,
            description = spec.description,
            status = "Develop",
            fields = TrackerIssueFields(
                targetRepo = null,
                aiSupplier = supplier,
                aiPhase = null,
                aiLevel = null,
                aiTokenBudget = null,
                aiTokensUsed = null,
                agentStartedAt = null,
                paused = false,
                error = null,
                type = "Task",
                subtaskType = spec.type.trackerValue,
            ),
            comments = emptyList(),
        )
    }

    override fun addTag(issueKey: String, tag: String) {
        addedTags += issueKey to tag
    }

    override fun removeTag(issueKey: String, tag: String) {
        removedTags += issueKey to tag
    }

    val descriptionUpdates: MutableMap<String, String> = mutableMapOf()

    override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
        updates.getOrPut(issueKey) { mutableListOf() } += update
    }

    override fun updateIssueDescription(issueKey: String, description: String) {
        descriptionUpdates[issueKey] = description
    }

    override fun transitionIssue(issueKey: String, statusName: String) {
        transitions += issueKey to statusName
    }

    override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment =
        throw UnsupportedOperationException()

    override fun postComment(issueKey: String, message: String): TrackerComment {
        postedComments += issueKey to message
        return TrackerComment("posted-${postedComments.size}", null, "Factory", message, null)
    }

    override fun hasProcessedCommentMarker(commentId: String, role: AgentRole): Boolean =
        false

    override fun markCommentProcessed(commentId: String, role: AgentRole): Boolean =
        false

    fun lastUpdate(issueKey: String): TrackerFieldUpdate =
        updates.getValue(issueKey).last()
}
