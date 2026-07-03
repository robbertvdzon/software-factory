package nl.vdzon.softwarefactory.core

/**
 * Herkent agent-comments aan hun rol-prefix. Uit TrackerCommentParser (server) gelicht omdat ook
 * de gedeelde GitHubCliClient (factory-common) menselijke @factory-comments van agent-comments
 * moet kunnen onderscheiden; de parser zelf blijft in de server (die kent commands/triggers).
 * De regex is bewust ongewijzigd overgenomen zodat het gedrag identiek blijft.
 */
object AgentComments {
    private val agentPrefixPattern = Regex(
        """^\s*\[(REFINER|DEVELOPER|REVIEWER|TESTER|SUMMARIZER|COST-MONITOR|ORCHESTRATOR)]""",
        RegexOption.IGNORE_CASE,
    )

    fun isAgentComment(body: String): Boolean =
        agentPrefixPattern.containsMatchIn(body)
}
