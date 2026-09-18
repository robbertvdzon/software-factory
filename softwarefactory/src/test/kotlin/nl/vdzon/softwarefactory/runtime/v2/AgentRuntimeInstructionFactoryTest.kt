package nl.vdzon.softwarefactory.runtime.v2

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AgentDispatchRequest
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class AgentRuntimeInstructionFactoryTest {
    private val factory = AgentRuntimeInstructionFactory(jacksonObjectMapper(), mock(KnowledgeApi::class.java))

    @Test
    fun `testability contract survives questions disabled and includes configured preview capability`() {
        val prompt = factory.instruction(request(AgentRole.TESTER).copy(questionsAllowed = false))
        assertTrue(prompt.contains("test-decision-needed"))
        assertTrue(prompt.contains("tested-with-limitations"))
        assertTrue(prompt.contains("test-environment-repair"))
        assertFalse(prompt.contains("keur niet goed"))
        val phases = factory.resultSchema(AgentRole.TESTER).path("properties").path("phase").path("enum").map { it.asText() }
        assertTrue(phases.containsAll(listOf("test-decision-needed", "tested-with-limitations", "test-environment-repair")))
        assertFalse(phases.contains("test-repair-requested"))
        val refinement = factory.instruction(request(AgentRole.REFINER).copy(previewUrlTemplate = "https://pr-{pr}.test"))
        assertTrue(refinement.contains("https://pr-{pr}.test"))
        assertTrue(refinement.contains("Testaanpak"))
        assertTrue(refinement.contains("vóór merge"))
    }

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
    fun `schema begrenst fases en is geldig voor strict structured output`() {
        val planner = factory.resultSchema(AgentRole.PLANNER)
        val summarizer = factory.resultSchema(AgentRole.SUMMARIZER)

        assertTrue(planner.at("/properties/phase/enum").any { it.asText() == "planned-with-questions" })
        assertStrictObject(planner)
        assertStrictObject(planner.at("/properties/subtasks/items"))
        assertStrictObject(planner.at("/properties/knowledgeUpdates/items"))
        assertStrictObject(summarizer)
        assertTrue(summarizer.path("required").any { it.asText() == "descriptionSummary" })
        assertTrue(summarizer.path("required").any { it.asText() == "shortDescriptionSummary" })
        assertFalse(summarizer.at("/additionalProperties").asBoolean(true))
    }

    @Test
    fun `outcome kan geen akkoordrapport met nul errors als fout laten classificeren`() {
        RUNTIME_ROLES.forEach { role ->
            val allowed = factory.resultSchema(role).at("/properties/outcome/enum").map { it.asText() }
            assertEquals(listOf("success", "error"), allowed)
            assertFalse("Alle criteria akkoord, 0 failures/errors" in allowed)
            assertTrue(factory.instruction(request(role)).contains("nooit een samenvatting"))
        }
    }

    private fun assertStrictObject(schema: com.fasterxml.jackson.databind.JsonNode) {
        val properties = schema.path("properties").fieldNames().asSequence().toSet()
        val required = schema.path("required").map { it.asText() }.toSet()
        assertEquals(properties, required)
        assertFalse(schema.path("additionalProperties").asBoolean(true))
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
