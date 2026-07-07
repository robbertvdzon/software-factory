package nl.vdzon.softwarefactory.youtrack.clients

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.SubtaskSpec
import nl.vdzon.softwarefactory.core.TrackerAttachment
import nl.vdzon.softwarefactory.core.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerProject
import nl.vdzon.softwarefactory.core.YouTrackApiException
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.net.http.HttpClient
import java.util.concurrent.Executors

/**
 * De [YouTrackApi]-implementatie: vertaalt de tracker-operaties naar YouTrack-REST-calls.
 * Het HTTP-verkeer zit in [YouTrackHttpTransport], de JSON↔domein-mapping in
 * [YouTrackIssueMapper] en de schema-bootstrap in [YouTrackSchemaBootstrapper];
 * deze class bevat alleen nog de endpoint-keuzes en de flow per operatie.
 *
 * Geen `@Component` (meer) — welke [YouTrackApi]-implementatie actief is, wordt bepaald door
 * `FactorySecrets.trackerBackend` via de `@Bean`-factory in [TrackerClientConfiguration], niet
 * door class-path-scanning. Zie daar voor de "youtrack" vs "postgres"-schakelaar.
 */
class YouTrackClient(
    private val factorySecrets: FactorySecrets,
    projectRepoResolver: ProjectRepoResolver,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
    httpClient: HttpClient = YouTrackHttpTransport.defaultHttpClient(),
) : YouTrackApi {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val transport = YouTrackHttpTransport(factorySecrets, objectMapper, httpClient)
    private val mapper = YouTrackIssueMapper
    private val bootstrapper = YouTrackSchemaBootstrapper(transport, projectRepoResolver)

    // findWorkIssues() haalt bij een leeg SF_YOUTRACK_PROJECTS (= alle projecten) per project een
    // aparte live call op; sequentieel opgeteld was dat de resterende trage stap in bv. myActions().
    // Daemon-threads: geen aparte shutdown-lifecycle nodig, ze mogen de JVM-shutdown niet ophouden.
    private val projectFetchExecutor = Executors.newFixedThreadPool(PROJECT_FETCH_WORKER_COUNT) { runnable ->
        Thread(runnable, "youtrack-project-fetch").apply { isDaemon = true }
    }

    override fun ensureConfiguredProjects(): List<TrackerProject> {
        // De repo wordt niet langer per project bepaald, maar per story via het `Project`-veld
        // (zie ProjectRepoResolver). Welke YouTrack-projecten gescand worden, komt puur uit
        // SF_YOUTRACK_PROJECTS; leeg = alle niet-gearchiveerde projecten.
        val allProjects = bootstrapper.listProjects()
        val projects = allProjects.filter { project ->
            factorySecrets.youTrackProjects.isEmpty() || project.key in factorySecrets.youTrackProjects
        }
        if (projects.isEmpty()) {
            val available = allProjects.joinToString(", ") { it.key }.ifBlank { "<none>" }
            throw YouTrackApiException(
                "No Software Factory YouTrack projects configured. Set SF_YOUTRACK_PROJECTS (or leave it empty to scan all). Available projects: $available",
            )
        }
        projects.forEach { project -> bootstrapper.ensureProjectSchema(project) }
        return projects
    }

    override fun findWorkIssues(maxResults: Int): List<TrackerIssue> {
        val projects = ensureConfiguredProjects()
        // Per project een eigen live call, parallel i.p.v. sequentieel: bij een leeg
        // SF_YOUTRACK_PROJECTS (= alle projecten scannen) tikt de sequentiële som van al die
        // calls flink aan; parallel schaalt de wachttijd met het traagste project, niet de som.
        val futures = projects.map { project ->
            projectFetchExecutor.submit<List<TrackerIssue>> {
                // Geen work-tags meer: alle issues van het project worden kandidaat. De fase-gate
                // in de orchestrator (lege fase = niet starten; `start` = oppakken) bepaalt de rest.
                // Recentst-bijgewerkt eerst, zodat lopende/zojuist-gestarte issues binnen de cap vallen.
                val root = transport.sendJson(
                    "GET",
                    "/api/issues",
                    listOf(
                        "query" to "project: ${project.key} sort by: updated desc",
                        "\$top" to maxResults.coerceAtLeast(1).toString(),
                        "fields" to YouTrackIssueMapper.issueFields,
                    ),
                )
                root.map { mapper.mapIssue(it) }
                    .filter { issue -> issue.fields.aiSupplier?.lowercase() !in setOf(null, "", "none") }
            }
        }
        return futures.flatMap { it.get() }
            .distinctBy { it.key }
            .sortedBy { it.key }
    }

    override fun getIssue(issueKey: String): TrackerIssue {
        val projectKey = issueKey.substringBefore('-', missingDelimiterValue = "")
        // Perf: de bootstrapper cachet per project-key; alleen de allereerste keer wordt
        // de projectlijst opgehaald — daarna is dit een no-op zonder extra call.
        bootstrapper.ensureProjectSchemaFor(projectKey)
        val root = transport.sendJson("GET", "/api/issues/${issueKey.pathEncoded()}", listOf("fields" to YouTrackIssueMapper.issueFields))
        return mapper.mapIssue(root)
    }

    override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
        if (update.values.isEmpty()) {
            return
        }
        val customFields = update.values.map { (field, value) -> mapper.fieldUpdate(field, value) }
        transport.sendJson(
            "POST",
            "/api/issues/${issueKey.pathEncoded()}",
            listOf("fields" to "idReadable,customFields(name,value(name,presentation,text))"),
            body = mapOf("customFields" to customFields),
        )
    }

    override fun createSubtask(parentKey: String, spec: SubtaskSpec, supplier: String?): TrackerIssue {
        val projectKey = parentKey.substringBefore('-', missingDelimiterValue = "")
        val project = bootstrapper.listProjects().firstOrNull { it.key == projectKey }
            ?: throw YouTrackApiException("Onbekend project voor parent '$parentKey'.")
        bootstrapper.ensureProjectSchema(project)

        // 1) Issue aanmaken (project + summary + description).
        val createBody = buildMap<String, Any?> {
            put("project", mapOf("id" to project.id))
            put("summary", spec.title)
            spec.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        }
        val created = transport.sendJson("POST", "/api/issues", listOf("fields" to "idReadable"), body = createBody)
        val subtaskKey = created.path("idReadable").asText()

        // 2) customFields zetten: Type = Task (kaart, geen swimlane), Subtask Type,
        //    optioneel model/effort. GEEN tag, GEEN Subtask Phase (coördinator start die).
        val customFields = buildList {
            add(mapper.enumFieldValue("Type", "Task"))
            add(mapper.enumFieldValue(TrackerField.SUBTASK_TYPE.displayName, spec.type.trackerValue))
            supplier?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
                ?.let { add(mapper.enumFieldValue("AI-supplier", it)) }
            spec.model?.takeIf { it.isNotBlank() }?.let { add(mapper.enumFieldValue(TrackerField.AI_MODEL.displayName, it)) }
            spec.effort?.takeIf { it.isNotBlank() }?.let { add(mapper.enumFieldValue(TrackerField.AI_REASONING_EFFORT.displayName, it)) }
        }
        transport.sendJson(
            "POST",
            "/api/issues/${subtaskKey.pathEncoded()}",
            listOf("fields" to "idReadable,customFields(name,value(name))"),
            body = mapOf("customFields" to customFields),
        )

        // 3) Subtask-link leggen: parent "parent for" subtask.
        transport.sendJson(
            "POST",
            "/api/commands",
            body = mapOf(
                "query" to "parent for $subtaskKey",
                "issues" to listOf(mapOf("idReadable" to parentKey)),
            ),
        )

        return getIssue(subtaskKey)
    }

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
        val project = bootstrapper.listProjects().firstOrNull { it.key == projectKey }
            ?: throw YouTrackApiException("Onbekend YouTrack-project: $projectKey")
        bootstrapper.ensureProjectSchema(project)

        // 1) Issue aanmaken (project + summary + description).
        val createBody = buildMap<String, Any?> {
            put("project", mapOf("id" to project.id))
            put("summary", title)
            description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        }
        val created = transport.sendJson("POST", "/api/issues", listOf("fields" to "idReadable"), body = createBody)
        val storyKey = created.path("idReadable").asText()

        // 2) customFields: Type = User Story (geen Task → STORY), optioneel Repo/AI-supplier en
        //    — bij start — meteen Story Phase = start.
        val customFields = buildList {
            add(mapper.enumFieldValue("Type", "User Story"))
            repo?.takeIf { it.isNotBlank() }?.let {
                add(
                    mapOf(
                        "name" to TrackerField.REPO.displayName,
                        "\$type" to "MultiEnumIssueCustomField",
                        "value" to listOf(mapOf("name" to it)),
                    ),
                )
            }
            aiSupplier?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
                ?.let { add(mapper.enumFieldValue("AI-supplier", it)) }
            aiModel?.takeIf { it.isNotBlank() }
                ?.let { add(mapper.enumFieldValue(TrackerField.AI_MODEL.displayName, it)) }
            // SF-335 — Silent atomair in dezelfde update als Story Phase, zodat 'silent' al staat
            // vóórdat de orchestrator de story (via phase=start) oppakt.
            if (silent) {
                add(mapper.enumFieldValue(TrackerField.SILENT.displayName, "true"))
            }
            if (start) {
                add(mapper.enumFieldValue(TrackerField.STORY_PHASE.displayName, "start"))
            }
        }
        transport.sendJson(
            "POST",
            "/api/issues/${storyKey.pathEncoded()}",
            listOf("fields" to "idReadable,customFields(name,value(name))"),
            body = mapOf("customFields" to customFields),
        )

        return getIssue(storyKey)
    }

    override fun existingSubtaskTitles(parentKey: String): Set<String> {
        val root = transport.sendJson(
            "GET",
            "/api/issues/${parentKey.pathEncoded()}",
            listOf("fields" to "links(direction,linkType(name),issues(summary))"),
        )
        return root.path("links")
            .filter {
                it.path("linkType").path("name").asText() == "Subtask" &&
                    it.path("direction").asText() == "OUTWARD"
            }
            .flatMap { it.path("issues") }
            .mapNotNull { it.path("summary").asText().takeIf { s -> s.isNotBlank() } }
            .toSet()
    }

    override fun parentStoryKey(subtaskKey: String): String? {
        val root = transport.sendJson(
            "GET",
            "/api/issues/${subtaskKey.pathEncoded()}",
            listOf("fields" to "links(direction,linkType(name),issues(idReadable))"),
        )
        return root.path("links")
            .filter {
                it.path("linkType").path("name").asText() == "Subtask" &&
                    it.path("direction").asText() == "INWARD"
            }
            .flatMap { it.path("issues") }
            .firstNotNullOfOrNull { it.path("idReadable").asText().takeIf { k -> k.isNotBlank() } }
    }

    override fun subtasksOf(parentKey: String): List<TrackerIssue> {
        // Perf: één call die de gelinkte issues meteen met de volledige veldenset meegeeft,
        // i.p.v. eerst de keys ophalen en dan per subtaak een getIssue doen (N+1).
        val root = transport.sendJson(
            "GET",
            "/api/issues/${parentKey.pathEncoded()}",
            listOf("fields" to "links(direction,linkType(name),issues(${YouTrackIssueMapper.issueFields}))"),
        )
        return root.path("links")
            .filter {
                it.path("linkType").path("name").asText() == "Subtask" &&
                    it.path("direction").asText() == "OUTWARD"
            }
            .flatMap { it.path("issues") }
            .map { mapper.mapIssue(it) }
            // Aanmaakvolgorde = oplopend issue-nummer (planner maakt ze sequentieel aan).
            .sortedBy { it.key.substringAfterLast('-').toIntOrNull() ?: Int.MAX_VALUE }
    }

    override fun addTag(issueKey: String, tag: String) {
        transport.sendJson(
            "POST",
            "/api/commands",
            body = mapOf(
                "query" to "add tag $tag",
                "issues" to listOf(mapOf("idReadable" to issueKey)),
            ),
        )
    }

    override fun removeTag(issueKey: String, tag: String) {
        transport.sendJson(
            "POST",
            "/api/commands",
            body = mapOf(
                "query" to "remove tag $tag",
                "issues" to listOf(mapOf("idReadable" to issueKey)),
            ),
        )
    }

    override fun updateIssueSummary(issueKey: String, summary: String) {
        transport.sendJson(
            "POST",
            "/api/issues/${issueKey.pathEncoded()}",
            listOf("fields" to "idReadable,summary"),
            body = mapOf("summary" to summary),
        )
    }

    override fun updateIssueDescription(issueKey: String, description: String) {
        transport.sendJson(
            "POST",
            "/api/issues/${issueKey.pathEncoded()}",
            listOf("fields" to "idReadable,description"),
            body = mapOf("description" to description),
        )
    }

    override fun transitionIssue(issueKey: String, statusName: String) {
        // Best-effort: de Agile-boards van de projecten sturen hun kolommen op het
        // `State`-veld (Open / In Progress / To Verify / Done). Een project zonder dat
        // veld of zonder de waarde negeert het commando; de orchestrator-flow (tag- en
        // fase-gedreven) mag er niet op breken. Meerwoordige waarden ("In Progress")
        // gaan hier zónder accolades — YouTrack parseert de rest als de State-waarde.
        runCatching {
            transport.sendJson(
                "POST",
                "/api/commands",
                body = mapOf(
                    "query" to "State $statusName",
                    "issues" to listOf(mapOf("idReadable" to issueKey)),
                ),
            )
        }.onFailure { ex ->
            logger.info("State-transitie '{}' voor {} overgeslagen (geen State-veld?): {}", statusName, issueKey, ex.message)
        }
    }

    override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment {
        return postComment(issueKey, "${role.commentPrefix} $message")
    }

    override fun postComment(issueKey: String, message: String): TrackerComment {
        val root = transport.sendJson(
            "POST",
            "/api/issues/${issueKey.pathEncoded()}/comments",
            listOf("fields" to YouTrackIssueMapper.commentFields),
            body = mapOf("text" to message),
        )
        return mapper.mapComment(root)
    }

    override fun listIssueAttachments(issueKey: String): List<TrackerAttachment> {
        val root = transport.sendJson(
            "GET",
            "/api/issues/${issueKey.pathEncoded()}/attachments",
            listOf("fields" to YouTrackIssueMapper.attachmentFields, "\$top" to "1000"),
        )
        return root.map { mapper.mapAttachment(it) }
    }

    override fun uploadIssueAttachment(issueKey: String, name: String, mimeType: String, bytes: ByteArray): TrackerAttachment {
        val root = transport.sendJson(
            request = transport.multipartRequest(
                path = "/api/issues/${issueKey.pathEncoded()}/attachments",
                query = listOf("fields" to YouTrackIssueMapper.attachmentFields),
                fieldName = "upload",
                fileName = name,
                mimeType = mimeType,
                bytes = bytes,
            ),
        )
        return mapper.mapAttachment(root.firstOrNull() ?: root)
    }

    override fun downloadAttachmentBytes(attachment: TrackerAttachment): ByteArray? {
        val url = attachment.url?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            transport.downloadBytes(url)
        }.onFailure {
            logger.warn("Kon attachment {} niet downloaden: {}", attachment.name, it.message)
        }.getOrNull()
    }

    override fun deleteIssueAttachment(issueKey: String, attachmentId: String) {
        transport.sendJson(
            "DELETE",
            "/api/issues/${issueKey.pathEncoded()}/attachments/${attachmentId.pathEncoded()}",
            allowedStatuses = setOf(200, 204),
        )
    }

    override fun hasProcessedCommentMarker(issueKey: String, commentId: String, role: AgentRole): Boolean {
        val response = transport.send(
            transport.request(
                "GET",
                "/api/issues/${issueKey.pathEncoded()}/comments/${commentId.pathEncoded()}/reactions",
                listOf("fields" to "id,reaction,author(login)", "\$top" to "100"),
            ),
        )
        return when (response.status) {
            in YouTrackHttpTransport.successStatuses -> objectMapper.readTree(response.body).any { it.path("reaction").asText() == PROCESSED_REACTION }
            in fallbackMarkerStatuses -> false
            else -> throw YouTrackApiException("YouTrack reaction lookup failed with status ${response.status}.")
        }
    }

    override fun markCommentProcessed(issueKey: String, commentId: String, role: AgentRole): Boolean {
        val response = transport.send(
            transport.request(
                "POST",
                "/api/issues/${issueKey.pathEncoded()}/comments/${commentId.pathEncoded()}/reactions",
                listOf("fields" to "id,reaction,author(login)"),
                body = mapOf("reaction" to PROCESSED_REACTION),
            ),
        )
        return when (response.status) {
            in YouTrackHttpTransport.successStatuses, 409 -> true
            in fallbackMarkerStatuses -> false
            else -> throw YouTrackApiException("YouTrack reaction update failed with status ${response.status}.")
        }
    }

    override fun deleteAgentComments(issueKey: String): Int {
        val issue = getIssue(issueKey)
        val agentCommentIds = issue.comments.filter { it.isAgentComment }.map { it.id }
        agentCommentIds.forEach { commentId ->
            transport.sendJson(
                "DELETE",
                "/api/issues/${issueKey.pathEncoded()}/comments/${commentId.pathEncoded()}",
                allowedStatuses = setOf(200, 204),
            )
        }
        return agentCommentIds.size
    }

    override fun deleteIssue(issueKey: String) {
        transport.sendJson(
            "DELETE",
            "/api/issues/${issueKey.pathEncoded()}",
            allowedStatuses = setOf(200, 204),
        )
    }

    companion object {
        private const val PROCESSED_REACTION = "eyes"
        private val fallbackMarkerStatuses = setOf(400, 403, 404, 405, 410)
        /** Aantal projecten dat findWorkIssues() gelijktijdig bevraagt (zie [projectFetchExecutor]). */
        private const val PROJECT_FETCH_WORKER_COUNT = 8
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
