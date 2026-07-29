package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.runtime.repositories.AgentEventRepository
import nl.vdzon.softwarefactory.runtime.services.AgentEventRetentionPoller
import nl.vdzon.softwarefactory.runtime.services.AgentEventRetentionSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * `agent_events` had als enige tabel geen opruiming en groeide onbeperkt (436 MB op 2026-07-29).
 * Deze tests pinnen het gedrag van de retentie vast: de juiste cutoff, doorgaan in batches, en niet
 * eindeloos doorbeuken binnen één ronde.
 */
class AgentEventRetentionPollerTest {

    private val clock = Clock.fixed(Instant.parse("2026-07-29T10:00:00Z"), ZoneOffset.UTC)

    private fun settings(
        enabled: Boolean = true,
        retentionDays: Long = 30,
        batchSize: Int = 100,
        maxBatchesPerRun: Int = 5,
    ) = AgentEventRetentionSettings(enabled, retentionDays, batchSize, maxBatchesPerRun)

    /** Onthoudt de aanroepen en levert per aanroep het aantal "verwijderde" rijen uit [batches]. */
    private class FakeEvents(private val batches: MutableList<Int>) : AgentEventRepository {
        val cutoffs = mutableListOf<OffsetDateTime>()
        val batchSizes = mutableListOf<Int>()

        override fun append(agentRunId: Long, kind: String, payload: Map<String, Any?>) = Unit

        override fun deleteOlderThan(olderThan: OffsetDateTime, batchSize: Int): Int {
            cutoffs += olderThan
            batchSizes += batchSize
            return if (batches.isEmpty()) 0 else batches.removeAt(0)
        }
    }

    @Test
    fun `verwijdert events ouder dan de retentieperiode`() {
        val events = FakeEvents(mutableListOf(42))
        val poller = AgentEventRetentionPoller(events, settings(), clock)

        val deleted = poller.cleanupOnce()

        assertEquals(42, deleted)
        assertEquals(OffsetDateTime.parse("2026-06-29T10:00:00Z"), events.cutoffs.single())
        assertEquals(100, events.batchSizes.single())
    }

    @Test
    fun `gaat door zolang een batch vol is en stopt bij de eerste halve batch`() {
        // Vol, vol, half → drie aanroepen, daarna klaar (niet doorgaan tot maxBatchesPerRun).
        val events = FakeEvents(mutableListOf(100, 100, 30))
        val poller = AgentEventRetentionPoller(events, settings(), clock)

        assertEquals(230, poller.cleanupOnce())
        assertEquals(3, events.cutoffs.size)
    }

    @Test
    fun `stopt na maxBatchesPerRun ook als er nog werk is`() {
        // Alleen volle batches: de ronde moet begrensd blijven, de volgende tick gaat verder.
        val events = FakeEvents(MutableList(50) { 100 })
        val poller = AgentEventRetentionPoller(events, settings(maxBatchesPerRun = 5), clock)

        assertEquals(500, poller.cleanupOnce())
        assertEquals(5, events.cutoffs.size)
    }

    @Test
    fun `doet niets als de retentie uit staat`() {
        val events = FakeEvents(mutableListOf(100))
        val poller = AgentEventRetentionPoller(events, settings(enabled = false), clock)

        poller.poll()

        assertTrue(events.cutoffs.isEmpty())
    }

    @Test
    fun `een fout in de opruiming laat de poller niet omvallen`() {
        val failing = object : AgentEventRepository {
            override fun append(agentRunId: Long, kind: String, payload: Map<String, Any?>) = Unit
            override fun deleteOlderThan(olderThan: OffsetDateTime, batchSize: Int): Int =
                error("database even weg")
        }

        AgentEventRetentionPoller(failing, settings(), clock).poll()
    }

    @Test
    fun `de env-defaults zijn 30 dagen en ingeschakeld`() {
        val defaults = AgentEventRetentionSettings.fromEnvironment(emptyMap())

        assertTrue(defaults.enabled)
        assertEquals(30L, defaults.retentionDays)
    }

    @Test
    fun `een onzinnige retentie uit de omgeving wordt begrensd`() {
        val zero = AgentEventRetentionSettings.fromEnvironment(mapOf("SF_AGENT_EVENT_RETENTION_DAYS" to "0"))
        val huge = AgentEventRetentionSettings.fromEnvironment(mapOf("SF_AGENT_EVENT_RETENTION_DAYS" to "999999"))

        assertEquals(1L, zero.retentionDays)
        assertEquals(3650L, huge.retentionDays)
    }
}
