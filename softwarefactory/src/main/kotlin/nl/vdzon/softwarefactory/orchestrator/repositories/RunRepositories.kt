package nl.vdzon.softwarefactory.orchestrator.repositories

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.orchestrator.AgentRunCompletionRecord
import nl.vdzon.softwarefactory.orchestrator.AgentRunRecord
import nl.vdzon.softwarefactory.orchestrator.AgentRunRepository
import nl.vdzon.softwarefactory.orchestrator.CompletedAgentRun
import nl.vdzon.softwarefactory.orchestrator.StoryRunRecord
import nl.vdzon.softwarefactory.orchestrator.StoryRunRepository
import nl.vdzon.softwarefactory.orchestrator.SystemStateRecord
import nl.vdzon.softwarefactory.orchestrator.SystemStateRepository
import nl.vdzon.softwarefactory.youtrack.AgentRole
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

@Repository
class JdbcStoryRunRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) : StoryRunRepository {
    override fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord {
        val existing = jdbcTemplate.query(
            """
            ${storyRunSelect()}
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

    override fun get(storyRunId: Long): StoryRunRecord? =
        jdbcTemplate.query(
            """
            ${storyRunSelect()}
            FROM ${factorySecrets.factoryDatabaseSchema}.story_runs
            WHERE id = ?
            """.trimIndent(),
            { rs, _ -> rs.toStoryRunRecord() },
            storyRunId,
        ).firstOrNull()

    override fun updatePullRequest(
        storyRunId: Long,
        branchName: String,
        prNumber: Int?,
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
            prNumber?.takeIf { it > 0 },
            prUrl,
            baseBranch,
            branchPrefix,
            previewUrlTemplate,
            previewNamespaceTemplate,
            previewDbSecretRecipe,
            storyRunId,
        )
    }

    override fun updateWorkspace(
        storyRunId: Long,
        workspacePath: String,
        branchName: String,
        baseBranch: String?,
        branchPrefix: String?,
        previewUrlTemplate: String?,
        previewNamespaceTemplate: String?,
        previewDbSecretRecipe: String?,
    ) {
        jdbcTemplate.update(
            """
            UPDATE ${factorySecrets.factoryDatabaseSchema}.story_runs
            SET workspace_path = ?,
                branch_name = ?,
                base_branch = ?,
                branch_prefix = ?,
                preview_url_template = ?,
                preview_namespace_template = ?,
                preview_db_secret_recipe = ?
            WHERE id = ?
            """.trimIndent(),
            workspacePath,
            branchName,
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
            ${storyRunSelect()}
            FROM ${factorySecrets.factoryDatabaseSchema}.story_runs
            WHERE ended_at IS NULL
              AND pr_number IS NOT NULL
            ORDER BY id ASC
            """.trimIndent(),
        ) { rs, _ -> rs.toStoryRunRecord() }

    override fun activeRuns(): List<StoryRunRecord> =
        jdbcTemplate.query(
            """
            ${storyRunSelect()}
            FROM ${factorySecrets.factoryDatabaseSchema}.story_runs
            WHERE ended_at IS NULL
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

    override fun delete(storyRunId: Long) {
        jdbcTemplate.update(
            """
            DELETE FROM ${factorySecrets.factoryDatabaseSchema}.story_runs
            WHERE id = ?
            """.trimIndent(),
            storyRunId,
        )
    }

    private fun ResultSet.toStoryRunRecord(): StoryRunRecord =
        StoryRunRecord(
            id = getLong("id"),
            storyKey = getString("story_key"),
            targetRepo = getString("target_repo"),
            workspacePath = getString("workspace_path"),
            branchName = getString("branch_name"),
            prNumber = (getObject("pr_number") as Number?)?.toInt(),
            prUrl = getString("pr_url"),
            baseBranch = getString("base_branch"),
            branchPrefix = getString("branch_prefix"),
            previewUrlTemplate = getString("preview_url_template"),
            previewNamespaceTemplate = getString("preview_namespace_template"),
            previewDbSecretRecipe = getString("preview_db_secret_recipe"),
            totalInputTokens = getLong("total_input_tokens"),
            totalOutputTokens = getLong("total_output_tokens"),
            totalCacheReadTokens = getLong("total_cache_read_tokens"),
            totalCacheCreationTokens = getLong("total_cache_creation_tokens"),
            totalCostUsdEst = getDouble("total_cost_usd_est"),
        )

    private fun storyRunSelect(): String =
        """
        SELECT id, story_key, target_repo, workspace_path, branch_name, pr_number, pr_url,
               base_branch, branch_prefix, preview_url_template,
               preview_namespace_template, preview_db_secret_recipe,
               total_input_tokens, total_output_tokens, total_cache_read_tokens,
               total_cache_creation_tokens, total_cost_usd_est
        """.trimIndent()
}

@Repository
class JdbcSystemStateRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) : SystemStateRepository {
    override fun current(): SystemStateRecord {
        ensureRow()
        return requireNotNull(
            jdbcTemplate.query(
                """
                SELECT credits_paused_until, credits_paused_reason
                FROM ${factorySecrets.factoryDatabaseSchema}.system_state
                WHERE id = 1
                """.trimIndent(),
                { rs, _ ->
                    SystemStateRecord(
                        creditsPausedUntil = rs.getObject("credits_paused_until", OffsetDateTime::class.java),
                        creditsPausedReason = rs.getString("credits_paused_reason"),
                    )
                },
            ).firstOrNull(),
        )
    }

    override fun pauseCredits(until: OffsetDateTime, reason: String) {
        ensureRow()
        jdbcTemplate.update(
            """
            UPDATE ${factorySecrets.factoryDatabaseSchema}.system_state
            SET credits_paused_until = ?,
                credits_paused_reason = ?
            WHERE id = 1
            """.trimIndent(),
            until,
            reason,
        )
    }

    override fun resumeCredits() {
        ensureRow()
        jdbcTemplate.update(
            """
            UPDATE ${factorySecrets.factoryDatabaseSchema}.system_state
            SET credits_paused_until = NULL,
                credits_paused_reason = NULL
            WHERE id = 1
            """.trimIndent(),
        )
    }

    private fun ensureRow() {
        jdbcTemplate.update(
            """
            INSERT INTO ${factorySecrets.factoryDatabaseSchema}.system_state (id)
            VALUES (1)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
    }
}

@Repository
class JdbcAgentRunRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) : AgentRunRepository {
    override fun recordStarted(
        storyRunId: Long,
        role: AgentRole,
        containerName: String,
        model: String?,
        effort: String?,
        level: Int?,
        workspacePath: String?,
    ): Long =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                INSERT INTO ${factorySecrets.factoryDatabaseSchema}.agent_runs
                    (story_run_id, role, container_name, model, effort, level, workspace_path)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """.trimIndent(),
                Long::class.java,
                storyRunId,
                role.markerKeyPart,
                containerName,
                model,
                effort,
                level,
                workspacePath,
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
            RETURNING id, story_run_id, workspace_path
            """.trimIndent(),
            { rs, _ -> CompletedAgentRun(rs.getLong("id"), rs.getLong("story_run_id"), rs.getString("workspace_path")) },
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

