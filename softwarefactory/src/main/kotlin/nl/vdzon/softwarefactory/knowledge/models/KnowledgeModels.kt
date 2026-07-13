package nl.vdzon.softwarefactory.knowledge.models

import java.time.OffsetDateTime

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
