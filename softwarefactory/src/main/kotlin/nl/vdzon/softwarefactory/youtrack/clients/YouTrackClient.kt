package nl.vdzon.softwarefactory.youtrack.clients

import nl.vdzon.softwarefactory.youtrack.*
import nl.vdzon.softwarefactory.core.*
import nl.vdzon.softwarefactory.core.TrackerCommentParser
import nl.vdzon.softwarefactory.support.CallMetrics
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
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
import java.util.UUID
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@Component
class YouTrackClient(
    private val factorySecrets: FactorySecrets,
    private val projectRepoResolver: ProjectRepoResolver,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
    private val httpClient: HttpClient = defaultHttpClient(),
) : YouTrackApi {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val baseUrl = factorySecrets.youTrackBaseUrl.trimEnd('/')
    private val authorizationHeader = "Bearer ${factorySecrets.youTrackToken}"
    private val bootstrappedProjectKeys = mutableSetOf<String>()
    private val schemaLock = Any()

    override fun ensureConfiguredProjects(): List<TrackerProject> {
        // De repo wordt niet langer per project bepaald, maar per story via het `Project`-veld
        // (zie ProjectRepoResolver). Welke YouTrack-projecten gescand worden, komt puur uit
        // SF_YOUTRACK_PROJECTS; leeg = alle niet-gearchiveerde projecten.
        val allProjects = listProjects()
        val projects = allProjects.filter { project ->
            factorySecrets.youTrackProjects.isEmpty() || project.key in factorySecrets.youTrackProjects
        }
        if (projects.isEmpty()) {
            val available = allProjects.joinToString(", ") { it.key }.ifBlank { "<none>" }
            throw YouTrackApiException(
                "No Software Factory YouTrack projects configured. Set SF_YOUTRACK_PROJECTS (or leave it empty to scan all). Available projects: $available",
            )
        }
        projects.forEach { project -> ensureProjectSchema(project) }
        return projects
    }

    override fun findWorkIssues(maxResults: Int): List<TrackerIssue> {
        val projects = ensureConfiguredProjects()
        return projects.flatMap { project ->
            // Geen work-tags meer: alle issues van het project worden kandidaat. De fase-gate
            // in de orchestrator (lege fase = niet starten; `start` = oppakken) bepaalt de rest.
            // Recentst-bijgewerkt eerst, zodat lopende/zojuist-gestarte issues binnen de cap vallen.
            val root = sendJson(
                "GET",
                "/api/issues",
                listOf(
                    "query" to "project: ${project.key} sort by: updated desc",
                    "\$top" to maxResults.coerceAtLeast(1).toString(),
                    "fields" to issueFields,
                ),
            )
            root.map { mapIssue(it) }
                .filter { issue -> issue.fields.aiSupplier?.lowercase() !in setOf(null, "", "none") }
        }
            .distinctBy { it.key }
            .sortedBy { it.key }
    }

    override fun getIssue(issueKey: String): TrackerIssue {
        val projectKey = issueKey.substringBefore('-', missingDelimiterValue = "")
        val project = listProjects().firstOrNull { it.key == projectKey }
        project?.let { ensureProjectSchema(it) }
        val root = sendJson("GET", "/api/issues/${issueKey.pathEncoded()}", listOf("fields" to issueFields))
        return mapIssue(root)
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

    override fun createSubtask(parentKey: String, spec: SubtaskSpec, supplier: String?): TrackerIssue {
        val projectKey = parentKey.substringBefore('-', missingDelimiterValue = "")
        val project = listProjects().firstOrNull { it.key == projectKey }
            ?: throw YouTrackApiException("Onbekend project voor parent '$parentKey'.")
        ensureProjectSchema(project)

        // 1) Issue aanmaken (project + summary + description).
        val createBody = buildMap<String, Any?> {
            put("project", mapOf("id" to project.id))
            put("summary", spec.title)
            spec.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        }
        val created = sendJson("POST", "/api/issues", listOf("fields" to "idReadable"), body = createBody)
        val subtaskKey = created.path("idReadable").asText()

        // 2) customFields zetten: Type = Task (kaart, geen swimlane), Subtask Type,
        //    optioneel model/effort. GEEN tag, GEEN Subtask Phase (coördinator start die).
        val customFields = buildList {
            add(enumFieldValue("Type", "Task"))
            add(enumFieldValue(TrackerField.SUBTASK_TYPE.displayName, spec.type.trackerValue))
            supplier?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
                ?.let { add(enumFieldValue("AI-supplier", it)) }
            spec.model?.takeIf { it.isNotBlank() }?.let { add(enumFieldValue(TrackerField.AI_MODEL.displayName, it)) }
            spec.effort?.takeIf { it.isNotBlank() }?.let { add(enumFieldValue(TrackerField.AI_REASONING_EFFORT.displayName, it)) }
        }
        sendJson(
            "POST",
            "/api/issues/${subtaskKey.pathEncoded()}",
            listOf("fields" to "idReadable,customFields(name,value(name))"),
            body = mapOf("customFields" to customFields),
        )

        // 3) Subtask-link leggen: parent "parent for" subtask.
        sendJson(
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
    ): TrackerIssue {
        val project = listProjects().firstOrNull { it.key == projectKey }
            ?: throw YouTrackApiException("Onbekend YouTrack-project: $projectKey")
        ensureProjectSchema(project)

        // 1) Issue aanmaken (project + summary + description).
        val createBody = buildMap<String, Any?> {
            put("project", mapOf("id" to project.id))
            put("summary", title)
            description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        }
        val created = sendJson("POST", "/api/issues", listOf("fields" to "idReadable"), body = createBody)
        val storyKey = created.path("idReadable").asText()

        // 2) customFields: Type = User Story (geen Task → STORY), optioneel Repo/AI-supplier en
        //    — bij start — meteen Story Phase = start.
        val customFields = buildList {
            add(enumFieldValue("Type", "User Story"))
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
                ?.let { add(enumFieldValue("AI-supplier", it)) }
            aiModel?.takeIf { it.isNotBlank() }
                ?.let { add(enumFieldValue(TrackerField.AI_MODEL.displayName, it)) }
            if (start) {
                add(enumFieldValue(TrackerField.STORY_PHASE.displayName, "start"))
            }
        }
        sendJson(
            "POST",
            "/api/issues/${storyKey.pathEncoded()}",
            listOf("fields" to "idReadable,customFields(name,value(name))"),
            body = mapOf("customFields" to customFields),
        )

        return getIssue(storyKey)
    }

    override fun existingSubtaskTitles(parentKey: String): Set<String> {
        val root = sendJson(
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
        val root = sendJson(
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
        val root = sendJson(
            "GET",
            "/api/issues/${parentKey.pathEncoded()}",
            listOf("fields" to "links(direction,linkType(name),issues(idReadable))"),
        )
        return root.path("links")
            .filter {
                it.path("linkType").path("name").asText() == "Subtask" &&
                    it.path("direction").asText() == "OUTWARD"
            }
            .flatMap { it.path("issues") }
            .mapNotNull { it.path("idReadable").asText().takeIf { k -> k.isNotBlank() } }
            // Aanmaakvolgorde = oplopend issue-nummer (planner maakt ze sequentieel aan).
            .sortedBy { it.substringAfterLast('-').toIntOrNull() ?: Int.MAX_VALUE }
            .map { getIssue(it) }
    }

    override fun addTag(issueKey: String, tag: String) {
        sendJson(
            "POST",
            "/api/commands",
            body = mapOf(
                "query" to "add tag $tag",
                "issues" to listOf(mapOf("idReadable" to issueKey)),
            ),
        )
    }

    override fun removeTag(issueKey: String, tag: String) {
        sendJson(
            "POST",
            "/api/commands",
            body = mapOf(
                "query" to "remove tag $tag",
                "issues" to listOf(mapOf("idReadable" to issueKey)),
            ),
        )
    }

    private fun enumFieldValue(name: String, value: String): Map<String, Any?> =
        mapOf(
            "name" to name,
            "\$type" to "SingleEnumIssueCustomField",
            "value" to mapOf("name" to value),
        )

    override fun updateIssueSummary(issueKey: String, summary: String) {
        sendJson(
            "POST",
            "/api/issues/${issueKey.pathEncoded()}",
            listOf("fields" to "idReadable,summary"),
            body = mapOf("summary" to summary),
        )
    }

    override fun updateIssueDescription(issueKey: String, description: String) {
        sendJson(
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
            sendJson(
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
        val root = sendJson(
            "POST",
            "/api/issues/${issueKey.pathEncoded()}/comments",
            listOf("fields" to commentFields),
            body = mapOf("text" to message),
        )
        return mapComment(root)
    }

    override fun listIssueAttachments(issueKey: String): List<TrackerAttachment> {
        val root = sendJson(
            "GET",
            "/api/issues/${issueKey.pathEncoded()}/attachments",
            listOf("fields" to attachmentFields, "\$top" to "1000"),
        )
        return root.map { mapAttachment(it) }
    }

    override fun uploadIssueAttachment(issueKey: String, name: String, mimeType: String, bytes: ByteArray): TrackerAttachment {
        val root = sendJson(
            request = multipartRequest(
                path = "/api/issues/${issueKey.pathEncoded()}/attachments",
                query = listOf("fields" to attachmentFields),
                fieldName = "upload",
                fileName = name,
                mimeType = mimeType,
                bytes = bytes,
            ),
        )
        return mapAttachment(root.firstOrNull() ?: root)
    }

    override fun deleteIssueAttachment(issueKey: String, attachmentId: String) {
        sendJson(
            "DELETE",
            "/api/issues/${issueKey.pathEncoded()}/attachments/${attachmentId.pathEncoded()}",
            allowedStatuses = setOf(200, 204),
        )
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

    override fun deleteIssue(issueKey: String) {
        sendJson(
            "DELETE",
            "/api/issues/${issueKey.pathEncoded()}",
            allowedStatuses = setOf(200, 204),
        )
    }

    private fun ensureProjectSchema(project: TrackerProject) = synchronized(schemaLock) {
        if (!bootstrappedProjectKeys.add(project.key)) {
            return@synchronized
        }

        // 'Repo' krijgt z'n enum-keuzes (projectnamen) dynamisch uit projects.yaml.
        val specs = factoryFieldSpecs.map { spec ->
            if (spec.name == "Repo") spec.copy(values = projectRepoResolver.projectNames()) else spec
        }

        val globalFields = loadGlobalFields().toMutableMap()
        specs.forEach { spec ->
            val existing = globalFields[spec.name]
            if (existing == null) {
                globalFields[spec.name] = createCustomField(spec)
            } else if (existing.fieldTypeId != spec.fieldTypeId) {
                throw YouTrackApiException(
                    "YouTrack custom field '${spec.name}' has type '${existing.fieldTypeId}', expected '${spec.fieldTypeId}'.",
                )
            }
        }

        val projectFields = loadProjectFields(project.id).toMutableMap()
        specs.forEach { spec ->
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

        // Geen Stage-veld meer vereist: werk wordt getriggerd door de tags
        // (`ai-refinement`/`ai-development`). Zo werkt elk board-template.
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
                )
            }
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
            allowedStatuses = successStatuses + 400,
        )
        // Idempotent: een global custom field is gedeeld over projecten. Bij een
        // herhaalde run (of meerdere projecten in één run) kan het veld al bestaan;
        // hergebruik het dan i.p.v. de boot te laten falen.
        val error = root.path("error").asText("")
        if (error.isNotBlank()) {
            if (error == "must-be-unique") {
                return loadGlobalFields()[spec.name]
                    ?: throw YouTrackApiException("Custom field '${spec.name}' bestaat al maar kon niet worden opgehaald.")
            }
            throw YouTrackApiException(
                "Could not create YouTrack custom field '${spec.name}': ${root.path("error_description").asText(error)}",
            )
        }
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

    private fun mapIssue(issue: JsonNode): TrackerIssue {
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

    private fun mapAttachment(attachment: JsonNode): TrackerAttachment =
        TrackerAttachment(
            id = attachment.path("id").asText(),
            name = attachment.path("name").asText(""),
            url = attachment.path("url").asText(null)?.takeIf { it.isNotBlank() },
            mimeType = attachment.path("mimeType").asText(null)?.takeIf { it.isNotBlank() },
            size = attachment.path("size").takeIf { it.isNumber }?.asLong(),
            created = attachment.path("created").takeIf { it.isNumber }?.asLong(),
        )

    private fun fieldUpdate(field: TrackerField, value: Any?): Map<String, Any?> =
        when (field) {
            TrackerField.AI_SUPPLIER,
            TrackerField.AUTO_APPROVE,
            TrackerField.AI_PHASE,
            TrackerField.PAUSED,
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

    private fun sendJson(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: Any? = null,
        allowedStatuses: Set<Int> = successStatuses,
    ): JsonNode {
        val response = send(request(method, path, query, body))
        return parseJsonResponse(response, method, path, allowedStatuses)
    }

    private fun sendJson(
        request: HttpRequest,
        allowedStatuses: Set<Int> = successStatuses,
    ): JsonNode {
        val response = send(request)
        return parseJsonResponse(response, request.method(), request.uri().path, allowedStatuses)
    }

    private fun parseJsonResponse(
        response: YouTrackResponse,
        method: String,
        path: String,
        allowedStatuses: Set<Int>,
    ): JsonNode {
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

    private fun multipartRequest(
        path: String,
        query: List<Pair<String, String>>,
        fieldName: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): HttpRequest {
        val boundary = "----software-factory-${UUID.randomUUID()}"
        val body = buildMultipartBody(boundary, fieldName, fileName, mimeType, bytes)
        return HttpRequest.newBuilder(URI.create(baseUrl + path + query.toQueryString()))
            .header("Authorization", authorizationHeader)
            .header("Accept", "application/json")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
    }

    private fun buildMultipartBody(
        boundary: String,
        fieldName: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): ByteArray {
        val header = """
            --$boundary
            Content-Disposition: form-data; name="$fieldName"; filename="${fileName.replace("\"", "")}"
            Content-Type: $mimeType

        """.trimIndent().replace("\n", "\r\n").toByteArray(StandardCharsets.UTF_8)
        val footer = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)
        return header + bytes + footer
    }

    private fun send(request: HttpRequest): YouTrackResponse =
        CallMetrics.measure("youtrack", "${request.method()} ${request.uri().path}") {
            try {
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                YouTrackResponse(response.statusCode(), response.body() ?: "")
            } catch (exception: Exception) {
                throw YouTrackApiException(
                    "YouTrack request failed: ${request.method()} ${request.uri().path}",
                    exception,
                )
            }
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
        /**
         * HttpClient die zowel de publieke CA's (default cacerts) als de
         * cluster-ingress-CA vertrouwt. Nodig om YouTrack via de directe
         * OpenShift-route (*.apps.sno.lab.vdzon.com, lab-self-signed) te
         * bereiken i.p.v. via de Cloudflare-tunnel — zónder publieke trust
         * te verliezen (Anthropic/GitHub-calls blijven werken).
         *
         * Het CA-bestand staat op het classpath: /certs/cluster-ingress-ca.crt.
         * Ontbreekt het, dan vallen we terug op de standaard-truststore.
         *
         * NB: het cert is van de ingress-operator en roteert; bij rotatie
         * moet cluster-ingress-ca.crt opnieuw geëxporteerd worden
         * (oc get configmap default-ingress-cert -n openshift-config-managed).
         */
        private fun defaultHttpClient(): HttpClient {
            val caStream = YouTrackClient::class.java.getResourceAsStream("/certs/cluster-ingress-ca.crt")
                ?: return HttpClient.newHttpClient()
            val caCerts = caStream.use { CertificateFactory.getInstance("X.509").generateCertificates(it) }

            val customTrustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                caCerts.forEachIndexed { index, cert -> setCertificateEntry("cluster-ingress-ca-$index", cert) }
            }
            val labTrustManager = trustManagerFrom(customTrustStore)
            val defaultTrustManager = trustManagerFrom(null) // null => JVM-default cacerts

            // Probeer eerst de publieke CA's; faalt dat, dan het lab-CA.
            val mergedTrustManager = object : X509TrustManager {
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    try {
                        defaultTrustManager.checkServerTrusted(chain, authType)
                    } catch (ignored: CertificateException) {
                        labTrustManager.checkServerTrusted(chain, authType)
                    }
                }

                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                    defaultTrustManager.checkClientTrusted(chain, authType)

                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    defaultTrustManager.acceptedIssuers + labTrustManager.acceptedIssuers
            }

            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(mergedTrustManager), null)
            }
            return HttpClient.newBuilder().sslContext(sslContext).build()
        }

        private fun trustManagerFrom(keyStore: KeyStore?): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(keyStore)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        private const val PROCESSED_REACTION = "eyes"
        private val successStatuses = (200..299).toSet()
        private val fallbackMarkerStatuses = setOf(400, 403, 404, 405, 410)
        private const val commentFields = "id,text,author(login,fullName),created,reactions(id,reaction,author(login))"
        private const val attachmentFields = "id,name,url,mimeType,size,created"
        private const val issueFields =
            "id,idReadable,summary,description,project(id,name,shortName,description)," +
                "customFields(name,value(id,name,presentation,text,localizedName))," +
                "tags(name)," +
                "comments($commentFields)"
        // v2: story-niveau lifecycle (refinement) — zie specs/v2-plan/fase-1.
        private val storyPhaseValues = listOf(
            "start",
            "refining",
            "refined-with-questions",
            "questions-answered",
            "refined",
            "refined-rejected",
            "refined-approved",
            "planning",
            "planned-with-questions",
            "planning-questions-answered",
            "planned",
            "planning-rejected",
            "planning-approved",
            "in-progress",
        )

        // v2: subtask-niveau — alle AI-stappen (developer/reviewer/tester/summary) + manual.
        private val subtaskPhaseValues = listOf(
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

        private val subtaskTypeValues = listOf("development", "review", "test", "manual", "summary")

        private val reasoningEffortValues = listOf("low", "medium", "high")

        // v2: alle model-ids die nu in de code staan (suppliers door elkaar). Openai/codex
        // hardcodet geen model; voeg dat zelf toe wanneer nodig.
        private val aiModelValues = listOf(
            "claude-haiku-4-5", "claude-sonnet-4-6", "claude-opus-4-7", "claude-opus-4-8",
            "gpt-4.1", "claude-haiku-4.5", "claude-sonnet-4.5", "claude-opus-4.5",
            "dummy-ai-client",
        )

        // 'Repo' is een multi-value enum (enum[*]): de keuzes (projectnamen) worden bij
        // schema-bootstrap dynamisch gevuld uit projects.yaml (zie ensureProjectSchema).
        private val factoryFieldSpecs = listOf(
            FieldSpec("Repo", "enum[*]", "EnumProjectCustomField"),
            FieldSpec("AI-supplier", "enum[1]", "EnumProjectCustomField", values = listOf("none", "mock", "claude", "openai", "copilot", "microsoft")),
            FieldSpec("Auto-approve", "enum[1]", "EnumProjectCustomField", values = listOf("off", "on")),
            FieldSpec("Story Phase", "enum[1]", "EnumProjectCustomField", values = storyPhaseValues),
            FieldSpec("Subtask Phase", "enum[1]", "EnumProjectCustomField", values = subtaskPhaseValues),
            FieldSpec("Subtask Type", "enum[1]", "EnumProjectCustomField", values = subtaskTypeValues),
            FieldSpec("AI Model", "enum[1]", "EnumProjectCustomField", values = aiModelValues),
            FieldSpec("AI Reasoning Effort", "enum[1]", "EnumProjectCustomField", values = reasoningEffortValues),
            FieldSpec("AI Max Developer Loopbacks", "integer", "SimpleProjectCustomField"),
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
