package nl.vdzon.softwarefactory.dashboard.youtrack

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.core.TrackerField
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
)

data class AttachmentDto(
    val id: String,
    val name: String,
    val url: String?,
    val mimeType: String?,
    val size: Long?,
    val created: Long?,
)

/**
 * Slanke read-client op YouTrack voor het dashboard, op het huidige factory-model:
 * - stories zijn issues met een gezet `Story Phase`-veld (lege fase = nog niet in de factory);
 * - de doel-repo staat in het `Repo`-custom-veld (projectnaam uit projects.yaml óf directe URL),
 *   niet meer in een `factory.repo=`-regel in de projectbeschrijving.
 * Veldnamen komen uit factory-common ([TrackerField]) zodat dashboard en factory niet kunnen divergeren.
 */
@Component
class YouTrackClient(
    private val secrets: DashboardSecrets,
    private val projectRepoResolver: ProjectRepoResolver,
    // Injecteerbare klok zodat tests de TTL-cache deterministisch kunnen laten verlopen.
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val objectMapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newHttpClient()
    private val baseUrl = secrets.youTrackBaseUrl.trimEnd('/')

    // Korte TTL-cache op de werkvoorraad: het dashboard vraagt bij één refresh dezelfde lijst
    // voor meerdere widgets/endpoints op; zonder cache betekende dat telkens opnieuw een
    // YouTrack-roundtrip per project (zelfde patroon als FactoryDashboardService.myActionsCountCache).
    @Volatile
    private var workIssuesCache: Pair<Long, List<StoryDto>>? = null

    fun findWorkIssues(): List<StoryDto> {
        val now = clock()
        workIssuesCache?.let { (at, issues) -> if (now - at < WORK_ISSUES_CACHE_TTL_MS) return issues }
        val issues = fetchWorkIssues()
        workIssuesCache = now to issues
        return issues
    }

    private fun fetchWorkIssues(): List<StoryDto> {
        val projects = listManagedProjects().filter { project ->
            secrets.youTrackProjects.isEmpty() || project.key in secrets.youTrackProjects
        }
        return projects.flatMap { project ->
            // Alleen stories die in de factory-flow zitten: `Story Phase` gezet (subtaken hebben
            // alleen `Subtask Phase`, dus die vallen vanzelf af) en nog niet in de Done-lane.
            val root = sendJson(
                "GET",
                "/api/issues",
                listOf(
                    "query" to "project: ${project.key} has: {${TrackerField.STORY_PHASE.displayName}} State: -Done sort by: updated desc",
                    "\$top" to WORK_ISSUES_TOP.toString(),
                    "fields" to issueFields,
                ),
            )
            root.map { mapIssue(it) }
                .filter { it.aiSupplier?.lowercase() !in setOf(null, "", "none") }
        }.distinctBy { it.key }.sortedBy { it.key }
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

        val createBody = buildMap<String, Any?> {
            put("project", mapOf("id" to projectId))
            put("summary", title)
            description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        }
        val created = sendJson("POST", "/api/issues", listOf("fields" to "idReadable"), body = createBody)
        val issueKey = created.path("idReadable").asText()

        // Zelfde velden als de factory zelf zet bij story-aanmaak (zie softwarefactory's
        // YouTrackClient.createStory): Type = User Story, Repo als multi-enum-veld (niet meer als
        // tekst in de description) en Story Phase = start zodat de orchestrator 'm oppakt.
        val customFields = buildList {
            add(enumFieldValue("Type", "User Story"))
            targetRepo?.takeIf { it.isNotBlank() }?.let {
                add(
                    mapOf(
                        "name" to TrackerField.REPO.displayName,
                        "\$type" to "MultiEnumIssueCustomField",
                        "value" to listOf(mapOf("name" to it)),
                    ),
                )
            }
            aiSupplier?.takeIf { it.isNotBlank() }?.let { add(enumFieldValue(TrackerField.AI_SUPPLIER.displayName, it)) }
            aiModel?.takeIf { it.isNotBlank() }?.let { add(enumFieldValue(TrackerField.AI_MODEL.displayName, it)) }
            budget?.let { add(simpleFieldValue(TrackerField.AI_TOKEN_BUDGET.displayName, it)) }
            add(enumFieldValue(TrackerField.STORY_PHASE.displayName, "start"))
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
        val root = sendJson("GET", "/api/issues/${issueKey.pathEncoded()}", listOf("fields" to issueFields))
        return mapIssue(root)
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

    /**
     * De niet-gearchiveerde YouTrack-projecten (voor de story-aanmaakkeuze). De doel-repo hangt
     * in het huidige model niet meer aan het project maar aan de story (`Repo`-veld).
     */
    fun listManagedProjects(): List<ProjectDto> {
        val root = sendJson(
            "GET",
            "/api/admin/projects",
            listOf("fields" to "id,name,shortName,archived", "\$top" to "1000"),
        )
        return root.filterNot { it.path("archived").asBoolean(false) }
            .map {
                ProjectDto(
                    key = it.path("shortName").asText(),
                    name = it.path("name").asText(""),
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

    private fun mapIssue(issue: JsonNode): StoryDto {
        val fields = issue.path("customFields")
        return StoryDto(
            key = issue.path("idReadable").asText(),
            summary = issue.path("summary").asText(""),
            description = issue.path("description").asText(null)?.takeIf { it.isNotBlank() },
            // De story-lifecycle staat op het `Story Phase`-veld (start/refining/…/in-progress);
            // het oude `Stage`-veld bestaat niet meer in het huidige model.
            status = customFieldText(fields, TrackerField.STORY_PHASE.displayName).orEmpty(),
            // `Repo` is een multi-enum met een projectnaam uit projects.yaml of een directe URL;
            // de resolver vertaalt een projectnaam naar de geconfigureerde repo-URL.
            targetRepo = projectRepoResolver.resolve(customFieldEnumNames(fields, TrackerField.REPO.displayName).firstOrNull()),
            aiSupplier = customFieldText(fields, TrackerField.AI_SUPPLIER.displayName),
            aiPhase = customFieldText(fields, TrackerField.AI_PHASE.displayName),
            aiLevel = customFieldLong(fields, TrackerField.AI_LEVEL.displayName)?.toInt(),
            aiTokenBudget = customFieldLong(fields, TrackerField.AI_TOKEN_BUDGET.displayName),
            aiTokensUsed = customFieldLong(fields, TrackerField.AI_TOKENS_USED.displayName),
            paused = customFieldText(fields, TrackerField.PAUSED.displayName).equals("true", ignoreCase = true),
            error = customFieldText(fields, TrackerField.ERROR.displayName),
        )
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
        // 7s: lang genoeg om één dashboard-refresh (meerdere widgets) op één fetch te laten
        // draaien, kort genoeg om fase-wijzigingen vrijwel direct te tonen.
        private const val WORK_ISSUES_CACHE_TTL_MS = 7_000L
        // Vast plafond per project (recentst-bijgewerkt eerst), i.p.v. een per-aanroep maxResults:
        // alle callers delen nu dezelfde gecachte lijst.
        private const val WORK_ISSUES_TOP = 200
        private const val attachmentFields = "id,name,url,mimeType,size,created"
        private const val issueFields =
            "id,idReadable,summary,description,project(id,name,shortName)," +
                "customFields(name,value(id,name,presentation,text,localizedName))"
    }
}

data class DownloadedAttachment(
    val bytes: ByteArray,
    val contentType: String,
)
