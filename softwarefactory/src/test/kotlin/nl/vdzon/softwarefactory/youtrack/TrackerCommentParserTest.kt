package nl.vdzon.softwarefactory.youtrack

import nl.vdzon.softwarefactory.youtrack.clients.*
import nl.vdzon.softwarefactory.youtrack.parsers.*
import nl.vdzon.softwarefactory.youtrack.repositories.*
import nl.vdzon.softwarefactory.youtrack.services.*

import nl.vdzon.softwarefactory.youtrack.*
import nl.vdzon.softwarefactory.youtrack.*
import nl.vdzon.softwarefactory.youtrack.*
import nl.vdzon.softwarefactory.youtrack.*

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
            @factory:command:sync
            @factory:command:retry-current-step
            LEVEL=7
            SUPPLIER=mock
            BUDGET=120000
            CONTINUE
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                TrackerCommandInstruction(FactoryCommand.PAUSE, "@factory:command:pause"),
                TrackerCommandInstruction(FactoryCommand.SYNC, "@factory:command:sync"),
                TrackerCommandInstruction(FactoryCommand.RETRY_CURRENT_STEP, "@factory:command:retry-current-step"),
                AiLevelTrigger(7, "LEVEL=7"),
                AiSupplierTrigger("mock", "SUPPLIER=mock"),
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
