package nl.vdzon.softwarefactory.knowledge.repositories

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.knowledge.models.AgentKnowledgeEntry
import nl.vdzon.softwarefactory.knowledge.models.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.core.AgentRole
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

interface AgentKnowledgeRepository {
    fun find(targetRepo: String, role: AgentRole): List<AgentKnowledgeEntry>

    fun upsert(request: AgentKnowledgeUpdateRequest): AgentKnowledgeEntry
}

@Repository
class JdbcAgentKnowledgeRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) : AgentKnowledgeRepository {
    override fun find(targetRepo: String, role: AgentRole): List<AgentKnowledgeEntry> =
        jdbcTemplate.query(
            """
            SELECT target_repo, role, category, key, content, updated_by_story, updated_at
            FROM ${factorySecrets.factoryDatabaseSchema}.agent_knowledge
            WHERE target_repo = ? AND role = ?
            ORDER BY category ASC, key ASC
            """.trimIndent(),
            { rs, _ -> rs.toEntry() },
            targetRepo,
            role.markerKeyPart,
        )

    override fun upsert(request: AgentKnowledgeUpdateRequest): AgentKnowledgeEntry =
        requireNotNull(
            jdbcTemplate.query(
                """
                INSERT INTO ${factorySecrets.factoryDatabaseSchema}.agent_knowledge
                    (target_repo, role, category, key, content, updated_by_story)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (target_repo, role, category, key)
                DO UPDATE SET content = EXCLUDED.content,
                              updated_by_story = EXCLUDED.updated_by_story,
                              updated_at = now()
                RETURNING target_repo, role, category, key, content, updated_by_story, updated_at
                """.trimIndent(),
                { rs, _ -> rs.toEntry() },
                request.targetRepo,
                request.role,
                request.category,
                request.key,
                request.content,
                request.updatedByStory,
            ).firstOrNull(),
        )

    private fun ResultSet.toEntry(): AgentKnowledgeEntry =
        AgentKnowledgeEntry(
            targetRepo = getString("target_repo"),
            role = getString("role"),
            category = getString("category"),
            key = getString("key"),
            content = getString("content"),
            updatedByStory = getString("updated_by_story"),
            updatedAt = getObject("updated_at", OffsetDateTime::class.java),
        )
}

