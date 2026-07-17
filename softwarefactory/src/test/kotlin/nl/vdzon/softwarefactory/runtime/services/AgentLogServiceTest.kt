package nl.vdzon.softwarefactory.runtime.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.runtime.models.AgentLogLine
import nl.vdzon.softwarefactory.runtime.repositories.AgentEventRecord
import nl.vdzon.softwarefactory.runtime.repositories.AgentEventRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Dekt [AgentLogService]: vertaalt de al gecapturede `docker-stdout`/`docker-stderr`-events
 * (SF-1038) van [AgentEventRepository.recentForAgentRun] naar een chronologisch geordende
 * logfeed voor de nieuwe agent-detailweergave.
 */
class AgentLogServiceTest {

    private val objectMapper = jacksonObjectMapper()

    private class FakeAgentEventRepository(private val records: List<AgentEventRecord>) : AgentEventRepository {
        var lastKinds: Set<String>? = null
        var lastLimit: Int? = null

        override fun append(agentRunId: Long, kind: String, payload: Map<String, Any?>) = Unit

        override fun recentForAgentRun(agentRunId: Long, kinds: Set<String>, limit: Int): List<AgentEventRecord> {
            lastKinds = kinds
            lastLimit = limit
            return records
        }
    }

    private fun payload(line: String) = """{"containerName":"c-1","line":"$line"}"""

    @Test
    fun `zet de nieuwste-eerst repository-volgorde om naar chronologisch (oudste eerst)`() {
        val repository = FakeAgentEventRepository(
            listOf(
                AgentEventRecord(id = 3, kind = "docker-stdout", payloadText = payload("regel 3")),
                AgentEventRecord(id = 2, kind = "docker-stderr", payloadText = payload("regel 2")),
                AgentEventRecord(id = 1, kind = "docker-stdout", payloadText = payload("regel 1")),
            ),
        )
        val service = AgentLogService(repository, objectMapper)

        val lines = service.recentLogLines(agentRunId = 42, limit = 500)

        assertEquals(
            listOf(
                AgentLogLine(kind = "docker-stdout", text = "regel 1"),
                AgentLogLine(kind = "docker-stderr", text = "regel 2"),
                AgentLogLine(kind = "docker-stdout", text = "regel 3"),
            ),
            lines,
        )
    }

    @Test
    fun `vraagt alleen docker-stdout- en docker-stderr-kinds op met de opgegeven limiet`() {
        val repository = FakeAgentEventRepository(emptyList())
        val service = AgentLogService(repository, objectMapper)

        service.recentLogLines(agentRunId = 7, limit = 50)

        assertEquals(setOf("docker-stdout", "docker-stderr"), repository.lastKinds)
        assertEquals(50, repository.lastLimit)
    }

    @Test
    fun `valt terug op de ruwe payload-tekst als het 'line'-veld ontbreekt of niet parseerbaar is`() {
        val repository = FakeAgentEventRepository(
            listOf(AgentEventRecord(id = 1, kind = "docker-stdout", payloadText = "geen-json")),
        )
        val service = AgentLogService(repository, objectMapper)

        val lines = service.recentLogLines(agentRunId = 1)

        assertEquals(listOf(AgentLogLine(kind = "docker-stdout", text = "geen-json")), lines)
    }

    @Test
    fun `strip de docker-timestamp-prefix zodat een JSONL-regel weer geldige JSON is`() {
        // Reproduceert de echte vorm: docker --timestamps zet "<RFC3339Nano>Z " vóór elke regel.
        // Zonder strippen was dit nooit valide JSON, waardoor de frontend-parser (SF-1047) altijd
        // op de ruwe-tekst-fallback belandde — precies het "1 grote tekst-brei"-symptoom.
        val jsonLine = """{\"type\":\"system\",\"subtype\":\"init\"}"""
        val repository = FakeAgentEventRepository(
            listOf(
                AgentEventRecord(
                    id = 1,
                    kind = "docker-stdout",
                    payloadText = payload("2026-07-17T20:13:36.574923708Z $jsonLine"),
                ),
            ),
        )
        val service = AgentLogService(repository, objectMapper)

        val lines = service.recentLogLines(agentRunId = 1)

        assertEquals(listOf(AgentLogLine(kind = "docker-stdout", text = """{"type":"system","subtype":"init"}""")), lines)
    }

    @Test
    fun `plain-tekst logregels blijven ongewijzigd nadat de timestamp-prefix eraf is`() {
        val repository = FakeAgentEventRepository(
            listOf(
                AgentEventRecord(
                    id = 1,
                    kind = "docker-stdout",
                    payloadText = payload("2026-07-17T20:13:35.243555680Z Agent worker started: story=SF-1 role=refiner"),
                ),
            ),
        )
        val service = AgentLogService(repository, objectMapper)

        val lines = service.recentLogLines(agentRunId = 1)

        assertEquals(
            listOf(AgentLogLine(kind = "docker-stdout", text = "Agent worker started: story=SF-1 role=refiner")),
            lines,
        )
    }

    @Test
    fun `geeft een lege lijst als de repository geen events heeft`() {
        val service = AgentLogService(FakeAgentEventRepository(emptyList()), objectMapper)

        assertEquals(emptyList(), service.recentLogLines(agentRunId = 1))
    }
}
