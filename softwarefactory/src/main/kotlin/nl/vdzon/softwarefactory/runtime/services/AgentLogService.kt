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
        val text = line.ifBlank { payloadText }
        // Elke docker-stdout/-stderr-regel begint met Docker's eigen timestamp-prefix (RFC3339Nano
        // + spatie), niet met content van de Claude/Codex-CLI zelf. De frontend interpreteert deze
        // regel als JSON om 'm leesbaar te tonen (SF-1047/agent_log_event.dart); met de timestamp
        // ervoor is dat nooit geldige JSON, dus viel elke regel terug op ruwe tekst. Strippen op de
        // bron, zodat alle consumenten van deze API een schone regel krijgen.
        return text.replaceFirst(DOCKER_TIMESTAMP_PREFIX, "")
    }

    private companion object {
        val LOG_KINDS = setOf("docker-stdout", "docker-stderr")
        val DOCKER_TIMESTAMP_PREFIX = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z\s+""")
    }
}
