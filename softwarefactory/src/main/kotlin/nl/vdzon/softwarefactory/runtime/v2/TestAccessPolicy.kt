package nl.vdzon.softwarefactory.runtime.v2

import nl.vdzon.softwarefactory.core.AgentRole

/** Expliciete repository-toekenning; credentials blijven beperkt tot niet-productietestlogin. */
object TestAccessPolicy {
    fun parse(value: String): Map<String, String> = value.split(',').filter(String::isNotBlank).associate { entry ->
        val parts = entry.trim().split('=', limit = 2)
        require(parts.size == 2 && Regex("[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+").matches(parts[0])) { "Ongeldige testrepository" }
        require(Regex("[A-Z][A-Z0-9_]*__(TEST|ACCEPTANCE|PREVIEW)_AGENT_TOKEN").matches(parts[1])) { "Alleen niet-productie agenttokens zijn toegestaan" }
        parts[0].lowercase() to parts[1]
    }

    fun selected(role: AgentRole, repository: String, grants: Map<String, String>): List<String> {
        if (role != AgentRole.TESTER) return emptyList()
        val normalized = repository.removePrefix("https://github.com/").removePrefix("git@github.com:").removeSuffix(".git").lowercase()
        return listOfNotNull(grants[normalized])
    }
}
