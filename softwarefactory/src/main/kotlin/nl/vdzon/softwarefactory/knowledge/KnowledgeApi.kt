package nl.vdzon.softwarefactory.knowledge

import java.time.OffsetDateTime

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

data class AgentKnowledgeEntry(
    val targetRepo: String,
    val role: String,
    val category: String,
    val key: String,
    val content: String,
    val updatedByStory: String?,
    val updatedAt: OffsetDateTime?,
)

data class AgentKnowledgeUpdateRequest(
    val targetRepo: String,
    val role: String,
    val category: String,
    val key: String,
    val content: String,
    val updatedByStory: String? = null,
)

