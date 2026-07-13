package nl.vdzon.softwarefactory.tracker.clients

import nl.vdzon.softwarefactory.config.FactorySecrets
import org.springframework.jdbc.core.JdbcTemplate

internal class PostgresIssueKeySequence(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) {
    fun next(projectKey: String): String {
        val schema = factorySecrets.factoryDatabaseSchema
        jdbcTemplate.update(
            """
            INSERT INTO $schema.project_key_sequences (project_key, next_number)
            VALUES (?, 1)
            ON CONFLICT (project_key) DO NOTHING
            """.trimIndent(),
            projectKey,
        )
        val used = jdbcTemplate.query(
            """
            UPDATE $schema.project_key_sequences
            SET next_number = next_number + 1
            WHERE project_key = ?
            RETURNING next_number - 1 AS used_number
            """.trimIndent(),
            { rs, _ -> rs.getInt("used_number") },
            projectKey,
        ).first()
        return "$projectKey-$used"
    }
}
