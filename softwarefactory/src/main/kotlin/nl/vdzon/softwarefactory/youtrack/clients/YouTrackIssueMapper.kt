package nl.vdzon.softwarefactory.youtrack.clients

import com.fasterxml.jackson.databind.JsonNode
import nl.vdzon.softwarefactory.core.TrackerAttachment
import nl.vdzon.softwarefactory.core.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerIssueFields
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Stateless mapping tussen YouTrack-JSON en de tracker-domeintypen: read-mapping
 * (issue/comment/attachment + custom-field-extractie) en write-mapping
 * (customFields-payloads voor updates). De bijbehorende `fields`-parameters
 * ([issueFields] c.s.) staan hier ook, zodat query en mapping in de pas lopen.
 */
internal object YouTrackIssueMapper {
    const val commentFields = "id,text,author(login,fullName),created,reactions(id,reaction,author(login))"
    const val attachmentFields = "id,name,url,mimeType,size,created"
    // "links(...)" levert de parent-story van een subtaak direct mee (zie [parentKeyOf]), zodat
    // `myActions()` niet meer per subtaak een aparte `parentStoryKey`-call hoeft te doen (was een
    // N+1: één live YouTrack-call per wachtende subtaak).
    const val issueFields =
        "id,idReadable,summary,description,project(id,name,shortName,description)," +
            "customFields(name,value(id,name,presentation,text,localizedName))," +
            "tags(name)," +
            "comments($commentFields)," +
            "links(direction,linkType(name),issues(idReadable))"

    fun mapIssue(issue: JsonNode): TrackerIssue {
        val fields = issue.path("customFields")
        return TrackerIssue(
            id = issue.path("id").asText(),
            key = issue.path("idReadable").asText(),
            summary = issue.path("summary").asText(""),
            description = issue.path("description").asText(null)?.takeIf { it.isNotBlank() },
            projectKey = issue.path("project").path("shortName").asText(issue.path("idReadable").asText().substringBefore("-")),
            // De board-lane staat op het `State`-veld (Open / In Progress / To Verify / Done) — dat is
            // wat de factory zelf bijwerkt via transitionIssue, dus de bron voor "klaar/bezig/todo".
            // Legacy `Stage` en `AI Phase` blijven fallback voor projecten zonder State-veld.
            status = customFieldText(fields, "State")
                ?.takeIf { it.isNotBlank() }
                ?: customFieldText(fields, "Stage")?.takeIf { it.isNotBlank() }
                ?: customFieldText(fields, TrackerField.AI_PHASE.displayName).orEmpty(),
            fields = TrackerIssueFields(
                // De repo komt niet meer van het project, maar wordt door de orchestrator afgeleid
                // uit het `Repo`-veld via ProjectRepoResolver (subtaken: van hun parent-story).
                targetRepo = null,
                // 'Repo' is multi-enum; de engine gebruikt voorlopig de eerste gekozen waarde.
                repo = customFieldEnumNames(fields, TrackerField.REPO.displayName).firstOrNull(),
                aiSupplier = customFieldText(fields, TrackerField.AI_SUPPLIER.displayName),
                autoApprove = customFieldText(fields, TrackerField.AUTO_APPROVE.displayName)
                    ?.let { it.equals("on", ignoreCase = true) || it.equals("true", ignoreCase = true) }
                    ?: false,
                aiPhase = customFieldText(fields, TrackerField.AI_PHASE.displayName),
                aiLevel = customFieldLong(fields, TrackerField.AI_LEVEL.displayName)?.toInt(),
                aiMaxDeveloperLoopbacks = customFieldLong(fields, TrackerField.AI_MAX_DEVELOPER_LOOPBACKS.displayName)?.toInt(),
                aiTokenBudget = customFieldLong(fields, TrackerField.AI_TOKEN_BUDGET.displayName),
                aiTokensUsed = customFieldLong(fields, TrackerField.AI_TOKENS_USED.displayName),
                agentStartedAt = customFieldDateTime(fields, TrackerField.AGENT_STARTED_AT.displayName),
                paused = customFieldText(fields, TrackerField.PAUSED.displayName).equals("true", ignoreCase = true),
                // SF-335 — enum-boolean analoog aan Paused: "true"/"on" → true, anders false.
                silent = customFieldText(fields, TrackerField.SILENT.displayName)
                    ?.let { it.equals("true", ignoreCase = true) || it.equals("on", ignoreCase = true) }
                    ?: false,
                error = customFieldText(fields, TrackerField.ERROR.displayName),
                type = customFieldText(fields, "Type"),
                subtaskType = customFieldText(fields, TrackerField.SUBTASK_TYPE.displayName),
                aiModel = customFieldText(fields, TrackerField.AI_MODEL.displayName),
                aiReasoningEffort = customFieldText(fields, TrackerField.AI_REASONING_EFFORT.displayName),
                storyPhase = customFieldText(fields, TrackerField.STORY_PHASE.displayName),
                subtaskPhase = customFieldText(fields, TrackerField.SUBTASK_PHASE.displayName),
            ),
            comments = issue.path("comments").map { mapComment(it) },
            tags = issue.path("tags").mapNotNull { it.path("name").asText("").takeIf { n -> n.isNotBlank() } },
            parentKey = parentKeyOf(issue),
        )
    }

