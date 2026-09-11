package nl.vdzon.softwarefactory.runtime.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AgentDispatchRequest
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class AgentRuntimeInstructionFactoryTest {
    private val factory = AgentRuntimeInstructionFactory(jacksonObjectMapper(), mock(KnowledgeApi::class.java))

    @Test
    fun `alle rolcontracten behouden hun kritieke gedragsregels`() {
        val expected = mapOf(
            AgentRole.REFINER to listOf("proposed-summary:start", "proposed-description:start"),
            AgentRole.PLANNER to listOf("precies drie subtaken", "Tests schrijven hoort in development"),
            AgentRole.DEVELOPER to listOf("Runtime-worker commit en pusht", "Samenvatting"),
            AgentRole.REVIEWER to listOf("volledige storydiff", "alle concrete blockers"),
            AgentRole.TESTER to listOf("schrijf of wijzig geen code", "/job/output/artifacts/screenshots"),
            AgentRole.SUMMARIZER to listOf("descriptionSummary", "shortDescriptionSummary"),
            AgentRole.DOCUMENTER to listOf("uitsluitend documentatie", "storydiff"),
            AgentRole.AUDITOR to listOf("auditReportMarkdown", "maximaal één"),
        )

        expected.forEach { (role, fragments) ->
            val instruction = factory.instruction(request(role))
            fragments.forEach { fragment ->
                assertTrue(instruction.contains(fragment), "$role mist promptcontract: $fragment")
            }
            assertTrue(instruction.contains("`phase`, `outcome` en een concrete `summaryText`"))
            if (role in REPOSITORY_ROLES) {
                assertTrue(instruction.contains("geen checkout, branch, commit, push, PR, merge"))
            }
        }
    }

    @Test
    fun `loopback en niet-geheime previewcontext gaan mee maar previewdatabasegeheim niet`() {
        val instruction = factory.instruction(
            request(AgentRole.DEVELOPER).copy(
                previewUrl = "https://preview.example.test",
                previewNamespace = "story-sf-42",
                previewDbUrl = "postgresql://secret-user:secret-pass@db/test",
                developerLoopbackReason = "Los alle reviewerbevindingen op.",
                aiEffort = "high",
            ),
        )

        assertTrue(instruction.contains("https://preview.example.test"))
        assertTrue(instruction.contains("story-sf-42"))
        assertTrue(instruction.contains("Los alle reviewerbevindingen op."))
        assertTrue(instruction.contains("Gevraagde effort: `high`"))
        assertFalse(instruction.contains("secret-pass"))
        assertFalse(instruction.contains("postgresql://"))
    }

    @Test
    fun `vragen uit schakelen verbiedt alle vragenfasen expliciet`() {
        RUNTIME_ROLES.forEach { role ->
            val instruction = factory.instruction(request(role).copy(questionsAllowed = false))
            assertTrue(instruction.contains("Vragen zijn uitgeschakeld"))
            assertTrue(instruction.contains("geen vragenfase"))
        }
    }

    @Test
    fun `schema begrenst fases en vereist rolvelden conditioneel`() {
        val planner = factory.resultSchema(AgentRole.PLANNER)
        val summarizer = factory.resultSchema(AgentRole.SUMMARIZER)

        assertTrue(planner.at("/properties/phase/enum").any { it.asText() == "planned-with-questions" })
        assertTrue(planner.path("allOf").toString().contains("subtasks"))
        assertTrue(summarizer.path("allOf").toString().contains("descriptionSummary"))
        assertTrue(summarizer.path("allOf").toString().contains("shortDescriptionSummary"))
        assertFalse(summarizer.at("/additionalProperties").asBoolean(true))
    }

    private fun request(role: AgentRole) = AgentDispatchRequest(
        storyKey = "SF-42",
        projectKey = "SF",
        targetRepo = "https://github.com/example/repo",
        storyRunId = 7,
        branchName = "ai/SF-42",
        role = role,
        phase = "running",
        baseBranch = "main",
        trackerContext = "## Issue Context\nBouw de gevraagde wijziging.",
    )

    private companion object {
        val REPOSITORY_ROLES = setOf(
            AgentRole.DEVELOPER,
            AgentRole.REVIEWER,
            AgentRole.TESTER,
            AgentRole.DOCUMENTER,
            AgentRole.AUDITOR,
        )
        val RUNTIME_ROLES = REPOSITORY_ROLES + setOf(
            AgentRole.REFINER,
            AgentRole.PLANNER,
            AgentRole.SUMMARIZER,
        )
    }
}
