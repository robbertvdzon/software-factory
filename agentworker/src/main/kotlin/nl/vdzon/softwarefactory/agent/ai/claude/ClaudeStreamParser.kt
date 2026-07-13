package nl.vdzon.softwarefactory.agent.ai.claude

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.agent.AgentEvent
import nl.vdzon.softwarefactory.agent.AgentUsage

data class ClaudeRunReport(
    val summaryText: String,
    val usage: AgentUsage,
    val outcome: String,
    val events: List<AgentEvent>,
)

object ClaudeStreamParser {
    private val objectMapper = jacksonObjectMapper()

    fun parse(lines: List<String>): ClaudeRunReport {
        val events = mutableListOf<AgentEvent>()
        var summaryText = ""
        var outcome = "success"
        var usage = AgentUsage(0, 0, 0, 0, 0, 0, 0.0)

        lines.filter { it.isNotBlank() }.forEach { line ->
            val node = runCatching { objectMapper.readTree(line) }.getOrNull()
            if (node == null) {
                events += AgentEvent("claude-raw", objectMapper.writeValueAsString(mapOf("text" to line.take(4000))))
                return@forEach
            }

            val type = node.path("type").asText("unknown")
            events += AgentEvent("claude-$type", objectMapper.writeValueAsString(node))

            if (type == "result") {
                summaryText = node.path("result").asText("").trim()
                outcome = node.path("subtype").asText(outcome).ifBlank { outcome }
                usage = AgentUsage(
                    inputTokens = node.path("usage").path("input_tokens").asInt(0),
                    outputTokens = node.path("usage").path("output_tokens").asInt(0),
                    cacheReadInputTokens = node.path("usage").path("cache_read_input_tokens").asInt(0),
                    cacheCreationInputTokens = node.path("usage").path("cache_creation_input_tokens").asInt(0),
                    numTurns = node.path("num_turns").asInt(0),
                    durationMs = node.path("duration_ms").asInt(0),
                    costUsdEst = node.path("total_cost_usd").asDouble(0.0),
                )
            }
        }

        return ClaudeRunReport(summaryText, usage, outcome, events)
    }
}


