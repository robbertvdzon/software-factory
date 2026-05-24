package nl.vdzon.softwarefactory.jira

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JiraCommentParserTest {
    @Test
    fun `detects factory commands and triggers in user comments`() {
        val instructions = JiraCommentParser.parseInstructions(
            """
            Please continue.
            @factory:command:pause
            LEVEL=7
            BUDGET=120000
            CONTINUE
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                JiraCommandInstruction(FactoryCommand.PAUSE, "@factory:command:pause"),
                AiLevelTrigger(7, "LEVEL=7"),
                BudgetTrigger(120000, "BUDGET=120000"),
                ContinueTrigger("CONTINUE"),
            ),
            instructions,
        )
    }

    @Test
    fun `ignores agent comments as commands`() {
        val body = "[REVIEWER] @factory:command:delete LEVEL=10"

        assertTrue(JiraCommentParser.isAgentComment(body))
        assertEquals(AgentRole.REVIEWER, JiraCommentParser.agentRole(body))
        assertEquals(emptyList<JiraCommentInstruction>(), JiraCommentParser.parseInstructions(body))
    }

    @Test
    fun `rejects invalid levels and recognizes non agent comments`() {
        val instructions = JiraCommentParser.parseInstructions("LEVEL=11 and LEVEL=0")

        assertFalse(JiraCommentParser.isAgentComment("Factory, please proceed"))
        assertEquals(listOf(AiLevelTrigger(0, "LEVEL=0")), instructions)
    }
}
