package nl.vdzon.softwarefactory.dashboard.repositories

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.dashboard.models.UiAgentEvent
import nl.vdzon.softwarefactory.dashboard.models.UiAgentRun
import nl.vdzon.softwarefactory.dashboard.models.UiStoryRun
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

@Repository
class FactoryDashboardRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) {
    fun activeStoryRuns(limit: Int = 50): List<UiStoryRun> =
        storyRuns(
            where = "ended_at IS NULL",
            orderBy = "started_at DESC, id DESC",
            limit = limit,
        )

    fun recentStoryRuns(limit: Int = 25): List<UiStoryRun> =
        storyRuns(
            where = "TRUE",
            orderBy = "started_at DESC, id DESC",
            limit = limit,
        )

    fun mergedStoryRuns(limit: Int = 25): List<UiStoryRun> =
        storyRuns(
            where = "final_status = 'merged'",
            orderBy = "ended_at DESC NULLS LAST, id DESC",
            limit = limit,
        )

    /** Story-keys met minstens één gemergede run — voor de merged-indicator op het stories-overzicht. */
    fun mergedStoryKeys(): Set<String> =
        jdbcTemplate.queryForList(
            "SELECT DISTINCT story_key FROM ${schema}.story_runs WHERE final_status = 'merged'",
            String::class.java,
        ).toSet()

