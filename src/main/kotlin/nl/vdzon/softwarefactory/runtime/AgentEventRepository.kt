package nl.vdzon.softwarefactory.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

interface AgentEventRepository {
    fun append(agentRunId: Long, kind: String, payload: Map<String, Any?>)
}

@Repository
class JdbcAgentEventRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
    private val objectMapper: ObjectMapper,
) : AgentEventRepository {
    override fun append(agentRunId: Long, kind: String, payload: Map<String, Any?>) {
        val redactedPayload = payload.mapValues { (_, value) ->
            if (value is String) SecretRedactor.redact(value) else value
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
}
