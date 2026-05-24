package nl.vdzon.softwarefactory.youtrack

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Component
class YouTrackClient(
    private val factorySecrets: FactorySecrets,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : YouTrackApi {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val baseUrl = factorySecrets.youTrackBaseUrl.trimEnd('/')
    private val authorizationHeader = "Bearer ${factorySecrets.youTrackToken}"
    private val bootstrappedProjectKeys = mutableSetOf<String>()

    override fun ensureConfiguredProjects(): List<TrackerProject> {
        val projects = listProjects().filter { project ->
            factorySecrets.youTrackProjects.isEmpty() || project.key in factorySecrets.youTrackProjects
        }.filter { project ->
            factorySecrets.youTrackProjects.isNotEmpty() || !project.targetRepo.isNullOrBlank()
        }
        projects.forEach { project -> ensureProjectSchema(project) }
        return projects
    }

    override fun findWorkIssues(maxResults: Int): List<TrackerIssue> {
        val projects = ensureConfiguredProjects()
        return projects.flatMap { project ->
            val root = sendJson(
                "GET",
                "/api/issues",
                listOf(
                    "query" to "project: ${project.key} Stage: Develop",
                    "\$top" to maxResults.coerceAtLeast(1).toString(),
                    "fields" to issueFields,
                ),
            )
            root.map { mapIssue(it, project.targetRepo) }
                .filter { issue -> issue.fields.aiSupplier?.lowercase() !in setOf(null, "", "none") }
        }.sortedBy { it.key }
    }

    override fun getIssue(issueKey: String): TrackerIssue {
        val projectKey = issueKey.substringBefore('-', missingDelimiterValue = "")
        val project = listProjects().firstOrNull { it.key == projectKey }
        project?.let { ensureProjectSchema(it) }
        val root = sendJson("GET", "/api/issues/${issueKey.pathEncoded()}", listOf("fields" to issueFields))
        return mapIssue(root, project?.targetRepo)
    }

    override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
        if (update.values.isEmpty()) {
            return
        }
        val customFields = update.values.map { (field, value) -> fieldUpdate(field, value) }
        sendJson(
            "POST",
            "/api/issues/${issueKey.pathEncoded()}",
            listOf("fields" to "idReadable,customFields(name,value(name,presentation,text))"),
            body = mapOf("customFields" to customFields),
        )
    }

    override fun updateIssueSummary(issueKey: String, summary: String) {
        sendJson(
            "POST",
            "/api/issues/${issueKey.pathEncoded()}",
            listOf("fields" to "idReadable,summary"),
            body = mapOf("summary" to summary),
        )
    }

    override fun transitionIssue(issueKey: String, statusName: String) {
        sendJson(
            "POST",
            "/api/commands",
            body = mapOf(
                "query" to "Stage $statusName",
                "issues" to listOf(mapOf("idReadable" to issueKey)),
            ),
        )
    }

    override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment {
        return postComment(issueKey, "${role.commentPrefix} $message")
    }

    override fun postComment(issueKey: String, message: String): TrackerComment {
        val root = sendJson(
            "POST",
            "/api/issues/${issueKey.pathEncoded()}/comments",
            listOf("fields" to commentFields),
            body = mapOf("text" to message),
        )
        return mapComment(root)
    }

    override fun hasProcessedCommentMarker(issueKey: String, commentId: String, role: AgentRole): Boolean {
        val response = send(
            request(
                "GET",
                "/api/issues/${issueKey.pathEncoded()}/comments/${commentId.pathEncoded()}/reactions",
                listOf("fields" to "id,reaction,author(login)", "\$top" to "100"),
            ),
        )
        return when (response.status) {
            in successStatuses -> objectMapper.readTree(response.body).any { it.path("reaction").asText() == PROCESSED_REACTION }
            in fallbackMarkerStatuses -> false
            else -> throw YouTrackApiException("YouTrack reaction lookup failed with status ${response.status}.")
        }
    }

    override fun markCommentProcessed(issueKey: String, commentId: String, role: AgentRole): Boolean {
        val response = send(
            request(
                "POST",
                "/api/issues/${issueKey.pathEncoded()}/comments/${commentId.pathEncoded()}/reactions",
                listOf("fields" to "id,reaction,author(login)"),
                body = mapOf("reaction" to PROCESSED_REACTION),
            ),
        )
        return when (response.status) {
            in successStatuses, 409 -> true
            in fallbackMarkerStatuses -> false
            else -> throw YouTrackApiException("YouTrack reaction update failed with status ${response.status}.")
        }
    }

    override fun deleteAgentComments(issueKey: String): Int {
        val issue = getIssue(issueKey)
        val agentCommentIds = issue.comments.filter { it.isAgentComment }.map { it.id }
        agentCommentIds.forEach { commentId ->
            sendJson(
                "DELETE",
                "/api/issues/${issueKey.pathEncoded()}/comments/${commentId.pathEncoded()}",
                allowedStatuses = setOf(200, 204),
            )
        }
        return agentCommentIds.size
    }

    private fun ensureProjectSchema(project: TrackerProject) {
        if (!bootstrappedProjectKeys.add(project.key)) {
            return
        }

        val globalFields = loadGlobalFields().toMutableMap()
        factoryFieldSpecs.forEach { spec ->
            val existing = globalFields[spec.name]
            if (existing == null) {
                globalFields[spec.name] = createCustomField(spec)
            } else if (existing.fieldTypeId != spec.fieldTypeId) {
                throw YouTrackApiException(
                    "YouTrack custom field '${spec.name}' has type '${existing.fieldTypeId}', expected '${spec.fieldTypeId}'.",
                )
            }
        }

        var projectFields = loadProjectFields(project.id).toMutableMap()
        factoryFieldSpecs.forEach { spec ->
            val customField = requireNotNull(globalFields[spec.name])
            val projectField = projectFields[spec.name] ?: attachFieldToProject(project.id, customField.id, spec).also {
                projectFields[it.name] = it
            }
            if (projectField.type != spec.projectFieldType) {
                throw YouTrackApiException(
                    "YouTrack project field '${spec.name}' in ${project.key} has type '${projectField.type}', expected '${spec.projectFieldType}'.",
                )
            }
            spec.values.forEach { value -> ensureBundleValue(project.id, projectField, value) }
        }

        projectFields = loadProjectFields(project.id).toMutableMap()
        val stageField = projectFields["Stage"]
            ?: throw YouTrackApiException("YouTrack project ${project.key} has no Stage field.")
        ensureBundleValue(project.id, stageField, "Develop")

        logger.info("YouTrack project {} schema is ready.", project.key)
    }

    private fun listProjects(): List<TrackerProject> {
        val root = sendJson(
            "GET",
            "/api/admin/projects",
            listOf("fields" to "id,name,shortName,description,archived", "\$top" to "1000"),
        )
        return root
            .filterNot { it.path("archived").asBoolean(false) }
            .map {
                TrackerProject(
                    id = it.path("id").asText(),
                    key = it.path("shortName").asText(),
                    name = it.path("name").asText(),
                    targetRepo = extractTargetRepo(it.path("description").asText("")),
                )
            }
    }

    private fun extractTargetRepo(description: String): String? {
        val configured = Regex("""(?m)^\s*factory\.githubRepo\s*=\s*(\S+)\s*$""")
            .find(description)
            ?.groupValues
            ?.getOrNull(1)
        if (!configured.isNullOrBlank()) {
            return configured.trim().trim('<', '>')
        }
        return Regex("""(?:https://github\.com/[^\s>)]+|git@github\.com:[^\s>)]+)""")
            .find(description)
            ?.value
            ?.trim()
            ?.trim('<', '>')
    }

    private fun loadGlobalFields(): Map<String, CustomFieldDefinition> {
        val root = sendJson(
            "GET",
            "/api/admin/customFieldSettings/customFields",
            listOf("fields" to "id,name,fieldType(id)", "\$top" to "1000"),
        )
        return root.associate { field ->
            field.path("name").asText() to CustomFieldDefinition(
                id = field.path("id").asText(),
                name = field.path("name").asText(),
                fieldTypeId = field.path("fieldType").path("id").asText(),
            )
        }
    }

    private fun createCustomField(spec: FieldSpec): CustomFieldDefinition {
        val root = sendJson(
            "POST",
            "/api/admin/customFieldSettings/customFields",
            listOf("fields" to "id,name,fieldType(id)"),
            body = mapOf(
                "name" to spec.name,
                "fieldType" to mapOf("id" to spec.fieldTypeId),
                "isDisplayedInIssueList" to false,
                "isAutoAttached" to false,
                "isPublic" to true,
            ),
        )
        return CustomFieldDefinition(
            id = root.path("id").asText(),
            name = root.path("name").asText(),
            fieldTypeId = root.path("fieldType").path("id").asText(),
        )
    }

    private fun loadProjectFields(projectId: String): Map<String, ProjectFieldDefinition> {
        val root = sendJson(
            "GET",
            "/api/admin/projects/${projectId.pathEncoded()}/customFields",
            listOf("fields" to "id,\$type,field(id,name,fieldType(id)),bundle(id,values(id,name))", "\$top" to "1000"),
        )
        return root.associate { field ->
            val name = field.path("field").path("name").asText()
            name to ProjectFieldDefinition(
                id = field.path("id").asText(),
                name = name,
                type = field.path("\$type").asText(),
                bundleId = field.path("bundle").path("id").asText().takeIf { it.isNotBlank() },
                values = field.path("bundle").path("values").map { it.path("name").asText() }.toSet(),
            )
        }
    }

    private fun attachFieldToProject(projectId: String, fieldId: String, spec: FieldSpec): ProjectFieldDefinition {
        val root = sendJson(
            "POST",
            "/api/admin/projects/${projectId.pathEncoded()}/customFields",
            listOf("fields" to "id,\$type,field(name),bundle(id,values(id,name))"),
            body = mapOf(
                "field" to mapOf("id" to fieldId, "\$type" to "CustomField"),
                "\$type" to spec.projectFieldType,
                "canBeEmpty" to spec.canBeEmpty,
                "isPublic" to true,
            ),
        )
        return ProjectFieldDefinition(
            id = root.path("id").asText(),
            name = root.path("field").path("name").asText(spec.name),
            type = root.path("\$type").asText(spec.projectFieldType),
            bundleId = root.path("bundle").path("id").asText().takeIf { it.isNotBlank() },
            values = root.path("bundle").path("values").map { it.path("name").asText() }.toSet(),
        )
    }

    private fun ensureBundleValue(projectId: String, projectField: ProjectFieldDefinition, value: String) {
        if (value in projectField.values) {
            return
        }
        val response = send(
            request(
                "POST",
                "/api/admin/projects/${projectId.pathEncoded()}/customFields/${projectField.id.pathEncoded()}/bundle/values",
                listOf("fields" to "id,name"),
                body = mapOf("name" to value),
            ),
        )
        if (response.status !in successStatuses && response.status != 409) {
            throw YouTrackApiException(
                "Could not add value '$value' to YouTrack field '${projectField.name}': HTTP ${response.status} ${response.body.take(300)}",
            )
        }
    }

    private fun mapIssue(issue: JsonNode, fallbackTargetRepo: String?): TrackerIssue {
        val fields = issue.path("customFields")
        return TrackerIssue(
            id = issue.path("id").asText(),
            key = issue.path("idReadable").asText(),
            summary = issue.path("summary").asText(""),
            description = issue.path("description").asText(null)?.takeIf { it.isNotBlank() },
            projectKey = issue.path("project").path("shortName").asText(issue.path("idReadable").asText().substringBefore("-")),
            status = customFieldText(fields, "Stage").orEmpty(),
            fields = TrackerIssueFields(
                targetRepo = extractTargetRepo(issue.path("project").path("description").asText("")) ?: fallbackTargetRepo,
                aiSupplier = customFieldText(fields, TrackerField.AI_SUPPLIER.displayName),
                aiPhase = customFieldText(fields, TrackerField.AI_PHASE.displayName),
                aiLevel = customFieldLong(fields, TrackerField.AI_LEVEL.displayName)?.toInt(),
                aiTokenBudget = customFieldLong(fields, TrackerField.AI_TOKEN_BUDGET.displayName),
                aiTokensUsed = customFieldLong(fields, TrackerField.AI_TOKENS_USED.displayName),
                agentStartedAt = customFieldDateTime(fields, TrackerField.AGENT_STARTED_AT.displayName),
                paused = customFieldText(fields, TrackerField.PAUSED.displayName).equals("true", ignoreCase = true),
                error = customFieldText(fields, TrackerField.ERROR.displayName),
            ),
            comments = issue.path("comments").map { mapComment(it) },
        )
    }

    private fun mapComment(comment: JsonNode): TrackerComment =
        TrackerComment(
            id = comment.path("id").asText(),
            authorAccountId = comment.path("author").path("login").asText().takeIf { it.isNotBlank() },
            authorDisplayName = comment.path("author").path("fullName").asText().takeIf { it.isNotBlank() },
            body = comment.path("text").asText(""),
            created = comment.path("created").takeIf { it.isNumber }?.asLong()?.toOffsetDateTime(),
        )

    private fun fieldUpdate(field: TrackerField, value: Any?): Map<String, Any?> =
        when (field) {
            TrackerField.AI_SUPPLIER,
            TrackerField.AI_PHASE,
            TrackerField.PAUSED,
            -> mapOf(
                "name" to field.displayName,
                "\$type" to "SingleEnumIssueCustomField",
                "value" to value?.let { mapOf("name" to it.toString()) },
            )
            TrackerField.AI_LEVEL,
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
        }

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

    private fun sendJson(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: Any? = null,
        allowedStatuses: Set<Int> = successStatuses,
    ): JsonNode {
        val response = send(request(method, path, query, body))
        if (response.status !in allowedStatuses) {
            throw YouTrackApiException(
                "YouTrack request $method $path failed with status ${response.status}: ${response.body.take(500)}",
            )
        }
        return if (response.body.isBlank()) objectMapper.createObjectNode() else objectMapper.readTree(response.body)
    }

    private fun request(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: Any? = null,
    ): HttpRequest {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path + query.toQueryString()))
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

    private fun send(request: HttpRequest): YouTrackResponse =
        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            YouTrackResponse(response.statusCode(), response.body() ?: "")
        } catch (exception: Exception) {
            throw YouTrackApiException(
                "YouTrack request failed: ${request.method()} ${request.uri().path}",
                exception,
            )
        }

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

    private data class CustomFieldDefinition(
        val id: String,
        val name: String,
        val fieldTypeId: String,
    )

    private data class ProjectFieldDefinition(
        val id: String,
        val name: String,
        val type: String,
        val bundleId: String?,
        val values: Set<String>,
    )

    private data class FieldSpec(
        val name: String,
        val fieldTypeId: String,
        val projectFieldType: String,
        val canBeEmpty: Boolean = true,
        val values: List<String> = emptyList(),
    )

    private data class YouTrackResponse(
        val status: Int,
        val body: String,
    )

    companion object {
        private const val PROCESSED_REACTION = "eyes"
        private val successStatuses = (200..299).toSet()
        private val fallbackMarkerStatuses = setOf(400, 403, 404, 405, 410)
        private const val commentFields = "id,text,author(login,fullName),created,reactions(id,reaction,author(login))"
        private const val issueFields =
            "id,idReadable,summary,description,project(id,name,shortName,description)," +
                "customFields(name,value(id,name,presentation,text,localizedName))," +
                "comments($commentFields)"
        private val phaseValues = listOf(
            "refining",
            "developing",
            "reviewing",
            "testing",
            "refined-with-questions-for-user",
            "refined-finished",
            "developed",
            "reviewed-with-feedback-for-developer",
            "review-finished",
            "tested-with-feedback-for-developer",
            "tested-successfully",
            "questions-answered-for-refinement",
        )
        private val factoryFieldSpecs = listOf(
            FieldSpec("AI-supplier", "enum[1]", "EnumProjectCustomField", values = listOf("none", "mock", "claude", "openai", "microsoft")),
            FieldSpec("AI Phase", "enum[1]", "EnumProjectCustomField", values = phaseValues),
            FieldSpec("AI Level", "integer", "SimpleProjectCustomField"),
            FieldSpec("AI Token Budget", "integer", "SimpleProjectCustomField"),
            FieldSpec("AI Tokens Used", "integer", "SimpleProjectCustomField"),
            FieldSpec("AgentStartedAt", "date and time", "SimpleProjectCustomField"),
            FieldSpec("Paused", "enum[1]", "EnumProjectCustomField", values = listOf("false", "true")),
            FieldSpec("Error", "text", "TextProjectCustomField"),
        )
    }
}

@Component
class YouTrackSchemaStartup(
    private val issueTrackerClient: YouTrackApi,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        issueTrackerClient.ensureConfiguredProjects()
    }
}