    fun latestStoryRun(storyKey: String): UiStoryRun? =
        jdbcTemplate.query(
            """
            ${storyRunSelect()}
            FROM ${schema}.story_runs
            WHERE story_key = ?
            ORDER BY ended_at IS NULL DESC, id DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ -> rs.toStoryRun() },
            storyKey,
        ).firstOrNull()

    fun agentRunsForStory(storyRunId: Long, limit: Int = 50): List<UiAgentRun> =
        agentRuns(
            where = "ar.story_run_id = ?",
            orderBy = "ar.started_at DESC, ar.id DESC",
            limit = limit,
            storyRunId,
        )

    fun agentRunById(agentRunId: Long): UiAgentRun? =
        agentRuns(
            where = "ar.id = ?",
            orderBy = "ar.id DESC",
            limit = 1,
            agentRunId,
        ).firstOrNull()

    fun activeAgentRuns(limit: Int = 25): List<UiAgentRun> =
        agentRuns(
            where = "ar.ended_at IS NULL",
            orderBy = "ar.started_at DESC, ar.id DESC",
            limit = limit,
        )

    fun recentAgentRuns(limit: Int = 25): List<UiAgentRun> =
        agentRuns(
            where = "TRUE",
            orderBy = "ar.started_at DESC, ar.id DESC",
            limit = limit,
        )

    fun eventsForStory(storyRunId: Long, limit: Int = 200): List<UiAgentEvent> =
        jdbcTemplate.query(
            """
            SELECT ae.id,
                   ae.agent_run_id,
                   sr.story_key,
                   ar.role,
                   ae.ts,
                   ae.kind,
                   ae.payload::text AS payload_text
            FROM ${schema}.agent_events ae
            JOIN ${schema}.agent_runs ar ON ar.id = ae.agent_run_id
            JOIN ${schema}.story_runs sr ON sr.id = ar.story_run_id
            WHERE sr.id = ?
            ORDER BY ae.ts DESC, ae.id DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> rs.toAgentEvent() },
            storyRunId,
            limit,
        )

    fun screenshotEventsForStory(storyRunId: Long, limit: Int = 100): List<UiAgentEvent> =
        jdbcTemplate.query(
            """
            SELECT ae.id,
                   ae.agent_run_id,
                   sr.story_key,
                   ar.role,
                   ae.ts,
                   ae.kind,
                   ae.payload::text AS payload_text
            FROM ${schema}.agent_events ae
            JOIN ${schema}.agent_runs ar ON ar.id = ae.agent_run_id
            JOIN ${schema}.story_runs sr ON sr.id = ar.story_run_id
            WHERE sr.id = ?
              AND ae.kind = 'tester-screenshot'
            ORDER BY ae.ts DESC, ae.id DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> rs.toAgentEvent() },
            storyRunId,
            limit,
        )

    private fun storyRuns(where: String, orderBy: String, limit: Int): List<UiStoryRun> =
        jdbcTemplate.query(
            """
            ${storyRunSelect()}
            FROM ${schema}.story_runs
            WHERE $where
            ORDER BY $orderBy
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> rs.toStoryRun() },
            limit,
        )

    private fun agentRuns(where: String, orderBy: String, limit: Int, vararg params: Any): List<UiAgentRun> =
        jdbcTemplate.query(
            """
            SELECT ar.id,
                   ar.story_run_id,
                   sr.story_key,
                   ar.role,
                   ar.container_name,
                   ar.model,
                   ar.effort,
                   ar.level,
                   ar.started_at,
                   ar.ended_at,
                   ar.outcome,
                   ar.input_tokens,
                   ar.output_tokens,
                   ar.cache_read_input_tokens,
                   ar.cache_creation_input_tokens,
                   ar.num_turns,
                   ar.duration_ms,
                   ar.cost_usd_est,
                   ar.summary_text,
                   ar.workspace_path,
                   ar.subtask_key
            FROM ${schema}.agent_runs ar
            JOIN ${schema}.story_runs sr ON sr.id = ar.story_run_id
            WHERE $where
            ORDER BY $orderBy
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> rs.toAgentRun() },
            *params,
            limit,
        )

    fun totalCostByTargetRepo(): Map<String, Double> =
        jdbcTemplate.query(
            """
            SELECT target_repo, SUM(total_cost_usd_est) AS total_cost
            FROM ${schema}.story_runs
            GROUP BY target_repo
            """.trimIndent(),
        ) { rs, _ -> rs.getString("target_repo") to rs.getDouble("total_cost") }
            .toMap()

    fun activeAgentCountByTargetRepo(): Map<String, Int> =
        jdbcTemplate.query(
            """
            SELECT sr.target_repo, COUNT(ar.id) AS active_count
            FROM ${schema}.agent_runs ar
            JOIN ${schema}.story_runs sr ON sr.id = ar.story_run_id
            WHERE ar.ended_at IS NULL
            GROUP BY sr.target_repo
            """.trimIndent(),
        ) { rs, _ -> rs.getString("target_repo") to rs.getInt("active_count") }
            .toMap()

    private fun storyRunSelect(): String =
        """
        SELECT id,
               story_key,
               target_repo,
               workspace_path,
               started_at,
               ended_at,
               final_status,
               branch_name,
               pr_number,
               pr_url,
               base_branch,
               branch_prefix,
               preview_url_template,
               preview_namespace_template,
               total_input_tokens,
               total_output_tokens,
               total_cache_read_tokens,
               total_cache_creation_tokens,
               total_cost_usd_est
        """.trimIndent()

    private fun ResultSet.toStoryRun(): UiStoryRun =
        UiStoryRun(
            id = getLong("id"),
            storyKey = getString("story_key"),
            targetRepo = getString("target_repo"),
            workspacePath = getString("workspace_path"),
            startedAt = getObject("started_at", OffsetDateTime::class.java),
            endedAt = getObject("ended_at", OffsetDateTime::class.java),
            finalStatus = getString("final_status"),
            branchName = getString("branch_name"),
            prNumber = (getObject("pr_number") as Number?)?.toInt(),
            prUrl = getString("pr_url"),
            baseBranch = getString("base_branch"),
            branchPrefix = getString("branch_prefix"),
            previewUrlTemplate = getString("preview_url_template"),
            previewNamespaceTemplate = getString("preview_namespace_template"),
            totalInputTokens = getLong("total_input_tokens"),
            totalOutputTokens = getLong("total_output_tokens"),
            totalCacheReadTokens = getLong("total_cache_read_tokens"),
            totalCacheCreationTokens = getLong("total_cache_creation_tokens"),
            totalCostUsdEst = getDouble("total_cost_usd_est"),
        )

    private fun ResultSet.toAgentRun(): UiAgentRun =
        UiAgentRun(
            id = getLong("id"),
            storyRunId = getLong("story_run_id"),
            storyKey = getString("story_key"),
            role = getString("role"),
            containerName = getString("container_name"),
            model = getString("model"),
            effort = getString("effort"),
            level = (getObject("level") as Number?)?.toInt(),
            startedAt = getObject("started_at", OffsetDateTime::class.java),
            endedAt = getObject("ended_at", OffsetDateTime::class.java),
            outcome = getString("outcome"),
            inputTokens = getLong("input_tokens"),
            outputTokens = getLong("output_tokens"),
            cacheReadInputTokens = getLong("cache_read_input_tokens"),
            cacheCreationInputTokens = getLong("cache_creation_input_tokens"),
            numTurns = getInt("num_turns"),
            durationMs = getLong("duration_ms"),
            costUsdEst = getDouble("cost_usd_est"),
            summaryText = getString("summary_text"),
            workspacePath = getString("workspace_path"),
            subtaskKey = getString("subtask_key"),
        )

    private fun ResultSet.toAgentEvent(): UiAgentEvent =
        UiAgentEvent(
            id = getLong("id"),
            agentRunId = getLong("agent_run_id"),
            storyKey = getString("story_key"),
            role = getString("role"),
            ts = getObject("ts", OffsetDateTime::class.java),
            kind = getString("kind"),
            payloadText = getString("payload_text"),
        )

    private val schema: String
        get() = factorySecrets.factoryDatabaseSchema
}
