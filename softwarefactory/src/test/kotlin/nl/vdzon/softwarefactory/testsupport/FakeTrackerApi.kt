package nl.vdzon.softwarefactory.testsupport

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.StoryPhase
import nl.vdzon.softwarefactory.core.contracts.SubtaskSpec
import nl.vdzon.softwarefactory.core.contracts.TrackerComment
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.contracts.TrackerIssueFields
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
    // Parent-story die wél via getIssue leesbaar is maar niet in de werk-lijst staat. Zonder deze
    // (of een parent in [issues]) gooit getIssue voor [parentKey] — precies de trackerstoring die
    // dispatch/deploy sinds SF-1560 laat skippen i.p.v. als "geen parent" te behandelen.
    private val parentIssue: TrackerIssue? = null,
) : TrackerApi {
    val updates: MutableMap<String, MutableList<TrackerFieldUpdate>> = mutableMapOf()
    val transitions: MutableList<Pair<String, String>> = mutableListOf()
    val postedComments: MutableList<Pair<String, String>> = mutableListOf()
    val addedTags: MutableList<Pair<String, String>> = mutableListOf()
    val removedTags: MutableList<Pair<String, String>> = mutableListOf()

    override fun findAiIssues(maxResults: Int, includeFinished: Boolean): List<TrackerIssue> =
        issues

    override fun getIssue(issueKey: String): TrackerIssue =
        (issues + listOfNotNull(parentIssue)).first { it.key == issueKey }

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

    /** SF-1959 — aangemaakte stories als (titel, hotfix-vlag), voor de aanmaakroute-tests. */
    val createdStories: MutableList<Pair<String, Boolean>> = mutableListOf()

    override fun createStory(
        projectKey: String,
        title: String,
        description: String?,
        repo: String?,
        aiSupplier: String?,
        aiModel: String?,
        startPhase: StoryPhase?,
        questionsAllowed: Boolean,
        approvalMode: String,
        notificationEvents: Set<nl.vdzon.softwarefactory.core.contracts.NotificationEvent>,
        hotfix: Boolean,
    ): TrackerIssue {
        createdStories += title to hotfix
        return TrackerIssue(
            key = "$projectKey-${createdStories.size}",
            summary = title,
            description = description,
            status = "",
            fields = TrackerIssueFields(
                targetRepo = null,
                repo = repo,
                aiSupplier = aiSupplier,
                aiPhase = null,
                aiLevel = null,
                aiTokenBudget = null,
                aiTokensUsed = null,
                agentStartedAt = null,
                paused = false,
                questionsAllowed = questionsAllowed,
                approvalMode = approvalMode,
                notificationEvents = notificationEvents,
                hotfix = hotfix,
                error = null,
                type = "User Story",
                aiModel = aiModel,
                storyPhase = startPhase?.trackerValue,
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
    val descriptionSummaryUpdates: MutableMap<String, String> = mutableMapOf()
    val shortDescriptionSummaryUpdates: MutableMap<String, String> = mutableMapOf()

    override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
        updates.getOrPut(issueKey) { mutableListOf() } += update
    }

    override fun updateIssueDescription(issueKey: String, description: String) {
        descriptionUpdates[issueKey] = description
    }

    override fun updateIssueDescriptionSummary(issueKey: String, descriptionSummary: String) {
        descriptionSummaryUpdates[issueKey] = descriptionSummary
    }

    override fun updateIssueShortDescriptionSummary(issueKey: String, shortDescriptionSummary: String) {
        shortDescriptionSummaryUpdates[issueKey] = shortDescriptionSummary
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