    override fun activeRuns(): List<AgentRunRecord> =
        jdbcTemplate.query(
            """
            SELECT id, story_run_id, role, container_name, started_at, ended_at, outcome, summary_text, model, effort, level, workspace_path
            FROM ${factorySecrets.factoryDatabaseSchema}.agent_runs
            WHERE ended_at IS NULL
            ORDER BY started_at ASC, id ASC
            """.trimIndent(),
            { rs, _ -> rs.toAgentRunRecord() },
        )

    override fun latestForRole(storyRunId: Long, role: AgentRole): AgentRunRecord? =
        recentForRole(storyRunId, role, limit = 1).firstOrNull()

    override fun recentForRole(storyRunId: Long, role: AgentRole, limit: Int): List<AgentRunRecord> =
        jdbcTemplate.query(
            """
            SELECT id, story_run_id, role, container_name, started_at, ended_at, outcome, summary_text, model, effort, level, workspace_path
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
            containerName = getString("container_name"),
            startedAt = getObject("started_at", OffsetDateTime::class.java),
            endedAt = getObject("ended_at", OffsetDateTime::class.java),
            outcome = getString("outcome"),
            summaryText = getString("summary_text"),
            model = getString("model"),
            effort = getString("effort"),
            level = (getObject("level") as Number?)?.toInt(),
            workspacePath = getString("workspace_path"),
        )
}
