package nl.vdzon.softwarefactory.jira

import nl.vdzon.softwarefactory.config.FactorySecrets
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

interface ProcessedCommentStore {
    fun isProcessed(storyKey: String, commentId: String, role: AgentRole): Boolean

    fun markProcessed(storyKey: String, commentId: String, role: AgentRole)
}

@Repository
class JdbcProcessedCommentStore(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) : ProcessedCommentStore {
    override fun isProcessed(storyKey: String, commentId: String, role: AgentRole): Boolean {
        val count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM ${factorySecrets.factoryDatabaseSchema}.processed_comments
            WHERE story_key = ? AND comment_id = ? AND role = ?
            """.trimIndent(),
            Long::class.java,
            storyKey,
            commentId,
            role.markerKeyPart,
        )
        return (count ?: 0L) > 0
    }

    override fun markProcessed(storyKey: String, commentId: String, role: AgentRole) {
        jdbcTemplate.update(
            """
            INSERT INTO ${factorySecrets.factoryDatabaseSchema}.processed_comments (story_key, comment_id, role)
            VALUES (?, ?, ?)
            ON CONFLICT (story_key, comment_id, role) DO NOTHING
            """.trimIndent(),
            storyKey,
            commentId,
            role.markerKeyPart,
        )
    }
}
