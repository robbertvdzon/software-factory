package nl.vdzon.softwarefactory.runtime.repositories

import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.support.SupportApi
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

interface AgentEventRepository {
    fun append(agentRunId: Long, kind: String, payload: Map<String, Any?>)

    fun recentForAgentRun(agentRunId: Long, kinds: Set<String> = emptySet(), limit: Int = 20): List<AgentEventRecord> = emptyList()
}

data class AgentEventRecord(
    val id: Long,
    val kind: String,
    val payloadText: String,
)

@Repository
class JdbcAgentEventRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
    private val objectMapper: ObjectMapper,
) : AgentEventRepository {
    override fun append(agentRunId: Long, kind: String, payload: Map<String, Any?>) {
        val redactedPayload = payload.mapValues { (_, value) ->
            if (value is String) SupportApi.default().redact(value) else value
        }
        jdbcTemplate.update(
            """
            INSERT INTO ${factorySecrets.factoryDatabaseSchema}.agent_events (agent_run_id, kind, payload)
            VALUES (?, ?, ?::jsonb)
            """.trimIndent(),
            agentRunId,
            kind,
            objectMapper.writeValueAsString(redactedPayload),
        )
    }

    override fun recentForAgentRun(agentRunId: Long, kinds: Set<String>, limit: Int): List<AgentEventRecord> {
        val filters = mutableListOf<Any>(agentRunId)
        val kindFilter = if (kinds.isEmpty()) {
            ""
        } else {
            filters.addAll(kinds)
            "AND kind IN (${kinds.joinToString(",") { "?" }})"
        }
        filters += limit
        return jdbcTemplate.query(
            """
            SELECT id, kind, payload::text AS payload_text
            FROM ${factorySecrets.factoryDatabaseSchema}.agent_events
            WHERE agent_run_id = ?
              $kindFilter
            ORDER BY id DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> AgentEventRecord(rs.getLong("id"), rs.getString("kind"), rs.getString("payload_text")) },
            *filters.toTypedArray(),
        )
    }
}
