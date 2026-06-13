package nl.vdzon.softwarefactory.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * In-memory, stateful model van een mini-YouTrack. Houdt één project, de
 * verwachte custom-field-definities, issues (met custom fields, tags, comments,
 * reactions) en subtask-links bij. [FakeYouTrackServer] zet HTTP-requests van de
 * echte [nl.vdzon.softwarefactory.youtrack.clients.YouTrackClient] om in
 * mutaties/queries op deze state.
 *
 * De test mag de state ook direct manipuleren (story aanmaken, label zetten),
 * zodat daarvoor geen HTTP vanuit de test nodig is.
 */
class FakeYouTrackState(
    val projectId: String = "0-0",
    val projectKey: String = "SP",
    val projectName: String = "Sample project",
    val projectDescription: String = "factory.repo=git@github.com:robbertvdzon/sample-build-project.git",
) {
    val mapper: ObjectMapper = jacksonObjectMapper()

    data class FieldDef(
        val id: String,
        val name: String,
        val fieldTypeId: String,
        val projectFieldType: String,
        val values: MutableList<String> = mutableListOf(),
    )

    data class Comment(
        val id: String,
        var text: String,
        val authorLogin: String,
        val authorFullName: String,
        val created: Long,
        val reactions: MutableList<String> = mutableListOf(),
    )

    inner class Issue(
        val id: String,
        val key: String,
        var summary: String,
        var description: String?,
    ) {
        val customFields: MutableMap<String, JsonNode?> = LinkedHashMap()
        val tags: MutableList<String> = mutableListOf()
        val comments: MutableList<Comment> = mutableListOf()
    }

    /** Exact de fields die `YouTrackClient.factoryFieldSpecs` verwacht — geseed zodat startup-validatie slaagt. */
    val fields: List<FieldDef> = listOf(
        FieldDef("cf-repo", "Repo", "enum[*]", "EnumProjectCustomField", mutableListOf("sample")),
        FieldDef("cf-supplier", "AI-supplier", "enum[1]", "EnumProjectCustomField", mutableListOf("none", "mock", "claude", "openai", "copilot", "microsoft")),
        FieldDef("cf-autoapprove", "Auto-approve", "enum[1]", "EnumProjectCustomField", mutableListOf("off", "on")),
        FieldDef("cf-storyphase", "Story Phase", "enum[1]", "EnumProjectCustomField", STORY_PHASE_VALUES.toMutableList()),
        FieldDef("cf-subtaskphase", "Subtask Phase", "enum[1]", "EnumProjectCustomField", SUBTASK_PHASE_VALUES.toMutableList()),
        FieldDef("cf-subtasktype", "Subtask Type", "enum[1]", "EnumProjectCustomField", SUBTASK_TYPE_VALUES.toMutableList()),
        FieldDef("cf-model", "AI Model", "enum[1]", "EnumProjectCustomField", AI_MODEL_VALUES.toMutableList()),
        FieldDef("cf-effort", "AI Reasoning Effort", "enum[1]", "EnumProjectCustomField", mutableListOf("low", "medium", "high")),
        FieldDef("cf-loopbacks", "AI Max Developer Loopbacks", "integer", "SimpleProjectCustomField"),
        FieldDef("cf-budget", "AI Token Budget", "integer", "SimpleProjectCustomField"),
        FieldDef("cf-used", "AI Tokens Used", "integer", "SimpleProjectCustomField"),
        FieldDef("cf-started", "AgentStartedAt", "date and time", "SimpleProjectCustomField"),
        FieldDef("cf-paused", "Paused", "enum[1]", "EnumProjectCustomField", mutableListOf("false", "true")),
        FieldDef("cf-error", "Error", "text", "TextProjectCustomField"),
    )

    private val issues: MutableMap<String, Issue> = LinkedHashMap()

    /** child-key -> parent-key (Subtask-link). */
    private val parentOf: MutableMap<String, String> = LinkedHashMap()

    private var issueCounter = 0
    private var commentCounter = 0

    /** Wist alle issues/links/tellers — nodig omdat de state een gedeelde static over de test-JVM is. */
    @Synchronized
    fun reset() {
        issues.clear()
        parentOf.clear()
        issueCounter = 0
        commentCounter = 0
    }

    @Synchronized
    fun issue(key: String): Issue? = issues[key]

    @Synchronized
    fun allIssues(): List<Issue> = issues.values.toList()

    @Synchronized
    fun childrenOf(parentKey: String): List<Issue> =
        parentOf.filterValues { it == parentKey }.keys.mapNotNull { issues[it] }
            .sortedBy { it.key.substringAfterLast('-').toIntOrNull() ?: Int.MAX_VALUE }

    @Synchronized
    fun parentKeyOf(childKey: String): String? = parentOf[childKey]

    @Synchronized
    fun linkParent(parentKey: String, childKey: String) {
        parentOf[childKey] = parentKey
    }

    /** Maakt een issue aan; gebruikt door de test (direct) én door POST /api/issues. */
    @Synchronized
    fun createIssue(summary: String, description: String? = null, key: String? = null): Issue {
        val resolvedKey = key ?: "$projectKey-${++issueCounter}".also { ensureCounterPast(it) }
        if (key != null) ensureCounterPast(key)
        val issue = Issue(id = "2-${issues.size + 1}", key = resolvedKey, summary = summary, description = description)
        issues[resolvedKey] = issue
        return issue
    }

    private fun ensureCounterPast(key: String) {
        key.substringAfterLast('-').toIntOrNull()?.let { if (it > issueCounter) issueCounter = it }
    }

    @Synchronized
    fun addComment(issueKey: String, text: String, authorLogin: String = "robbert", authorFullName: String = "Robbert"): Comment {
        val issue = issues.getValue(issueKey)
        val comment = Comment(
            id = "c-${++commentCounter}",
            text = text,
            authorLogin = authorLogin,
            authorFullName = authorFullName,
            created = SEED_CREATED + commentCounter * 60_000L,
        )
        issue.comments += comment
        return comment
    }

    /** Zet een enum-achtig custom-field via een simpele naam->waarde-helper (test-gemak). */
    @Synchronized
    fun setEnumField(issueKey: String, fieldName: String, value: String) {
        issues.getValue(issueKey).customFields[fieldName] = mapper.createObjectNode().put("name", value)
    }

    @Synchronized
    fun setRawField(issueKey: String, fieldName: String, value: JsonNode?) {
        issues.getValue(issueKey).customFields[fieldName] = value
    }

    /** Zet een tekst-custom-field (YouTrack-vorm: {"text": ...}), bv. `Project`. */
    @Synchronized
    fun setTextField(issueKey: String, fieldName: String, value: String) {
        issues.getValue(issueKey).customFields[fieldName] = mapper.createObjectNode().put("text", value)
    }

    // ---- JSON-serialisatie (zoals YouTrack-REST het teruggeeft) ----

    @Synchronized
    fun projectsNode(): ArrayNode = mapper.createArrayNode().apply {
        add(
            mapper.createObjectNode()
                .put("id", projectId)
                .put("name", projectName)
                .put("shortName", projectKey)
                .put("description", projectDescription)
                .put("archived", false),
        )
    }

    @Synchronized
    fun globalFieldsNode(): ArrayNode = mapper.createArrayNode().apply {
        fields.forEach { f ->
            add(
                mapper.createObjectNode()
                    .put("id", f.id)
                    .put("name", f.name)
                    .set<ObjectNode>("fieldType", mapper.createObjectNode().put("id", f.fieldTypeId)),
            )
        }
    }

    @Synchronized
    fun projectFieldsNode(): ArrayNode = mapper.createArrayNode().apply {
        fields.forEach { f ->
            val bundleValues = mapper.createArrayNode()
            f.values.forEach { v -> bundleValues.add(mapper.createObjectNode().put("id", "value-$v").put("name", v)) }
            add(
                mapper.createObjectNode()
                    .put("id", "pf-${f.id}")
                    .put("\$type", f.projectFieldType)
                    .set<ObjectNode>(
                        "field",
                        mapper.createObjectNode()
                            .put("id", f.id)
                            .put("name", f.name)
                            .set<ObjectNode>("fieldType", mapper.createObjectNode().put("id", f.fieldTypeId)),
                    )
                    .set<ObjectNode>(
                        "bundle",
                        mapper.createObjectNode().put("id", "bundle-${f.id}").set<ObjectNode>("values", bundleValues),
                    ),
            )
        }
    }

    @Synchronized
    fun issueNode(issue: Issue): ObjectNode {
        val node = mapper.createObjectNode()
            .put("id", issue.id)
            .put("idReadable", issue.key)
            .put("summary", issue.summary)
        issue.description?.let { node.put("description", it) }
        node.set<ObjectNode>(
            "project",
            mapper.createObjectNode()
                .put("id", projectId)
                .put("name", projectName)
                .put("shortName", projectKey)
                .put("description", projectDescription),
        )

        val customFields = mapper.createArrayNode()
        issue.customFields.forEach { (name, value) ->
            val cf = mapper.createObjectNode().put("name", name)
            if (value == null) cf.putNull("value") else cf.set<JsonNode>("value", value)
            customFields.add(cf)
        }
        node.set<ObjectNode>("customFields", customFields)

        val tags = mapper.createArrayNode()
        issue.tags.forEach { tags.add(mapper.createObjectNode().put("name", it)) }
        node.set<ObjectNode>("tags", tags)

        node.set<ObjectNode>("comments", commentsNode(issue))
        node.set<ObjectNode>("links", linksNode(issue))
        return node
    }

    @Synchronized
    fun commentsNode(issue: Issue): ArrayNode = mapper.createArrayNode().apply {
        issue.comments.forEach { add(commentNode(it)) }
    }

    @Synchronized
    fun commentNode(comment: Comment): ObjectNode {
        val reactions = mapper.createArrayNode()
        comment.reactions.forEachIndexed { i, r ->
            reactions.add(
                mapper.createObjectNode()
                    .put("id", "${comment.id}-r$i")
                    .put("reaction", r)
                    .set<ObjectNode>("author", mapper.createObjectNode().put("login", "factory")),
            )
        }
        return mapper.createObjectNode()
            .put("id", comment.id)
            .put("text", comment.text)
            .set<ObjectNode>("author", mapper.createObjectNode().put("login", comment.authorLogin).put("fullName", comment.authorFullName))
            .apply { put("created", comment.created) }
            .set<ObjectNode>("reactions", reactions)
    }

    private fun linksNode(issue: Issue): ArrayNode {
        val links = mapper.createArrayNode()
        // OUTWARD: deze issue is parent van zijn children.
        childrenOf(issue.key).forEach { child ->
            links.add(linkNode("OUTWARD", child.key, child.summary))
        }
        // INWARD: deze issue is child van zijn parent.
        parentOf[issue.key]?.let { parentKey ->
            links.add(linkNode("INWARD", parentKey, issues[parentKey]?.summary ?: ""))
        }
        return links
    }

    private fun linkNode(direction: String, otherKey: String, otherSummary: String): ObjectNode {
        val issuesArr = mapper.createArrayNode()
        issuesArr.add(mapper.createObjectNode().put("idReadable", otherKey).put("summary", otherSummary))
        return mapper.createObjectNode()
            .put("direction", direction)
            .set<ObjectNode>("linkType", mapper.createObjectNode().put("name", "Subtask"))
            .also { it.set<ObjectNode>("issues", issuesArr) }
    }

    companion object {
        const val SEED_CREATED = 1_771_754_400_000L

        val STORY_PHASE_VALUES = listOf(
            "start",
            "refining", "refined-with-questions", "questions-answered", "refined",
            "refined-rejected", "refined-approved", "planning", "planned-with-questions",
            "planning-questions-answered", "planned", "planning-rejected", "planning-approved",
        )
        val SUBTASK_PHASE_VALUES = listOf(
            "start",
            "developing", "developed", "developed-with-questions",
            "development-questions-answered", "development-approved", "development-rejected",
            "reviewing", "reviewed", "reviewed-with-questions",
            "review-questions-answered", "review-approved", "review-rejected",
            "testing", "tested", "tested-with-questions",
            "test-questions-answered", "test-approved", "test-rejected",
            "summarizing", "summarized", "summary-with-questions",
            "summary-questions-answered", "summary-approved", "summary-rejected",
            "awaiting-human", "manual-action-done",
        )
        val SUBTASK_TYPE_VALUES = listOf("development", "review", "test", "manual", "summary")
        val AI_MODEL_VALUES = listOf(
            "claude-haiku-4-5", "claude-sonnet-4-6", "claude-opus-4-7", "claude-opus-4-8",
            "gpt-4.1", "claude-haiku-4.5", "claude-sonnet-4.5", "claude-opus-4.5", "dummy-ai-client",
        )
    }
}
