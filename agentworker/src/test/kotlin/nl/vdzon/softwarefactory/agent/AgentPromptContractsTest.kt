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
}
