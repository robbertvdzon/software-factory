package nl.vdzon.softwarefactory.agent

import nl.vdzon.softwarefactory.agent.ai.shared.AgentOutcomeParser
import nl.vdzon.softwarefactory.agent.ai.shared.AgentPromptBuilder
import nl.vdzon.softwarefactory.core.AgentRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentPromptContractsTest {

    @Test
    fun `developer system prompt bevat het developed-with-questions faseoptie`() {
        val prompt = AgentPromptBuilder.systemPrompt(AgentRole.DEVELOPER, effort = null)

        assertTrue(prompt.contains("\"phase\":\"developed\""), "verwacht {\"phase\":\"developed\"} contract")
        assertTrue(
            prompt.contains("\"phase\":\"developed-with-questions\""),
            "verwacht {\"phase\":\"developed-with-questions\"} contract",
        )
    }

    @Test
    fun `developer system prompt instrueert escaleren naar PO bij onenigheid of scope-twijfel`() {
        val prompt = AgentPromptBuilder.systemPrompt(AgentRole.DEVELOPER, effort = null)

        assertTrue(
            prompt.contains("escaleer") && prompt.contains("PO"),
            "verwacht een escalatie-instructie naar de PO in de developer-prompt",
        )
    }

    @Test
    fun `reviewer prompt eist complete eerste review en begrensde vervolgreview`() {
        val prompt = AgentPromptBuilder.systemPrompt(AgentRole.REVIEWER, effort = null)

        assertTrue(prompt.contains("Eerste reviewronde") && prompt.contains("ALLE concrete blockers en bugs"))
        assertTrue(prompt.contains("Vervolgreview") && prompt.contains("eerdere `[REVIEWER]`-comments"))
        assertTrue(prompt.contains("FACTORY VERIFICATION EVIDENCE") && prompt.contains("testedTreeSha"))
    }

    @Test
    fun `gedeelde system prompt geeft PO-antwoorden voorrang boven de refined story`() {
        AgentRole.entries
            .filterNot { it in setOf(AgentRole.ASSISTANT, AgentRole.COST_MONITOR, AgentRole.ORCHESTRATOR) }
            .forEach { role ->
                val prompt = AgentPromptBuilder.systemPrompt(role, effort = null)
                assertTrue(
                    prompt.contains("PO-antwoorden") && prompt.contains("leidend"),
                    "verwacht de PO-voorrangsregel in de system prompt voor rol $role",
                )
            }
    }

    @Test
    fun `auditor system prompt draagt op het rapport naar het rapportbestand te schrijven`() {
        val prompt = AgentPromptBuilder.systemPrompt(
            AgentRole.AUDITOR,
            effort = null,
            auditReportPath = "/work/audit-report.md",
        )

        assertTrue(prompt.contains("/work/audit-report.md"), "verwacht het rapportpad in de auditor-prompt")
        assertTrue(prompt.contains("geen JSON"), "verwacht de instructie dat het rapport geen JSON bevat")
    }

    @Test
    fun `questionsAllowed false instrueert nooit de -with-questions-vorm te gebruiken`() {
        val prompt = AgentPromptBuilder.systemPrompt(AgentRole.REFINER, effort = null, questionsAllowed = false)

        assertTrue(
            prompt.contains("Vragen staan voor deze story UIT"),
            "verwacht een expliciete no-questions-instructie",
        )
        assertTrue(
            prompt.contains("NOOIT"),
            "verwacht dat de instructie het gebruik van -with-questions hard uitsluit",
        )
        assertTrue(
            !prompt.contains("gebruik dan de \"-with-questions\"-vorm met een concrete vraag"),
            "de toegestane-vorm-instructie uit de vragen-AAN-tak hoort niet meer in de prompt te staan",
        )
    }

    @Test
    fun `questionsAllowed true (default) laat de bestaande -with-questions-instructie ongewijzigd`() {
        val prompt = AgentPromptBuilder.systemPrompt(AgentRole.REFINER, effort = null)

        assertTrue(prompt.contains("gebruik dan de \"-with-questions\"-vorm met een concrete vraag"))
        assertTrue(!prompt.contains("Vragen staan voor deze story UIT"))
    }

    @Test
    fun `retry contract reminder voor developer toont de developed-varianten`() {
        val reminder = AgentPromptBuilder.retryContractReminder(AgentRole.DEVELOPER)

        assertTrue(reminder.contains("\"phase\":\"developed\""))
        assertTrue(reminder.contains("\"phase\":\"developed-with-questions\""))
    }

    @Test
    fun `AgentOutcomeParser mapt developed-with-questions voor de developer`() {
        val decision = AgentOutcomeParser.parse(
            AgentRole.DEVELOPER,
            """Ik heb een vraag voor de PO.\n{"phase":"developed-with-questions","questions":["mag ik X ook aanpassen?"]}""",
        )

        assertEquals("developed-with-questions", decision?.phase)
    }

    @Test
    fun `AgentOutcomeParser haalt subtasks op ook als een omschrijving een losse accolade bevat`() {
        // SF: reproduceert product-factory-18 — een subtaak-description die zelf een niet-gebalanceerde
        // { bevat (bv. een regex-/JSON-voorbeeld) mag de subtasks-array niet laten verdwijnen.
        val text = """
            Het plan is toegevoegd aan de story-body.
            ```json
            {"agent_tips_update":[]}
            ```
            ```json
            {"phase":"planned","subtasks":[{"type":"development","title":"Reden-blok toevoegen","description":"regexcheck tegen {\"/\":\"-patronen in de output."},{"type":"test","title":"Story-brede test"},{"type":"summary","title":"Eindsamenvatting"}]}
            ```
        """.trimIndent()

        val decision = AgentOutcomeParser.parse(AgentRole.PLANNER, text)

        assertEquals("planned", decision?.phase)
        assertEquals(
            listOf("Reden-blok toevoegen", "Story-brede test", "Eindsamenvatting"),
            decision?.subtasks?.map { it.title },
        )
    }
}
