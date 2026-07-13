package nl.vdzon.softwarefactory.orchestrator
import nl.vdzon.softwarefactory.core.contracts.*

import nl.vdzon.softwarefactory.core.contracts.AiRouting
import nl.vdzon.softwarefactory.core.AgentRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiRoutingTest {
    @Test
    fun `claude always defaults to sonnet 5 with effort scaling by level`() {
        assertRoute(0, "claude", AgentRole.DEVELOPER, "claude-sonnet-5", "low")
        assertRoute(5, "claude", AgentRole.DEVELOPER, "claude-sonnet-5", "medium")
        assertRoute(10, "claude", AgentRole.DEVELOPER, "claude-sonnet-5", "high")
        assertRoute(10, "claude", AgentRole.TESTER, "claude-sonnet-5", "high")
    }

    @Test
    fun `copilot uses compact level matrix`() {
        assertRoute(0, "copilot", AgentRole.DEVELOPER, "gpt-4.1", "low")
        assertRoute(1, "copilot", AgentRole.DEVELOPER, "claude-haiku-4.5", "low")
        assertRoute(3, "copilot", AgentRole.DEVELOPER, "claude-haiku-4.5", "medium")
        assertRoute(4, "copilot", AgentRole.DEVELOPER, "claude-sonnet-4.5", "medium")
        assertRoute(9, "copilot", AgentRole.DEVELOPER, "claude-sonnet-4.5", "high")
        assertRoute(10, "copilot", AgentRole.DEVELOPER, "claude-opus-4.5", "high")
    }

    @Test
    fun `mock keeps dummy model and clamps level`() {
        assertRoute(-1, "mock", AgentRole.DEVELOPER, "dummy-ai-client", "low", expectedLevel = 0)
        assertRoute(99, "mock", AgentRole.DEVELOPER, "dummy-ai-client", "high", expectedLevel = 10)
    }

    private fun assertRoute(
        level: Int,
        supplier: String,
        role: AgentRole,
        expectedModel: String?,
        expectedEffort: String,
        expectedLevel: Int = level,
    ) {
        val route = AiRouting.resolve(level, supplier, role)
        assertEquals(expectedLevel, route.level)
        assertEquals(expectedModel, route.model)
        assertEquals(expectedEffort, route.effort)
    }
}
