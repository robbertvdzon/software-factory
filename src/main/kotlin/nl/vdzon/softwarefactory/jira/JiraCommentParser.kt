package nl.vdzon.softwarefactory.jira

enum class FactoryCommand(val token: String) {
    PAUSE("pause"),
    RESUME("resume"),
    KILL("kill"),
    RE_IMPLEMENT("re-implement"),
    DELETE("delete"),
    MERGE("merge"),
}

sealed interface JiraCommentInstruction {
    val sourceText: String
}

data class JiraCommandInstruction(
    val command: FactoryCommand,
    override val sourceText: String,
) : JiraCommentInstruction

sealed interface JiraTriggerInstruction : JiraCommentInstruction

data class AiLevelTrigger(
    val level: Int,
    override val sourceText: String,
) : JiraTriggerInstruction

data class BudgetTrigger(
    val budget: Long,
    override val sourceText: String,
) : JiraTriggerInstruction

data class ContinueTrigger(
    override val sourceText: String,
) : JiraTriggerInstruction

object JiraCommentParser {
    private val agentPrefixPattern = Regex(
        """^\s*\[(REFINER|DEVELOPER|REVIEWER|TESTER|COST-MONITOR|ORCHESTRATOR)]""",
        RegexOption.IGNORE_CASE,
    )
    private val commandPattern = Regex("""(?i)@factory:command:([a-z-]+)""")
    private val levelPattern = Regex("""(?i)\bLEVEL\s*=\s*(\d{1,2})\b""")
    private val budgetPattern = Regex("""(?i)\bBUDGET\s*=\s*(\d+)\b""")
    private val continuePattern = Regex("""\bCONTINUE\b""")

    fun isAgentComment(body: String): Boolean =
        agentPrefixPattern.containsMatchIn(body)

    fun agentRole(body: String): AgentRole? =
        AgentRole.entries.firstOrNull { role ->
            body.trimStart().startsWith(role.commentPrefix, ignoreCase = true)
        }

    fun parseInstructions(body: String): List<JiraCommentInstruction> {
        if (isAgentComment(body)) {
            return emptyList()
        }

        val instructions = mutableListOf<JiraCommentInstruction>()

        commandPattern.findAll(body).forEach { match ->
            val command = FactoryCommand.entries.firstOrNull { it.token == match.groupValues[1].lowercase() }
            if (command != null) {
                instructions += JiraCommandInstruction(command, match.value)
            }
        }

        levelPattern.findAll(body).forEach { match ->
            val level = match.groupValues[1].toInt()
            if (level in 0..10) {
                instructions += AiLevelTrigger(level, match.value)
            }
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
