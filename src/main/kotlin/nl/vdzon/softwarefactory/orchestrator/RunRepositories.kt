package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.jira.AgentRole
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

interface StoryRunRepository {
    fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord

    fun updatePullRequest(
        storyRunId: Long,
        branchName: String,
        prNumber: Int,
        prUrl: String?,
        baseBranch: String?,
        branchPrefix: String?,
        previewUrlTemplate: String?,
        previewNamespaceTemplate: String?,
        previewDbSecretRecipe: String?,
    )

    fun activePullRequests(): List<StoryRunRecord>

    fun close(storyRunId: Long, finalStatus: String, endedAt: OffsetDateTime)
}

data class StoryRunRecord(
    val id: Long,
    val storyKey: String,
    val targetRepo: String,
    val branchName: String? = null,
    val prNumber: Int? = null,
    val prUrl: String? = null,
    val baseBranch: String? = null,
    val branchPrefix: String? = null,
    val previewUrlTemplate: String? = null,
    val previewNamespaceTemplate: String? = null,
    val previewDbSecretRecipe: String? = null,
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
            SELECT id, story_key, target_repo, branch_name, pr_number, pr_url,
                   base_branch, branch_prefix, preview_url_template,
                   preview_namespace_template, preview_db_secret_recipe
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

    override fun updatePullRequest(
        storyRunId: Long,
        branchName: String,
        prNumber: Int,
        prUrl: String?,
        baseBranch: String?,
        branchPrefix: String?,
        previewUrlTemplate: String?,
        previewNamespaceTemplate: String?,
        previewDbSecretRecipe: String?,
    ) {
        jdbcTemplate.update(
            """
            UPDATE ${factorySecrets.factoryDatabaseSchema}.story_runs
            SET branch_name = ?,
                pr_number = ?,
                pr_url = ?,
                base_branch = ?,
                branch_prefix = ?,
                preview_url_template = ?,
                preview_namespace_template = ?,
                preview_db_secret_recipe = ?
            WHERE id = ?
            """.trimIndent(),
            branchName,
            prNumber.takeIf { it > 0 },
            prUrl,
            baseBranch,
            branchPrefix,
            previewUrlTemplate,
            previewNamespaceTemplate,
            previewDbSecretRecipe,
            storyRunId,
        )
    }

    override fun activePullRequests(): List<StoryRunRecord> =
        jdbcTemplate.query(
            """
            SELECT id, story_key, target_repo, branch_name, pr_number, pr_url,
                   base_branch, branch_prefix, preview_url_template,
                   preview_namespace_template, preview_db_secret_recipe
            FROM ${factorySecrets.factoryDatabaseSchema}.story_runs
            WHERE ended_at IS NULL
              AND pr_number IS NOT NULL
            ORDER BY id ASC
            """.trimIndent(),
        ) { rs, _ -> rs.toStoryRunRecord() }

    override fun close(storyRunId: Long, finalStatus: String, endedAt: OffsetDateTime) {
        jdbcTemplate.update(
            """
            UPDATE ${factorySecrets.factoryDatabaseSchema}.story_runs
            SET ended_at = ?,
                final_status = ?
            WHERE id = ?
              AND ended_at IS NULL
            """.trimIndent(),
            endedAt,
            finalStatus,
            storyRunId,
        )
    }

    private fun ResultSet.toStoryRunRecord(): StoryRunRecord =
        StoryRunRecord(
            id = getLong("id"),
            storyKey = getString("story_key"),
            targetRepo = getString("target_repo"),
            branchName = getString("branch_name"),
            prNumber = (getObject("pr_number") as Number?)?.toInt(),
            prUrl = getString("pr_url"),
            baseBranch = getString("base_branch"),
            branchPrefix = getString("branch_prefix"),
            previewUrlTemplate = getString("preview_url_template"),
            previewNamespaceTemplate = getString("preview_namespace_template"),
            previewDbSecretRecipe = getString("preview_db_secret_recipe"),
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
