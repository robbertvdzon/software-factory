package nl.vdzon.softwarefactory.dashboard.youtrack

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.dashboard.api.StoryDto
import nl.vdzon.softwarefactory.dashboard.config.DashboardSecrets
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

enum class FactoryCommand(val token: String) {
    PAUSE("pause"),
    RESUME("resume"),
    KILL("kill"),
    RE_IMPLEMENT("re-implement"),
    CLEAR_ERROR("clear-error"),
    RETRY_CURRENT_STEP("retry-current-step"),
    SYNC("sync"),
    DELETE("delete"),
    MERGE("merge"),
}

data class ProjectDto(
    val key: String,
    val name: String,
    val targetRepo: String?,
)

data class AttachmentDto(
    val id: String,
    val name: String,
    val url: String?,
    val mimeType: String?,
    val size: Long?,
    val created: Long?,
)

@Component
class YouTrackClient(
    private val secrets: DashboardSecrets,
) {
    private val objectMapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newHttpClient()
    private val baseUrl = secrets.youTrackBaseUrl.trimEnd('/')

    fun findWorkIssues(maxResults: Int): List<StoryDto> {
        val projects = listManagedProjects().filter { project ->
            secrets.youTrackProjects.isEmpty() || project.key in secrets.youTrackProjects
        }.filter { project ->
            secrets.youTrackProjects.isNotEmpty() || !project.targetRepo.isNullOrBlank()
        }
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
                .filter { it.aiSupplier?.lowercase() !in setOf(null, "", "none") }
        }.sortedBy { it.key }
    }

    fun createIssue(
        projectKey: String,
        targetRepo: String?,
        aiSupplier: String?,
        aiModel: String?,
        budget: Long?,
        title: String,
        description: String?,
    ): StoryDto {
        val projectId = resolveProjectId(projectKey)
            ?: error("Onbekend YouTrack-project: $projectKey")

        // De orchestrator leidt de doel-repo af uit de project-beschrijving, niet uit een
        // issue-veld. Neem de opgegeven repo-projectnaam daarom pragmatisch op in de
        // story-description zodat hij zichtbaar is en niet verloren gaat.
        val effectiveDescription = buildString {
            description?.takeIf { it.isNotBlank() }?.let { append(it) }
            targetRepo?.takeIf { it.isNotBlank() }?.let { repo ->
                if (isNotEmpty()) append("\n\n")
                append("Repo: ").append(repo)
            }
        }.takeIf { it.isNotBlank() }

        val createBody = buildMap<String, Any?> {
            put("project", mapOf("id" to projectId))
            put("summary", title)
            effectiveDescription?.let { put("description", it) }
        }
        val created = sendJson("POST", "/api/issues", listOf("fields" to "idReadable"), body = createBody)
        val issueKey = created.path("idReadable").asText()

        val customFields = buildList {
            add(enumFieldValue("Stage", "Develop"))
            aiSupplier?.takeIf { it.isNotBlank() }?.let { add(enumFieldValue("AI-supplier", it)) }
            aiModel?.takeIf { it.isNotBlank() }?.let { add(enumFieldValue("AI Model", it)) }
            budget?.let { add(simpleFieldValue("AI Token Budget", it)) }
        }
        sendJson(
            "POST",
            "/api/issues/${issueKey.pathEncoded()}",
            listOf("fields" to "idReadable,customFields(name,value(name))"),
            body = mapOf("customFields" to customFields),
        )

        return getIssue(issueKey)
    }

    fun getIssue(issueKey: String): StoryDto {
        val projectKey = issueKey.substringBefore('-', missingDelimiterValue = "")
        val project = listManagedProjects().firstOrNull { it.key == projectKey }
        val root = sendJson("GET", "/api/issues/${issueKey.pathEncoded()}", listOf("fields" to issueFields))
        return mapIssue(root, project?.targetRepo)
    }

    fun postCommand(issueKey: String, command: FactoryCommand) {
        sendJson(
            "POST",
            "/api/issues/${issueKey.pathEncoded()}/comments",
            listOf("fields" to "id,text"),
            body = mapOf("text" to "@factory:command:${command.token}"),
        )
    }

    fun testerScreenshots(issueKey: String): List<AttachmentDto> =
        issueAttachments(issueKey)
            .filter { it.name.startsWith(TESTER_SCREENSHOT_ATTACHMENT_PREFIX) }
            .sortedBy { it.name }

    fun downloadAttachment(url: String): DownloadedAttachment {
        val response = httpClient.send(
            HttpRequest.newBuilder(URI.create(if (url.startsWith("http")) url else baseUrl + url))
                .header("Authorization", "Bearer ${secrets.youTrackToken}")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        if (response.statusCode() !in 200..299) {
            error("YouTrack attachment download failed with status ${response.statusCode()}")
        }
        return DownloadedAttachment(
            bytes = response.body(),
            contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream"),
        )
    }

    fun listManagedProjects(): List<ProjectDto> {
        val root = sendJson(
            "GET",
            "/api/admin/projects",
            listOf("fields" to "id,name,shortName,description,archived", "\$top" to "1000"),
        )
        return root.filterNot { it.path("archived").asBoolean(false) }
            .map {
                ProjectDto(
                    key = it.path("shortName").asText(),
                    name = it.path("name").asText(""),
                    targetRepo = extractTargetRepo(it.path("description").asText("")),
                )
            }
    }

    private fun resolveProjectId(projectKey: String): String? {
        val root = sendJson(
            "GET",
            "/api/admin/projects",
            listOf("fields" to "id,shortName,archived", "\$top" to "1000"),
        )
        return root.firstOrNull {
            it.path("shortName").asText() == projectKey && !it.path("archived").asBoolean(false)
        }?.path("id")?.asText()?.takeIf { it.isNotBlank() }
    }

    private fun enumFieldValue(name: String, value: String): Map<String, Any?> =
        mapOf(
            "name" to name,
            "\$type" to "SingleEnumIssueCustomField",
            "value" to mapOf("name" to value),
        )

    private fun simpleFieldValue(name: String, value: Any?): Map<String, Any?> =
        mapOf(
            "name" to name,
            "\$type" to "SimpleIssueCustomField",
            "value" to value,
        )

    private fun issueAttachments(issueKey: String): List<AttachmentDto> {
        val root = sendJson(
            "GET",
            "/api/issues/${issueKey.pathEncoded()}/attachments",
            listOf("fields" to attachmentFields, "\$top" to "1000"),
        )
        return root.map {
            AttachmentDto(
                id = it.path("id").asText(),
                name = it.path("name").asText(""),
                url = it.path("url").asText(null)?.takeIf { value -> value.isNotBlank() },
                mimeType = it.path("mimeType").asText(null)?.takeIf { value -> value.isNotBlank() },
                size = it.path("size").takeIf { value -> value.isNumber }?.asLong(),
                created = it.path("created").takeIf { value -> value.isNumber }?.asLong(),
            )
        }
    }

    private fun mapIssue(issue: JsonNode, fallbackTargetRepo: String?): StoryDto {
        val fields = issue.path("customFields")
        return StoryDto(
            key = issue.path("idReadable").asText(),
            summary = issue.path("summary").asText(""),
            description = issue.path("description").asText(null)?.takeIf { it.isNotBlank() },
            status = customFieldText(fields, "Stage").orEmpty(),
            targetRepo = extractTargetRepo(issue.path("project").path("description").asText("")) ?: fallbackTargetRepo,
            aiSupplier = customFieldText(fields, "AI-supplier"),
            aiPhase = customFieldText(fields, "AI Phase"),
            aiLevel = customFieldLong(fields, "AI Level")?.toInt(),
            aiTokenBudget = customFieldLong(fields, "AI Token Budget"),
            aiTokensUsed = customFieldLong(fields, "AI Tokens Used"),
            paused = customFieldText(fields, "Paused").equals("true", ignoreCase = true),
            error = customFieldText(fields, "Error"),
        )
    }

    private fun extractTargetRepo(description: String): String? {
        val configured = Regex("""(?m)^\s*factory\.(?:repo|githubRepo)\s*=\s*(\S+)\s*$""")
            .find(description)
            ?.groupValues
            ?.getOrNull(1)
        if (!configured.isNullOrBlank()) {
            return configured.trim().trim('<', '>')
        }
        return Regex("""(?:https?://[^\s>)]+|git@[^\s>)]+)""")
            .find(description)
            ?.value
            ?.trim()
            ?.trim('<', '>')
    }

    private fun customFieldText(fields: JsonNode, name: String): String? {
        val value = fields.firstOrNull { it.path("name").asText() == name }?.path("value") ?: return null
        if (value.isMissingNode || value.isNull) return null
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

    private fun sendJson(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: Any? = null,
    ): JsonNode {
        val response = httpClient.send(request(method, path, query, body), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("YouTrack request $method $path failed with status ${response.statusCode()}: ${response.body().take(500)}")
        }
        return if (response.body().isNullOrBlank()) objectMapper.createObjectNode() else objectMapper.readTree(response.body())
    }

    private fun request(method: String, path: String, query: List<Pair<String, String>>, body: Any?): HttpRequest {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path + query.toQueryString()))
            .header("Authorization", "Bearer ${secrets.youTrackToken}")
            .header("Accept", "application/json")
        return if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
        } else {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build()
        }
    }

    private fun List<Pair<String, String>>.toQueryString(): String =
        if (isEmpty()) "" else joinToString(prefix = "?", separator = "&") { (key, value) ->
            "${key.urlEncoded()}=${value.urlEncoded()}"
        }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)
    private fun String.pathEncoded(): String = urlEncoded().replace("+", "%20")
    private companion object {
        private const val TESTER_SCREENSHOT_ATTACHMENT_PREFIX = "factory-tester-screenshot__"
        private const val attachmentFields = "id,name,url,mimeType,size,created"
        private const val issueFields =
            "id,idReadable,summary,description,project(id,name,shortName,description)," +
                "customFields(name,value(id,name,presentation,text,localizedName))"
    }
}

data class DownloadedAttachment(
    val bytes: ByteArray,
    val contentType: String,
)
