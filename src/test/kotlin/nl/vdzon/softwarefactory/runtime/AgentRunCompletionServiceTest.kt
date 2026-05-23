package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.jira.AgentRole
import nl.vdzon.softwarefactory.orchestrator.AgentRunCompletionRecord
import nl.vdzon.softwarefactory.orchestrator.AgentRunRecord
import nl.vdzon.softwarefactory.orchestrator.AgentRunRepository
import nl.vdzon.softwarefactory.orchestrator.CompletedAgentRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

class AgentRunCompletionServiceTest {
    @Test
    fun `completion stores usage totals and redacted events`() {
        val runs = FakeAgentRunRepository()
        val events = FakeAgentEventRepository()
        val service = AgentRunCompletionService(
            agentRunRepository = runs,
            agentEventRepository = events,
            clock = Clock.fixed(java.time.Instant.parse("2026-05-23T20:00:00Z"), ZoneOffset.UTC),
        )

        val response = service.complete(
            AgentRunCompleteRequest(
                storyKey = "KAN-69",
                role = "developer",
                containerName = "factory-kan-69-developer",
                outcome = "ok",
                summaryText = "done",
                inputTokens = 1000,
                outputTokens = 500,
                cacheReadInputTokens = 10,
                cacheCreationInputTokens = 20,
                numTurns = 2,
                durationMs = 1234,
                costUsdEst = 0.42,
                events = listOf(
                    AgentRunEventPayload("log", "SF_GITHUB_TOKEN=secret postgresql://user:pass@host/db"),
                ),
            ),
        )

        assertEquals(200, response.statusCode.value())
        assertEquals(1L, response.body?.agentRunId)
        assertEquals(7L, response.body?.storyRunId)
        assertEquals("ok", runs.completed.single().outcome)
        assertEquals(1000, runs.usageAdded.single().inputTokens)
        assertTrue(events.payloads.single()["payload"].toString().contains("SF_GITHUB_TOKEN=<redacted>"))
        assertTrue(events.payloads.single()["payload"].toString().contains("postgresql://<redacted>"))
    }

    private class FakeAgentRunRepository : AgentRunRepository {
        val completed = mutableListOf<AgentRunCompletionRecord>()
        val usageAdded = mutableListOf<AgentRunCompletionRecord>()

        override fun recordStarted(storyRunId: Long, role: AgentRole, containerName: String, level: Int?): Long = 1

        override fun complete(
            containerName: String,
            completion: AgentRunCompletionRecord,
            endedAt: OffsetDateTime,
        ): CompletedAgentRun? {
            completed += completion
            return CompletedAgentRun(agentRunId = 1, storyRunId = 7)
        }

        override fun addUsageToStoryRun(storyRunId: Long, completion: AgentRunCompletionRecord) {
            usageAdded += completion
        }

        override fun latestForRole(storyRunId: Long, role: AgentRole): AgentRunRecord? = null

        override fun recentForRole(storyRunId: Long, role: AgentRole, limit: Int): List<AgentRunRecord> = emptyList()

        override fun countForRole(storyRunId: Long, role: AgentRole): Int = 0
    }

    private class FakeAgentEventRepository : AgentEventRepository {
        val payloads = mutableListOf<Map<String, Any?>>()

        override fun append(agentRunId: Long, kind: String, payload: Map<String, Any?>) {
            payloads += payload
        }
    }
}
