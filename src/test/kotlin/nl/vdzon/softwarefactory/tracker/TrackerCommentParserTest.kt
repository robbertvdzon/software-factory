package nl.vdzon.softwarefactory.tracker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackerCommentParserTest {
    @Test
    fun `detects factory commands and triggers in user comments`() {
        val instructions = TrackerCommentParser.parseInstructions(
            """
            Please continue.
            @factory:command:pause
            LEVEL=7
            SUPPLIER=openai
            BUDGET=120000
            CONTINUE
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                TrackerCommandInstruction(FactoryCommand.PAUSE, "@factory:command:pause"),
                AiLevelTrigger(7, "LEVEL=7"),
                AiSupplierTrigger("openai", "SUPPLIER=openai"),
                BudgetTrigger(120000, "BUDGET=120000"),
                ContinueTrigger("CONTINUE"),
            ),
            instructions,
        )
    }

    @Test
    fun `ignores agent comments as commands`() {
        val body = "[REVIEWER] @factory:command:delete LEVEL=10"

        assertTrue(TrackerCommentParser.isAgentComment(body))
        assertEquals(AgentRole.REVIEWER, TrackerCommentParser.agentRole(body))
        assertEquals(emptyList<TrackerCommentInstruction>(), TrackerCommentParser.parseInstructions(body))
    }

    @Test
    fun `rejects invalid levels and recognizes non agent comments`() {
        val instructions = TrackerCommentParser.parseInstructions("LEVEL=11 and LEVEL=0")

        assertFalse(TrackerCommentParser.isAgentComment("Factory, please proceed"))
        assertEquals(listOf(AiLevelTrigger(0, "LEVEL=0")), instructions)
    }
}