    /** Owner-story van een subtaak, uit de inward "Subtask"-link (zie [YouTrackApi.parentStoryKey]). */
    private fun parentKeyOf(issue: JsonNode): String? =
        issue.path("links")
            .filter {
                it.path("linkType").path("name").asText() == "Subtask" &&
                    it.path("direction").asText() == "INWARD"
            }
            .flatMap { it.path("issues") }
            .firstNotNullOfOrNull { it.path("idReadable").asText().takeIf { k -> k.isNotBlank() } }

    fun mapComment(comment: JsonNode): TrackerComment =
        TrackerComment(
            id = comment.path("id").asText(),
            authorAccountId = comment.path("author").path("login").asText().takeIf { it.isNotBlank() },
            authorDisplayName = comment.path("author").path("fullName").asText().takeIf { it.isNotBlank() },
            body = comment.path("text").asText(""),
            created = comment.path("created").takeIf { it.isNumber }?.asLong()?.toOffsetDateTime(),
        )

    fun mapAttachment(attachment: JsonNode): TrackerAttachment =
        TrackerAttachment(
            id = attachment.path("id").asText(),
            name = attachment.path("name").asText(""),
            url = attachment.path("url").asText(null)?.takeIf { it.isNotBlank() },
            mimeType = attachment.path("mimeType").asText(null)?.takeIf { it.isNotBlank() },
            size = attachment.path("size").takeIf { it.isNumber }?.asLong(),
            created = attachment.path("created").takeIf { it.isNumber }?.asLong(),
        )

    fun fieldUpdate(field: TrackerField, value: Any?): Map<String, Any?> =
        when (field) {
            TrackerField.AI_SUPPLIER,
            TrackerField.AUTO_APPROVE,
            TrackerField.AI_PHASE,
            TrackerField.PAUSED,
            TrackerField.SILENT,
            TrackerField.AI_MODEL,
            TrackerField.AI_REASONING_EFFORT,
            TrackerField.STORY_PHASE,
            TrackerField.SUBTASK_PHASE,
            TrackerField.SUBTASK_TYPE,
            -> mapOf(
                "name" to field.displayName,
                "\$type" to "SingleEnumIssueCustomField",
                "value" to value?.let { mapOf("name" to it.toString()) },
            )
            TrackerField.AI_LEVEL,
            TrackerField.AI_MAX_DEVELOPER_LOOPBACKS,
            TrackerField.AI_TOKEN_BUDGET,
            TrackerField.AI_TOKENS_USED,
            -> mapOf(
                "name" to field.displayName,
                "\$type" to "SimpleIssueCustomField",
                "value" to value,
            )
            TrackerField.AGENT_STARTED_AT -> mapOf(
                "name" to field.displayName,
                "\$type" to "SimpleIssueCustomField",
                "value" to (value as? OffsetDateTime)?.toInstant()?.toEpochMilli(),
            )
            TrackerField.ERROR -> mapOf(
                "name" to field.displayName,
                "\$type" to "TextIssueCustomField",
                "value" to value?.let {
                    mapOf(
                        "\$type" to "TextFieldValue",
                        "text" to it.toString(),
                    )
                },
            )
            // 'Repo' is multi-enum; wordt normaliter door de gebruiker gezet, niet door de factory.
            TrackerField.REPO -> mapOf(
                "name" to field.displayName,
                "\$type" to "MultiEnumIssueCustomField",
                "value" to when (value) {
                    null -> emptyList<Map<String, Any?>>()
                    is Collection<*> -> value.map { mapOf("name" to it.toString()) }
                    else -> listOf(mapOf("name" to value.toString()))
                },
            )
        }

    fun enumFieldValue(name: String, value: String): Map<String, Any?> =
        mapOf(
            "name" to name,
            "\$type" to "SingleEnumIssueCustomField",
            "value" to mapOf("name" to value),
        )

    private fun customFieldText(fields: JsonNode, name: String): String? {
        val value = fields.firstOrNull { it.path("name").asText() == name }?.path("value") ?: return null
        if (value.isMissingNode || value.isNull) {
            return null
        }
        return when {
            value.isTextual -> value.asText()
            value.isNumber -> value.asText()
            value.isBoolean -> value.asBoolean().toString()
            value.path("name").isTextual -> value.path("name").asText()
            value.path("presentation").isTextual -> value.path("presentation").asText()
            value.path("text").isTextual -> value.path("text").asText()
            value.path("localizedName").isTextual -> value.path("localizedName").asText()
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    /** Namen van een (multi-)enum-veld: array van {name} of een enkel {name}-object. */
    private fun customFieldEnumNames(fields: JsonNode, name: String): List<String> {
        val value = fields.firstOrNull { it.path("name").asText() == name }?.path("value") ?: return emptyList()
        return when {
            value.isArray -> value.mapNotNull { it.path("name").asText("").takeIf { n -> n.isNotBlank() } }
            value.path("name").isTextual -> listOf(value.path("name").asText())
            else -> emptyList()
        }
    }

    private fun customFieldLong(fields: JsonNode, name: String): Long? =
        fields.firstOrNull { it.path("name").asText() == name }?.path("value")?.let { value ->
            when {
                value.isNumber -> value.asLong()
                value.isTextual -> value.asText().toLongOrNull()
                else -> null
            }
        }

    private fun customFieldDateTime(fields: JsonNode, name: String): OffsetDateTime? =
        customFieldLong(fields, name)?.toOffsetDateTime()

    private fun Long.toOffsetDateTime(): OffsetDateTime =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneOffset.UTC)
}
