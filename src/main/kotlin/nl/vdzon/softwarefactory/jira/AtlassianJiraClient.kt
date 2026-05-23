package nl.vdzon.softwarefactory.jira

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

@Component
class AtlassianJiraClient(
    private val factorySecrets: FactorySecrets,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : JiraClient {
    private val baseUrl = factorySecrets.jiraBaseUrl.trimEnd('/')
    private val authorizationHeader = "Basic " + Base64.getEncoder().encodeToString(
        "${factorySecrets.jiraEmail}:${factorySecrets.jiraApiKey}".toByteArray(StandardCharsets.UTF_8),
    )
    private val fieldMapping: JiraFieldMapping by lazy { loadFieldMapping() }

    override fun findAiIssues(projectKey: String, maxResults: Int): List<JiraIssue> {
        val mapping = fieldMapping
        val fields = (listOf("summary", "status", "comment") + mapping.searchableFieldIds()).joinToString(",")
        val issues = mutableListOf<JiraIssue>()
        var nextPageToken: String? = null

        do {
            val params = mutableListOf(
                "jql" to "project = $projectKey AND status = AI ORDER BY key ASC",
                "maxResults" to maxResults.coerceAtLeast(1).toString(),
                "fields" to fields,
            )
            nextPageToken?.let { params += "nextPageToken" to it }
            val root = sendJson("GET", "/rest/api/3/search/jql", params)
            root.path("issues").forEach { issues += mapIssue(it, mapping) }
            nextPageToken = root.path("nextPageToken").asText().takeIf { it.isNotBlank() }
            val isLast = root.path("isLast").asBoolean(true)
        } while (!isLast && nextPageToken != null)

        return issues
    }

    override fun getIssue(issueKey: String): JiraIssue {
        val mapping = fieldMapping
        val fields = (listOf("summary", "status", "comment") + mapping.searchableFieldIds()).joinToString(",")
        val root = sendJson(
            "GET",
            "/rest/api/3/issue/${issueKey.pathEncoded()}",
            listOf("fields" to fields),
        )
        return mapIssue(root, mapping)
    }

    override fun updateIssueFields(issueKey: String, update: JiraFieldUpdate) {
        if (update.values.isEmpty()) {
            return
        }

        val fields = update.values.entries.associate { (field, value) ->
            fieldMapping.id(field) to toJiraFieldValue(field, value)
        }

        sendJson(
            "PUT",
            "/rest/api/3/issue/${issueKey.pathEncoded()}",
            body = mapOf("fields" to fields),
            allowedStatuses = setOf(204),
        )
    }

    override fun transitionIssue(issueKey: String, statusName: String) {
        val transitions = sendJson(
            "GET",
            "/rest/api/3/issue/${issueKey.pathEncoded()}/transitions",
        ).path("transitions")
        val transition = transitions.firstOrNull { transition ->
            transition.path("name").asText("").equals(statusName, ignoreCase = true)
        } ?: throw JiraClientException("Jira transition '$statusName' is not available for $issueKey.")

        sendJson(
            "POST",
            "/rest/api/3/issue/${issueKey.pathEncoded()}/transitions",
            body = mapOf("transition" to mapOf("id" to transition.path("id").asText())),
            allowedStatuses = setOf(204),
        )
    }

    override fun postAgentComment(issueKey: String, role: AgentRole, message: String): JiraComment {
        val prefixedMessage = "${role.commentPrefix} $message"
        val root = sendJson(
            "POST",
            "/rest/api/3/issue/${issueKey.pathEncoded()}/comment",
            body = mapOf("body" to AtlassianDocument.plainTextDocument(prefixedMessage)),
            allowedStatuses = setOf(200, 201),
        )
        return mapComment(root)
    }

    override fun hasProcessedCommentMarker(commentId: String, role: AgentRole): Boolean {
        val response = send(
            request(
                "GET",
                "/rest/api/3/comment/${commentId.pathEncoded()}/properties/${processedMarkerKey(role).pathEncoded()}",
            ),
        )
        return when (response.status) {
            200 -> true
            in fallbackMarkerStatuses -> false
            else -> throw JiraClientException("Jira comment marker lookup failed with status ${response.status}.")
        }
    }

    override fun markCommentProcessed(commentId: String, role: AgentRole): Boolean {
        val response = send(
            request(
                "PUT",
                "/rest/api/3/comment/${commentId.pathEncoded()}/properties/${processedMarkerKey(role).pathEncoded()}",
                body = mapOf(
                    "marker" to "eyes",
                    "role" to role.markerKeyPart,
                    "processedAt" to OffsetDateTime.now(ZoneOffset.UTC).toString(),
                ),
            ),
        )
        return when (response.status) {
            200, 201, 204 -> true
            in fallbackMarkerStatuses -> false
            else -> throw JiraClientException("Jira comment marker update failed with status ${response.status}.")
        }
    }

    private fun loadFieldMapping(): JiraFieldMapping {
        val root = sendJson("GET", "/rest/api/3/field")
        val fields = root.map { field ->
            JiraFieldMetadata(
                id = field.path("id").asText(),
                name = field.path("name").asText(),
                schemaType = field.path("schema").path("type").asText().takeIf { it.isNotBlank() },
            )
        }
        return JiraFieldMapping.fromDefinitions(fields)
    }

    private fun mapIssue(issue: JsonNode, mapping: JiraFieldMapping): JiraIssue {
        val fields = issue.path("fields")
        return JiraIssue(
            key = issue.path("key").asText(),
            summary = fields.path("summary").asText(""),
            status = fields.path("status").path("name").asText(""),
            fields = JiraIssueFields(
                targetRepo = textField(fields, mapping, JiraKnownField.TARGET_REPO),
                aiPhase = textField(fields, mapping, JiraKnownField.AI_PHASE),
                aiLevel = numberField(fields, mapping, JiraKnownField.AI_LEVEL)?.toInt(),
                aiTokenBudget = numberField(fields, mapping, JiraKnownField.AI_TOKEN_BUDGET),
                aiTokensUsed = numberField(fields, mapping, JiraKnownField.AI_TOKENS_USED),
                agentStartedAt = dateTimeField(fields, mapping, JiraKnownField.AGENT_STARTED_AT),
                paused = booleanField(fields, mapping, JiraKnownField.PAUSED),
                error = textField(fields, mapping, JiraKnownField.ERROR),
            ),
            comments = fields.path("comment").path("comments").map { mapComment(it) },
        )
    }

    private fun mapComment(comment: JsonNode): JiraComment =
        JiraComment(
            id = comment.path("id").asText(),
            authorAccountId = comment.path("author").path("accountId").asText().takeIf { it.isNotBlank() },
            authorDisplayName = comment.path("author").path("displayName").asText().takeIf { it.isNotBlank() },
            body = AtlassianDocument.toPlainText(comment.path("body")),
            created = parseDateTime(comment.path("created").asText().takeIf { it.isNotBlank() }),
        )

    private fun textField(fields: JsonNode, mapping: JiraFieldMapping, field: JiraKnownField): String? {
        val node = fields.path(mapping.id(field))
        return node.takeUnless { it.isMissingNode || it.isNull }?.asText()?.takeIf { it.isNotBlank() }
    }

    private fun numberField(fields: JsonNode, mapping: JiraFieldMapping, field: JiraKnownField): Long? {
        val node = fields.path(mapping.id(field))
        return node.takeUnless { it.isMissingNode || it.isNull }?.asLong()
    }

    private fun booleanField(fields: JsonNode, mapping: JiraFieldMapping, field: JiraKnownField): Boolean {
        val node = fields.path(mapping.id(field))
        if (node.isMissingNode || node.isNull) {
            return false
        }
        return when {
            node.isBoolean -> node.asBoolean()
            node.isTextual -> node.asText().equals("true", ignoreCase = true) ||
                node.asText().equals("yes", ignoreCase = true) ||
                node.asText() == "1"
            else -> node.asBoolean(false)
        }
    }

    private fun dateTimeField(fields: JsonNode, mapping: JiraFieldMapping, field: JiraKnownField): OffsetDateTime? =
        parseDateTime(fields.path(mapping.id(field)).asText().takeIf { it.isNotBlank() && it != "null" })

    private fun parseDateTime(value: String?): OffsetDateTime? {
        if (value.isNullOrBlank()) {
            return null
        }
        return runCatching { OffsetDateTime.parse(value) }
            .recoverCatching { OffsetDateTime.parse(value, jiraDateTimeFormatter) }
            .getOrNull()
    }

    private fun toJiraFieldValue(field: JiraKnownField, value: Any?): Any? =
        when (field) {
            JiraKnownField.ERROR -> when {
                value == null -> null
                else -> AtlassianDocument.plainTextDocument(value.toString())
            }
            JiraKnownField.PAUSED -> when (fieldMapping.schemaType(field)) {
                "boolean" -> value as? Boolean
                else -> value?.toString()
            }
            JiraKnownField.AGENT_STARTED_AT -> (value as? OffsetDateTime)?.format(jiraDateTimeFormatter)
            else -> value
        }

    private fun sendJson(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: Any? = null,
        allowedStatuses: Set<Int> = successStatuses,
    ): JsonNode {
        val response = send(request(method, path, query, body))
        if (response.status !in allowedStatuses) {
            throw JiraClientException("Jira request $method $path failed with status ${response.status}: ${response.body.take(500)}")
        }
        return if (response.body.isBlank()) objectMapper.createObjectNode() else objectMapper.readTree(response.body)
    }

    private fun request(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: Any? = null,
    ): HttpRequest {
        val uri = URI.create(baseUrl + path + query.toQueryString())
        val builder = HttpRequest.newBuilder(uri)
            .header("Authorization", authorizationHeader)
            .header("Accept", "application/json")
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
        }
        return builder.build()
    }

    private fun send(request: HttpRequest): JiraResponse =
        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            JiraResponse(response.statusCode(), response.body() ?: "")
        } catch (exception: Exception) {
            throw JiraClientException("Jira request failed: ${request.method()} ${request.uri().path}", exception)
        }

    private fun processedMarkerKey(role: AgentRole): String =
        "software-factory-processed-${role.markerKeyPart}"

    private fun List<Pair<String, String>>.toQueryString(): String =
        if (isEmpty()) {
            ""
        } else {
            joinToString(prefix = "?", separator = "&") { (key, value) ->
                "${key.urlEncoded()}=${value.urlEncoded()}"
            }
        }

    private fun String.urlEncoded(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8)

    private fun String.pathEncoded(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")

    private data class JiraResponse(
        val status: Int,
        val body: String,
    )

    companion object {
        private val successStatuses = (200..299).toSet()
        private val fallbackMarkerStatuses = setOf(400, 403, 404, 405, 410)
        private val jiraDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
    }
}
