package nl.vdzon.softwarefactory.dashboard.database

import nl.vdzon.softwarefactory.dashboard.api.AgentEventDto
import nl.vdzon.softwarefactory.dashboard.api.AgentRunDto
import nl.vdzon.softwarefactory.dashboard.api.StoryRunDto
import nl.vdzon.softwarefactory.dashboard.config.DashboardSecrets
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

@Repository
class DashboardRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val secrets: DashboardSecrets,
) {
    fun activeStoryRuns(limit: Int): List<StoryRunDto> =
        storyRuns("ended_at IS NULL", "started_at DESC, id DESC", limit)

    fun recentStoryRuns(limit: Int): List<StoryRunDto> =
        storyRuns("TRUE", "started_at DESC, id DESC", limit)

    fun latestStoryRun(storyKey: String): StoryRunDto? =
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

    fun agentRunsForStory(storyRunId: Long): List<AgentRunDto> =
        agentRuns("ar.story_run_id = ?", "ar.started_at DESC, ar.id DESC", 50, storyRunId)

    fun activeAgentRuns(limit: Int): List<AgentRunDto> =
        agentRuns("ar.ended_at IS NULL", "ar.started_at DESC, ar.id DESC", limit)

    fun eventsForStory(storyRunId: Long): List<AgentEventDto> =
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
            LIMIT 200
            """.trimIndent(),
            { rs, _ -> rs.toAgentEvent() },
            storyRunId,
        )

    private fun storyRuns(where: String, orderBy: String, limit: Int): List<StoryRunDto> =
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

    private fun agentRuns(where: String, orderBy: String, limit: Int, vararg params: Any): List<AgentRunDto> =
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
                   ar.summary_text
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

    private fun storyRunSelect(): String =
        """
        SELECT id,
               story_key,
               target_repo,
               started_at,
               ended_at,
               final_status,
               branch_name,
               pr_number,
               pr_url,
               preview_url_template,
               total_input_tokens,
               total_output_tokens,
               total_cache_read_tokens,
               total_cache_creation_tokens,
               total_cost_usd_est
        """.trimIndent()

    private fun ResultSet.toStoryRun(): StoryRunDto =
        StoryRunDto(
            id = getLong("id"),
            storyKey = getString("story_key"),
            targetRepo = getString("target_repo"),
            startedAt = getObject("started_at", OffsetDateTime::class.java),
            endedAt = getObject("ended_at", OffsetDateTime::class.java),
            finalStatus = getString("final_status"),
            branchName = getString("branch_name"),
            prNumber = (getObject("pr_number") as Number?)?.toInt(),
            prUrl = getString("pr_url"),
            previewUrl = PreviewUrlResolver.resolve(
                getString("preview_url_template"),
                (getObject("pr_number") as Number?)?.toInt(),
                getString("target_repo"),
            ),
            totalTokens = getLong("total_input_tokens") + getLong("total_output_tokens") +
                getLong("total_cache_read_tokens") + getLong("total_cache_creation_tokens"),
            totalCostUsd = getDouble("total_cost_usd_est"),
        )

    private fun ResultSet.toAgentRun(): AgentRunDto {
        val input = getLong("input_tokens")
        val output = getLong("output_tokens")
        val cacheRead = getLong("cache_read_input_tokens")
        val cacheCreation = getLong("cache_creation_input_tokens")
        return AgentRunDto(
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
            totalTokens = input + output + cacheRead + cacheCreation,
            inputTokens = input,
            outputTokens = output,
            cacheReadTokens = cacheRead,
            cacheCreationTokens = cacheCreation,
            turns = getInt("num_turns"),
            durationMs = getLong("duration_ms"),
            costUsd = getDouble("cost_usd_est"),
            summary = getString("summary_text"),
        )
    }

    private fun ResultSet.toAgentEvent(): AgentEventDto =
        AgentEventDto(
            id = getLong("id"),
            agentRunId = getLong("agent_run_id"),
            storyKey = getString("story_key"),
            role = getString("role"),
            timestamp = getObject("ts", OffsetDateTime::class.java),
            kind = getString("kind"),
            payload = getString("payload_text"),
        )

    private val schema: String get() = secrets.databaseSchema
}
