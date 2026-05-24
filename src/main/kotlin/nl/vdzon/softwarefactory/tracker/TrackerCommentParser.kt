package nl.vdzon.softwarefactory.tracker

enum class FactoryCommand(val token: String) {
    PAUSE("pause"),
    RESUME("resume"),
    KILL("kill"),
    RE_IMPLEMENT("re-implement"),
    DELETE("delete"),
    MERGE("merge"),
}

sealed interface TrackerCommentInstruction {
    val sourceText: String
}

data class TrackerCommandInstruction(
    val command: FactoryCommand,
    override val sourceText: String,
) : TrackerCommentInstruction

sealed interface TrackerTriggerInstruction : TrackerCommentInstruction

data class AiLevelTrigger(
    val level: Int,
    override val sourceText: String,
) : TrackerTriggerInstruction

data class AiSupplierTrigger(
    val supplier: String,
    override val sourceText: String,
) : TrackerTriggerInstruction

data class BudgetTrigger(
    val budget: Long,
    override val sourceText: String,
) : TrackerTriggerInstruction

data class ContinueTrigger(
    override val sourceText: String,
) : TrackerTriggerInstruction

object TrackerCommentParser {
    private val agentPrefixPattern = Regex(
        """^\s*\[(REFINER|DEVELOPER|REVIEWER|TESTER|COST-MONITOR|ORCHESTRATOR)]""",
        RegexOption.IGNORE_CASE,
    )
    private val commandPattern = Regex("""(?i)@factory:command:([a-z-]+)""")
    private val levelPattern = Regex("""(?i)\bLEVEL\s*=\s*(\d{1,2})\b""")
    private val supplierPattern = Regex("""(?i)\bSUPPLIER\s*=\s*(none|claude|openai|microsoft)\b""")
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
