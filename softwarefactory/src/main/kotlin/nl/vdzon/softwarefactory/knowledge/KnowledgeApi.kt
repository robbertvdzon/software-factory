package nl.vdzon.softwarefactory.knowledge

import nl.vdzon.softwarefactory.knowledge.models.AgentKnowledgeEntry
import nl.vdzon.softwarefactory.knowledge.models.AgentKnowledgeUpdateRequest

/**
 * Public API of the knowledge module.
 *
 * The knowledge module stores reusable agent guidance per target repository and
 * role. Other modules use this API to read tips for an agent run or persist new
 * lessons learned by an agent.
 */
interface KnowledgeApi {
    fun find(targetRepo: String, role: String): List<AgentKnowledgeEntry>

    fun upsert(request: AgentKnowledgeUpdateRequest): AgentKnowledgeEntry
}

