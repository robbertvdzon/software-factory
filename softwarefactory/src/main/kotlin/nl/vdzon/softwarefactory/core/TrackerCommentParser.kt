package nl.vdzon.softwarefactory.core

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.AiLevelTrigger
import nl.vdzon.softwarefactory.core.AiSupplierTrigger
import nl.vdzon.softwarefactory.core.AutoApproveTrigger
import nl.vdzon.softwarefactory.core.BudgetTrigger
import nl.vdzon.softwarefactory.core.ContinueTrigger
import nl.vdzon.softwarefactory.core.FactoryCommand
import nl.vdzon.softwarefactory.core.TrackerCommandInstruction
import nl.vdzon.softwarefactory.core.TrackerCommentInstruction

object TrackerCommentParser {
    private val agentPrefixPattern = Regex(
        """^\s*\[(REFINER|DEVELOPER|REVIEWER|TESTER|SUMMARIZER|COST-MONITOR|ORCHESTRATOR)]""",
        RegexOption.IGNORE_CASE,
    )
    private val commandPattern = Regex("""(?i)@factory:command:([a-z-]+)""")
    private val levelPattern = Regex("""(?i)\bLEVEL\s*=\s*(\d{1,2})\b""")
    private val supplierPattern = Regex("""(?i)\bSUPPLIER\s*=\s*(none|mock|claude|openai|copilot|microsoft)\b""")
    private val autoApprovePattern = Regex("""(?i)\bAUTO-APPROVE\s*=\s*(on|off)\b""")
    private val budgetPattern = Regex("""(?i)\bBUDGET\s*=\s*(\d+)\b""")
    private val continuePattern = Regex("""\bCONTINUE\b""")

    fun isAgentComment(body: String): Boolean =
        agentPrefixPattern.containsMatchIn(body)

    fun agentRole(body: String): AgentRole? =
        AgentRole.entries.firstOrNull { role ->
            body.trimStart().startsWith(role.commentPrefix, ignoreCase = true)
        }

    fun parseInstructions(body: String): List<TrackerCommentInstruction> {
        if (isAgentComment(body)) {
            return emptyList()
        }

        val instructions = mutableListOf<TrackerCommentInstruction>()

        commandPattern.findAll(body).forEach { match ->
            val command = FactoryCommand.entries.firstOrNull { it.token == match.groupValues[1].lowercase() }
            if (command != null) {
                instructions += TrackerCommandInstruction(command, match.value)
            }
        }

        levelPattern.findAll(body).forEach { match ->
            val level = match.groupValues[1].toInt()
            if (level in 0..10) {
                instructions += AiLevelTrigger(level, match.value)
            }
        }

        supplierPattern.findAll(body).forEach { match ->
            instructions += AiSupplierTrigger(match.groupValues[1].lowercase(), match.value)
        }

        autoApprovePattern.findAll(body).forEach { match ->
            instructions += AutoApproveTrigger(match.groupValues[1].equals("on", ignoreCase = true), match.value)
        }

        budgetPattern.findAll(body).forEach { match ->
            val budget = match.groupValues[1].toLong()
            if (budget > 0) {
                instructions += BudgetTrigger(budget, match.value)
            }
        }

        continuePattern.findAll(body).forEach { match ->
            instructions += ContinueTrigger(match.value)
        }

        return instructions
    }
}
