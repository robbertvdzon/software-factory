package nl.vdzon.softwarefactory.runtime.v2

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AgentDispatchRequest
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import org.springframework.stereotype.Component

@Component
class AgentRuntimeInstructionFactory(
    private val objectMapper: ObjectMapper,
    private val knowledge: KnowledgeApi,
) {
    fun instruction(request: AgentDispatchRequest): String = buildString {
        appendLine("# Software Factory-opdracht")
        appendLine()
        appendLine("Story: `${request.storyKey}`")
        appendLine("Rol: `${request.role.markerKeyPart}`")
        appendLine("Fase: `${request.phase}`")
        request.baseBranch?.let { appendLine("Base branch: `$it`") }
        request.branchName?.let { appendLine("Storybranch: `$it`") }
        request.previewUrl?.let { appendLine("Preview: `$it`") }
        request.previewNamespace?.let { appendLine("Preview namespace: `$it`") }
        request.aiEffort?.takeIf(String::isNotBlank)?.let { appendLine("Gevraagde effort: `$it`") }
        appendLine()
        appendLine(commonRules(request))
        appendLine()
        appendLine(AgentRuntimeRoleInstructions.forRole(request.role, request.questionsAllowed))
        request.developerLoopbackReason?.takeIf(String::isNotBlank)?.let {
            appendLine()
            appendLine("## Developer-loopback")
            appendLine(it)
        }
        request.trackerContext?.takeIf(String::isNotBlank)?.let {
            appendLine()
            appendLine(it)
        }
        request.prCommentContext?.takeIf(String::isNotBlank)?.let {
            appendLine()
            appendLine(it)
        }
        if (request.inputAttachments.isNotEmpty()) {
            appendLine()
            appendLine("## Aangeleverde bestanden")
            appendLine("Lees deze bestanden vanuit de getoonde Runtime-objectpaden:")
            request.inputAttachments.forEach { attachment ->
                appendLine(
                    "- `${attachment.originalFilename}` (${attachment.mimeType}): " +
                        "`/job/input/objects/${attachment.logicalName}/content`",
                )
            }
        }
        val tips = runCatching { knowledge.find(request.targetRepo, request.role.markerKeyPart) }
            .getOrDefault(emptyList())
            .sortedByDescending { it.updatedAt }
            .take(30)
        if (tips.isNotEmpty()) {
            appendLine()
            appendLine("## Relevante agenttips")
            tips.forEach { appendLine("- ${it.category}/${it.key}: ${it.content.trim()}") }
        }
        appendLine()
        appendLine("Geef uitsluitend één JSON-object terug dat aan het aangeleverde resultaatschema voldoet.")
    }.trim()

    fun resultSchema(role: AgentRole): JsonNode {
        val properties = objectMapper.createObjectNode().apply {
            set<JsonNode>("phase", phaseSchema(role))
            set<JsonNode>("outcome", stringSchema())
            set<JsonNode>("summaryText", stringSchema(maxLength = 200_000))
            set<JsonNode>("questions", arraySchema(stringSchema(maxLength = 4_000), 50))
            set<JsonNode>("knowledgeUpdates", arraySchema(knowledgeUpdateSchema(), 100))
            if (role == AgentRole.PLANNER) set<JsonNode>("subtasks", arraySchema(subtaskSchema(), 100))
            if (role == AgentRole.SUMMARIZER) {
                set<JsonNode>("descriptionSummary", stringSchema(maxLength = 20_000))
                set<JsonNode>("shortDescriptionSummary", stringSchema(maxLength = 5_000))
            }
            if (role == AgentRole.AUDITOR) {
                set<JsonNode>("auditScore", numberSchema())
                set<JsonNode>("auditScoreLabel", stringSchema())
                set<JsonNode>("auditReportMarkdown", stringSchema(maxLength = 500_000))
                set<JsonNode>("auditFindingsMarkdown", stringSchema(maxLength = 500_000))
                set<JsonNode>("proposedStoryTitle", stringSchema(maxLength = 500))
                set<JsonNode>("proposedStoryDescription", stringSchema(maxLength = 100_000))
            }
        }
        return objectMapper.createObjectNode().apply {
            put("type", "object")
            set<JsonNode>("properties", properties)
            // OpenAI strict structured output vereist dat iedere gedeclareerde property ook in
            // `required` staat. Rollen waarvoor een veld inhoudelijk niet van toepassing is geven
            // daarom een lege array/string terug; de mapper houdt zijn bestaande defaults.
            set<JsonNode>("required", objectMapper.valueToTree(properties.fieldNames().asSequence().toList()))
            put("additionalProperties", false)
        }
    }

    private fun commonRules(request: AgentDispatchRequest): String = buildString {
        appendLine("## Algemene regels")
        appendLine("- Werk zelfstandig en gebruik geen secrets in output.")
        appendLine("- Deze job is één run. Wacht iedere gestarte achtergrondtaak af voordat je antwoordt.")
        appendLine("- Antwoorden uit relevante issue-comments zijn leidend wanneer ze botsen met oudere context.")
        appendLine("- Zet herbruikbare nieuwe kennis in `knowledgeUpdates`; gebruik een lege lijst als er niets is.")
        appendLine("- Vul altijd `phase`, `outcome` en een concrete `summaryText` in volgens het resultaatschema.")
        if (request.role in REPOSITORY_ROLES) {
            appendLine("- Werk uitsluitend in de door Agent Runtime voorbereide checkout.")
            appendLine("- Laat relevante tests groen achter.")
            appendLine("- Voer geen checkout, branch, commit, push, PR, merge of credentialinspectie uit.")
            appendLine("- Alleen read-only Gitinspectie zoals status, diff, log en show is toegestaan.")
        }
        if (!request.questionsAllowed) {
            appendLine("- Vragen zijn uitgeschakeld: maak de veiligste redelijke aanname en rapporteer geen vragenfase.")
        }
    }

    private fun stringSchema(maxLength: Int = 4_000): JsonNode =
        objectMapper.createObjectNode().put("type", "string").put("maxLength", maxLength)

    private fun phaseSchema(role: AgentRole): JsonNode = objectMapper.createObjectNode().apply {
        put("type", "string")
        set<JsonNode>("enum", objectMapper.valueToTree(PHASES.getValue(role)))
    }

    private fun numberSchema(): JsonNode = objectMapper.createObjectNode().put("type", "number")

    private fun arraySchema(items: JsonNode, maxItems: Int): JsonNode = objectMapper.createObjectNode().apply {
        put("type", "array")
        put("maxItems", maxItems)
        set<JsonNode>("items", items)
    }

    private fun knowledgeUpdateSchema(): JsonNode = objectMapper.createObjectNode().apply {
        put("type", "object")
        set<JsonNode>("required", objectMapper.valueToTree(listOf("category", "key", "content")))
        set<JsonNode>("properties", objectMapper.createObjectNode().apply {
            set<JsonNode>("category", stringSchema())
            set<JsonNode>("key", stringSchema())
            set<JsonNode>("content", stringSchema(maxLength = 20_000))
        })
        put("additionalProperties", false)
    }

    private fun subtaskSchema(): JsonNode = objectMapper.createObjectNode().apply {
        put("type", "object")
        set<JsonNode>("required", objectMapper.valueToTree(listOf("type", "title", "description")))
        set<JsonNode>("properties", objectMapper.createObjectNode().apply {
            set<JsonNode>("type", stringSchema())
            set<JsonNode>("title", stringSchema())
            set<JsonNode>("description", stringSchema(maxLength = 20_000))
        })
        put("additionalProperties", false)
    }

    companion object {
        private val PHASES = mapOf(
            AgentRole.REFINER to listOf("refined", "refined-with-questions"),
            AgentRole.PLANNER to listOf("planned", "planned-with-questions"),
            AgentRole.DEVELOPER to listOf("developed", "developed-with-questions"),
            AgentRole.REVIEWER to listOf("reviewed", "review-rejected", "reviewed-with-questions"),
            AgentRole.TESTER to listOf("tested", "test-rejected", "tested-with-questions"),
            AgentRole.SUMMARIZER to listOf("summarized", "summary-with-questions"),
            AgentRole.DOCUMENTER to listOf("documented", "documentation-with-questions"),
            AgentRole.AUDITOR to listOf("audited", "audit-questions"),
        )
        private val REPOSITORY_ROLES = setOf(
            AgentRole.DEVELOPER,
            AgentRole.REVIEWER,
            AgentRole.TESTER,
            AgentRole.DOCUMENTER,
            AgentRole.AUDITOR,
        )
    }
}
