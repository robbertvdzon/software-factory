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
        appendLine()
        appendLine(commonRules(request))
        appendLine()
        appendLine(roleRules(request.role, request.questionsAllowed))
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
            set<JsonNode>("phase", stringSchema())
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
            set<JsonNode>("required", objectMapper.valueToTree(listOf("phase", "outcome", "summaryText")))
            set<JsonNode>("properties", properties)
            put("additionalProperties", false)
        }
    }

    private fun commonRules(request: AgentDispatchRequest): String = buildString {
        appendLine("## Algemene regels")
        appendLine("- Werk zelfstandig en gebruik geen secrets in output.")
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

    private fun roleRules(role: AgentRole, questionsAllowed: Boolean): String = when (role) {
        AgentRole.REFINER -> """
            ## Refiner
            Schrijf geen code. Maak de story zelfstandig implementeerbaar. Zet het definitieve voorstel in
            `summaryText` tussen `<!-- proposed-description:start -->` en `<!-- proposed-description:end -->`.
            Gebruik fase `refined`, of ${questionPhase("refined-with-questions", questionsAllowed)}.
        """.trimIndent()
        AgentRole.PLANNER -> """
            ## Planner
            Schrijf geen code. Maak normaal één development-, één test- en één summary-subtaak.
            Tests schrijven hoort bij development. Gebruik fase `planned`, of
            ${questionPhase("planned-with-questions", questionsAllowed)} en vul `subtasks`.
        """.trimIndent()
        AgentRole.DEVELOPER -> """
            ## Developer
            Implementeer de volledige opdracht inclusief tests. Gebruik fase `developed`, of
            ${questionPhase("developed-with-questions", questionsAllowed)}. Laat alle bestandswijzigingen
            in de worktree; de Runtime-worker verifieert, commit en pusht pas na jouw run.
        """.trimIndent()
        AgentRole.REVIEWER -> """
            ## Reviewer
            Wijzig geen implementatie. Review de volledige storydiff en rapporteer alle blockers in één pass.
            Gebruik `reviewed`, `review-rejected`, of ${questionPhase("reviewed-with-questions", questionsAllowed)}.
        """.trimIndent()
        AgentRole.TESTER -> """
            ## Tester
            Verifieer gedrag; schrijf geen code of tests. Gebruik `tested`, `test-rejected`, of
            ${questionPhase("tested-with-questions", questionsAllowed)}. Maak bij browser- of
            previewtests screenshots. Bundel uitsluitend PNG-, JPEG- of WebP-screenshots als ZIP
            op exact `/job/output/artifacts/screenshots`; laat dit optionele artifact weg wanneer
            er geen screenshots zijn.
        """.trimIndent()
        AgentRole.SUMMARIZER -> """
            ## Summarizer
            Vat uitsluitend het werkelijk opgeleverde resultaat samen. Gebruik fase `summarized` en vul
            `descriptionSummary` en `shortDescriptionSummary`, of
            ${questionPhase("summary-with-questions", questionsAllowed)}.
        """.trimIndent()
        AgentRole.DOCUMENTER -> """
            ## Documenter
            Werk alleen werkelijk geraakte documentatie bij. Geen impact is toegestaan. Gebruik `documented`,
            of ${questionPhase("documentation-with-questions", questionsAllowed)}.
        """.trimIndent()
        AgentRole.AUDITOR -> """
            ## Auditor
            Voer een read-only audit uit en stel hoogstens één vervolgstory voor. Gebruik `audited`, of
            ${questionPhase("audit-questions", questionsAllowed)}.
        """.trimIndent()
        else -> error("Role ${role.markerKeyPart} is not an Agent Runtime role")
    }

    private fun questionPhase(phase: String, allowed: Boolean): String =
        if (allowed) "`$phase` met concrete vragen" else "geen vragenfase"

    private fun stringSchema(maxLength: Int = 4_000): JsonNode =
        objectMapper.createObjectNode().put("type", "string").put("maxLength", maxLength)

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
        set<JsonNode>("required", objectMapper.valueToTree(listOf("type", "title")))
        set<JsonNode>("properties", objectMapper.createObjectNode().apply {
            set<JsonNode>("type", stringSchema())
            set<JsonNode>("title", stringSchema())
            set<JsonNode>("description", stringSchema(maxLength = 20_000))
        })
        put("additionalProperties", false)
    }

    companion object {
        private val REPOSITORY_ROLES = setOf(
            AgentRole.DEVELOPER,
            AgentRole.REVIEWER,
            AgentRole.TESTER,
            AgentRole.DOCUMENTER,
            AgentRole.AUDITOR,
        )
    }
}
