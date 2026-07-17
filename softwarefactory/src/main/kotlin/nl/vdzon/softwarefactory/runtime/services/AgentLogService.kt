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
    override fun recentLogLines(agentRunId: Long, limit: Int): List<AgentLogLine> =
        agentEventRepository.recentForAgentRun(agentRunId, kinds = LOG_KINDS, limit = limit)
            .asReversed()
            .map { record -> AgentLogLine(kind = record.kind, text = lineTextOf(record.payloadText)) }

    private fun lineTextOf(payloadText: String): String {
        val line = runCatching { objectMapper.readTree(payloadText).path("line").asText("") }.getOrDefault("")
        return line.ifBlank { payloadText }
    }

    private companion object {
        val LOG_KINDS = setOf("docker-stdout", "docker-stderr")
    }
}
