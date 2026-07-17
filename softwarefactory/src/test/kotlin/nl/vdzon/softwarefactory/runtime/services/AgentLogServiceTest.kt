package nl.vdzon.softwarefactory.runtime.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.runtime.repositories.AgentEventRecord
import nl.vdzon.softwarefactory.runtime.repositories.AgentEventRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Dekt [AgentLogService] (SF-1010): vertaalt [AgentEventRepository.recentForAgentRun] (nieuwste
 * eerst, per `id DESC`) naar chronologisch geordende [nl.vdzon.softwarefactory.runtime.models.AgentLogLine]s
 * en leest het `line`-veld uit de JSON-payload.
 */
class AgentLogServiceTest {

    private class FakeAgentEventRepository(private val records: List<AgentEventRecord>) : AgentEventRepository {
        var lastAgentRunId: Long? = null
        var lastKinds: Set<String>? = null
        var lastLimit: Int? = null

        override fun append(agentRunId: Long, kind: String, payload: Map<String, Any?>) = Unit

        override fun recentForAgentRun(agentRunId: Long, kinds: Set<String>, limit: Int): List<AgentEventRecord> {
            lastAgentRunId = agentRunId
            lastKinds = kinds
            lastLimit = limit
            return records
        }
    }

    @Test
    fun `zet de nieuwste-eerst-records om naar chronologisch geordende regels`() {
        val repository = FakeAgentEventRepository(
            listOf(
                AgentEventRecord(id = 3, kind = "docker-stdout", payloadText = """{"line":"derde regel"}"""),
                AgentEventRecord(id = 2, kind = "docker-stderr", payloadText = """{"line":"tweede regel"}"""),
                AgentEventRecord(id = 1, kind = "docker-stdout", payloadText = """{"line":"eerste regel"}"""),
            ),
        )
        val service = AgentLogService(repository, jacksonObjectMapper())

        val lines = service.recentLines(agentRunId = 42, limit = 100)

        assertEquals(listOf("eerste regel", "tweede regel", "derde regel"), lines.map { it.line })
        assertEquals(listOf(1L, 2L, 3L), lines.map { it.id })
        assertEquals(listOf("docker-stdout", "docker-stderr", "docker-stdout"), lines.map { it.kind })
        assertEquals(42L, repository.lastAgentRunId)
        assertEquals(setOf("docker-stdout", "docker-stderr"), repository.lastKinds)
        assertEquals(100, repository.lastLimit)
    }

    @Test
    fun `een payload zonder line-veld levert een lege regel ipv een crash`() {
        val repository = FakeAgentEventRepository(
            listOf(AgentEventRecord(id = 1, kind = "docker-stdout", payloadText = """{"containerName":"c-1"}""")),
        )
        val service = AgentLogService(repository, jacksonObjectMapper())

        val lines = service.recentLines(agentRunId = 1)

        assertEquals(listOf(""), lines.map { it.line })
    }

    @Test
    fun `geen events levert een lege lijst`() {
        val service = AgentLogService(FakeAgentEventRepository(emptyList()), jacksonObjectMapper())

        assertEquals(emptyList(), service.recentLines(agentRunId = 99))
    }
}
