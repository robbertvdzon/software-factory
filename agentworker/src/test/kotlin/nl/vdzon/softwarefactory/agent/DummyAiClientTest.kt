package nl.vdzon.softwarefactory.agent

import nl.vdzon.softwarefactory.agent.*
import nl.vdzon.softwarefactory.agent.ai.claude.*
import nl.vdzon.softwarefactory.agent.ai.dummy.*
import nl.vdzon.softwarefactory.agent.ai.unsupported.*
import nl.vdzon.softwarefactory.agentworker.flows.*

import nl.vdzon.softwarefactory.youtrack.AgentRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DummyAiClientTest {
    @Test
    fun `forced outcomes map to role phases`() {
        val client = DummyAiClient()

        assertEquals(
            "refined-with-questions",
            client.run(context(AgentRole.REFINER, "questions")).phase,
        )
        assertEquals("refined", client.run(context(AgentRole.REFINER, "ok")).phase)
        assertEquals("planned", client.run(context(AgentRole.PLANNER, "ok")).phase)
        assertEquals(
            "planned-with-questions",
            client.run(context(AgentRole.PLANNER, "questions")).phase,
        )
        assertEquals("developed", client.run(context(AgentRole.DEVELOPER, "ok")).phase)
        assertEquals(
            "review-rejected",
            client.run(context(AgentRole.REVIEWER, "feedback")).phase,
        )
        assertEquals("reviewed", client.run(context(AgentRole.REVIEWER, "ok")).phase)
        assertEquals(
            "test-rejected",
            client.run(context(AgentRole.TESTER, "bug")).phase,
        )
        assertEquals("tested", client.run(context(AgentRole.TESTER, "ok")).phase)
        assertEquals("summarized", client.run(context(AgentRole.SUMMARIZER, "ok")).phase)
        assertEquals("documented", client.run(context(AgentRole.DOCUMENTER, "ok")).phase)
        assertEquals(
            "documentation-with-questions",
            client.run(context(AgentRole.DOCUMENTER, "questions")).phase,
        )
    }

    @Test
    fun `planner declares subtasks on ok`() {
        val outcome = DummyAiClient().run(context(AgentRole.PLANNER, "ok"))

        assertEquals("planned", outcome.phase)
        assertEquals(
            listOf("development", "review", "test", "summary"),
            outcome.subtasks.map { it.type },
        )
    }

    @Test
    fun `forced error returns non zero outcome without phase`() {
        val outcome = DummyAiClient().run(context(AgentRole.TESTER, "error"))

        assertEquals(null, outcome.phase)
        assertEquals("error", outcome.outcome)
        assertEquals(1, outcome.exitCode)
    }

    @Test
    fun `forced credits exhausted returns system pause outcome`() {
        val outcome = DummyAiClient().run(context(AgentRole.DEVELOPER, "credits-exhausted"))

        assertEquals(null, outcome.phase)
        assertEquals("credits-exhausted", outcome.outcome)
        assertEquals(1, outcome.exitCode)
    }

    private fun context(role: AgentRole, forcedOutcome: String): AgentContext =
        AgentContext("KAN-69", role, "task", forcedOutcome)
}
