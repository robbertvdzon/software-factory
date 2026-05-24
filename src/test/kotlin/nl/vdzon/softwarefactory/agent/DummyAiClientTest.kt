package nl.vdzon.softwarefactory.agent

import nl.vdzon.softwarefactory.agent.ai.*
import nl.vdzon.softwarefactory.agent.flows.*
import nl.vdzon.softwarefactory.agent.services.*

import nl.vdzon.softwarefactory.youtrack.AgentRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DummyAiClientTest {
    @Test
    fun `forced outcomes map to role phases`() {
        val client = DummyAiClient()

        assertEquals(
            "refined-with-questions-for-user",
            client.run(context(AgentRole.REFINER, "questions")).phase,
        )
        assertEquals("developed", client.run(context(AgentRole.DEVELOPER, "ok")).phase)
        assertEquals(
            "reviewed-with-feedback-for-developer",
            client.run(context(AgentRole.REVIEWER, "feedback")).phase,
        )
        assertEquals(
            "tested-with-feedback-for-developer",
            client.run(context(AgentRole.TESTER, "bug")).phase,
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
