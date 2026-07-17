package nl.vdzon.softwarefactory.runtime.services

import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.softwarefactory.runtime.AgentLogApi
import nl.vdzon.softwarefactory.runtime.models.AgentLogLine
import nl.vdzon.softwarefactory.runtime.repositories.AgentEventRepository
import org.springframework.stereotype.Service

@Service
class AgentLogService(
    private val agentEventRepository: AgentEventRepository,
    private val objectMapper: ObjectMapper,
) : AgentLogApi {
    override fun recentLines(agentRunId: Long, limit: Int): List<AgentLogLine> =
        agentEventRepository.recentForAgentRun(agentRunId, kinds = LOG_EVENT_KINDS, limit = limit)
            .reversed()
            .map { record ->
                val line = runCatching { objectMapper.readTree(record.payloadText).path("line").asText("") }.getOrDefault("")
                AgentLogLine(id = record.id, kind = record.kind, line = line)
            }

    private companion object {
        val LOG_EVENT_KINDS = setOf("docker-stdout", "docker-stderr")
    }
}
