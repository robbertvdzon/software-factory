package nl.vdzon.softwarefactory.youtrack.clients

import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.core.AiRouting
import nl.vdzon.softwarefactory.core.TrackerProject
import nl.vdzon.softwarefactory.core.YouTrackApiException
import org.slf4j.LoggerFactory

/**
 * Zorgt (eenmalig per project, per proces) dat het Software Factory-schema in
 * YouTrack staat: global custom fields, project-koppelingen en bundle-waarden.
 * Idempotent en thread-safe; de cache van gebootstrapte project-keys voorkomt
 * herhaalde admin-calls per issue-lookup.
 */
internal class YouTrackSchemaBootstrapper(
    private val transport: YouTrackHttpTransport,
    private val projectRepoResolver: ProjectRepoResolver,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val bootstrappedProjectKeys = mutableSetOf<String>()
    private val schemaLock = Any()

    fun isBootstrapped(projectKey: String): Boolean = synchronized(schemaLock) {
        projectKey in bootstrappedProjectKeys
    }

    /**
     * Bootstrap voor een los issue: is het project al gebootstrapt, dan slaan we óók de
     * `listProjects()`-call over (die haalde vóórheen bij élke getIssue de volledige
     * projectlijst op — puur om aan een TrackerProject voor ensureProjectSchema te komen).
     */
    fun ensureProjectSchemaFor(projectKey: String) {
        if (isBootstrapped(projectKey)) {
            return
        }
        listProjects().firstOrNull { it.key == projectKey }?.let { ensureProjectSchema(it) }
    }

    fun listProjects(): List<TrackerProject> {
        val root = transport.sendJson(
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

    fun ensureProjectSchema(project: TrackerProject) = synchronized(schemaLock) {
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

    private fun loadGlobalFields(): Map<String, CustomFieldDefinition> {
        val root = transport.sendJson(
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
        val root = transport.sendJson(
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
            allowedStatuses = YouTrackHttpTransport.successStatuses + 400,
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
        val root = transport.sendJson(
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
        val root = transport.sendJson(
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
        val response = transport.send(
            transport.request(
                "POST",
                "/api/admin/projects/${projectId.pathEncoded()}/customFields/${projectField.id.pathEncoded()}/bundle/values",
                listOf("fields" to "id,name"),
                body = mapOf("name" to value),
            ),
        )
        if (response.status !in YouTrackHttpTransport.successStatuses && response.status != 409) {
            throw YouTrackApiException(
                "Could not add value '$value' to YouTrack field '${projectField.name}': HTTP ${response.status} ${response.body.take(300)}",
            )
        }
    }

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

    companion object {
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
            // documentation-stap (SF-213): geen reject-tak.
            "documenting", "documented", "documentation-with-questions",
            "documentation-questions-answered", "documentation-approved",
            "awaiting-human", "manual-action-done",
            // manual-approve-poort (SF-192): geen agent, wel echte Subtask Phase-waarden.
            "manual-approve-needed", "manually-approved", "manually-not-approved",
            // merge/deploy-fasen (SF-154): geen agent, maar wel echte Subtask Phase-waarden.
            "merging", "merge-approved",
            "deploying", "deploy-approved", "deploy-failed",
        )

        private val subtaskTypeValues = listOf("development", "review", "test", "manual", "manual-approve", "summary", "documentation", "merge", "deploy")

        private val reasoningEffortValues = listOf("low", "medium", "high")

        // Alle bekende model-ids (bron: AiRouting.MODELS_BY_SUPPLIER) — nieuwe modelversie
        // hoeft alleen daar toegevoegd te worden.
        private val aiModelValues = AiRouting.ALL_MODEL_IDS

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
            // SF-335 — Silent: enum-boolean analoog aan Paused; gegarandeerd aangemaakt via schema-bootstrap.
            FieldSpec("Silent", "enum[1]", "EnumProjectCustomField", values = listOf("false", "true")),
            FieldSpec("Error", "text", "TextProjectCustomField"),
        )
    }
}
