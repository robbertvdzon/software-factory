package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.jira.AgentRole
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

interface StoryRunRepository {
    fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord
}

data class StoryRunRecord(
    val id: Long,
    val storyKey: String,
    val targetRepo: String,
)

interface AgentRunRepository {
    fun recordStarted(storyRunId: Long, role: AgentRole, containerName: String, level: Int?): Long

    fun complete(containerName: String, completion: AgentRunCompletionRecord, endedAt: OffsetDateTime): CompletedAgentRun?

    fun addUsageToStoryRun(storyRunId: Long, completion: AgentRunCompletionRecord)

    fun latestForRole(storyRunId: Long, role: AgentRole): AgentRunRecord?

    fun recentForRole(storyRunId: Long, role: AgentRole, limit: Int): List<AgentRunRecord>

    fun countForRole(storyRunId: Long, role: AgentRole): Int
}

data class AgentRunRecord(
    val id: Long,
    val storyRunId: Long,
    val role: AgentRole,
    val startedAt: OffsetDateTime,
    val endedAt: OffsetDateTime?,
    val outcome: String?,
    val summaryText: String?,
)

data class AgentRunCompletionRecord(
    val outcome: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadInputTokens: Int,
    val cacheCreationInputTokens: Int,
    val numTurns: Int,
    val durationMs: Int,
    val costUsdEst: Double,
    val summaryText: String?,
)

data class CompletedAgentRun(
    val agentRunId: Long,
    val storyRunId: Long,
)

@Repository
class JdbcStoryRunRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) : StoryRunRepository {
    override fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord {
        val existing = jdbcTemplate.query(
            """
            SELECT id, story_key, target_repo
            FROM ${factorySecrets.factoryDatabaseSchema}.story_runs
            WHERE story_key = ? AND ended_at IS NULL
            ORDER BY id DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.toStoryRunRecord() },
            storyKey,
        ).firstOrNull()

        if (existing != null) {
            return existing
        }

        val id = requireNotNull(
            jdbcTemplate.queryForObject(
                """
                INSERT INTO ${factorySecrets.factoryDatabaseSchema}.story_runs (story_key, target_repo)
                VALUES (?, ?)
                RETURNING id
                """.trimIndent(),
                Long::class.java,
                storyKey,
                targetRepo,
            ),
        )
        return StoryRunRecord(id, storyKey, targetRepo)
    }

    private fun ResultSet.toStoryRunRecord(): StoryRunRecord =
        StoryRunRecord(
            id = getLong("id"),
            storyKey = getString("story_key"),
            targetRepo = getString("target_repo"),
        )
}

@Repository
class JdbcAgentRunRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) : AgentRunRepository {
    override fun recordStarted(storyRunId: Long, role: AgentRole, containerName: String, level: Int?): Long =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                INSERT INTO ${factorySecrets.factoryDatabaseSchema}.agent_runs
                    (story_run_id, role, container_name, level)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """.trimIndent(),
                Long::class.java,
                storyRunId,
                role.markerKeyPart,
                containerName,
                level,
            ),
        )

    override fun complete(containerName: String, completion: AgentRunCompletionRecord, endedAt: OffsetDateTime): CompletedAgentRun? =
        jdbcTemplate.query(
            """
            UPDATE ${factorySecrets.factoryDatabaseSchema}.agent_runs
            SET ended_at = ?,
                outcome = ?,
                input_tokens = ?,
                output_tokens = ?,
                cache_read_input_tokens = ?,
                cache_creation_input_tokens = ?,
                num_turns = ?,
                duration_ms = ?,
                cost_usd_est = ?,
                summary_text = ?
            WHERE container_name = ?
              AND ended_at IS NULL
            RETURNING id, story_run_id
            """.trimIndent(),
            { rs, _ -> CompletedAgentRun(rs.getLong("id"), rs.getLong("story_run_id")) },
            endedAt,
            completion.outcome,
            completion.inputTokens,
            completion.outputTokens,
            completion.cacheReadInputTokens,
            completion.cacheCreationInputTokens,
            completion.numTurns,
            completion.durationMs,
            completion.costUsdEst,
            completion.summaryText,
            containerName,
        ).firstOrNull()

    override fun addUsageToStoryRun(storyRunId: Long, completion: AgentRunCompletionRecord) {
        jdbcTemplate.update(
            """
            UPDATE ${factorySecrets.factoryDatabaseSchema}.story_runs
            SET total_input_tokens = total_input_tokens + ?,
                total_output_tokens = total_output_tokens + ?,
                total_cache_read_tokens = total_cache_read_tokens + ?,
                total_cache_creation_tokens = total_cache_creation_tokens + ?,
                total_cost_usd_est = total_cost_usd_est + ?
            WHERE id = ?
            """.trimIndent(),
            completion.inputTokens,
            completion.outputTokens,
            completion.cacheReadInputTokens,
            completion.cacheCreationInputTokens,
            completion.costUsdEst,
            storyRunId,
        )
    }

    override fun latestForRole(storyRunId: Long, role: AgentRole): AgentRunRecord? =
        recentForRole(storyRunId, role, limit = 1).firstOrNull()

    override fun recentForRole(storyRunId: Long, role: AgentRole, limit: Int): List<AgentRunRecord> =
        jdbcTemplate.query(
            """
            SELECT id, story_run_id, role, started_at, ended_at, outcome, summary_text
            FROM ${factorySecrets.factoryDatabaseSchema}.agent_runs
            WHERE story_run_id = ? AND role = ?
            ORDER BY started_at DESC, id DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> rs.toAgentRunRecord() },
            storyRunId,
            role.markerKeyPart,
            limit,
        )

    override fun countForRole(storyRunId: Long, role: AgentRole): Int =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM ${factorySecrets.factoryDatabaseSchema}.agent_runs
                WHERE story_run_id = ? AND role = ?
                """.trimIndent(),
                Int::class.java,
                storyRunId,
                role.markerKeyPart,
            ),
        )

    private fun ResultSet.toAgentRunRecord(): AgentRunRecord =
        AgentRunRecord(
            id = getLong("id"),
            storyRunId = getLong("story_run_id"),
            role = AgentRole.entries.first { it.markerKeyPart == getString("role") },
            startedAt = getObject("started_at", OffsetDateTime::class.java),
            endedAt = getObject("ended_at", OffsetDateTime::class.java),
            outcome = getString("outcome"),
            summaryText = getString("summary_text"),
        )
}
