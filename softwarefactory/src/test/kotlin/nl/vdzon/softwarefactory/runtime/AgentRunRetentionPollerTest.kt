package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AgentRunCompletionRecord
import nl.vdzon.softwarefactory.core.contracts.AgentRunRecord
import nl.vdzon.softwarefactory.core.contracts.AgentRunRepository
import nl.vdzon.softwarefactory.core.contracts.CompletedAgentRun
import nl.vdzon.softwarefactory.maintenance.CleanupRunGuard
import nl.vdzon.softwarefactory.maintenance.repositories.CleanupKinds
import nl.vdzon.softwarefactory.maintenance.repositories.CleanupTriggers
import nl.vdzon.softwarefactory.runtime.services.AgentRunRetentionPoller
import nl.vdzon.softwarefactory.runtime.services.AgentRunRetentionSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * `agent_runs` werd als enige tabel nooit opgeruimd, waardoor het agent-log-scherm runs toonde
 * waarvan de inhoud allang weg was. Deze tests pinnen de retentie vast: de juiste cutoff, batching,
 * de aan/uit-schakelaar, de env-clamping en het wegschrijven in de gedeelde opruim-log.
 *
 * De twee veiligheidsregels (lopende run blijft staan, run met onafgeronde completion blijft staan)
 * zitten in de SQL en worden tegen een echte Postgres bewezen in
 * [nl.vdzon.softwarefactory.orchestrator.repositories.AgentRunRetentionRepositoryTest].
 */
class AgentRunRetentionPollerTest {

    private val clock = Clock.fixed(Instant.parse("2026-08-04T10:00:00Z"), ZoneOffset.UTC)

    private fun settings(
        enabled: Boolean = true,
        retentionDays: Long = 90,
        batchSize: Int = 100,
        maxBatchesPerRun: Int = 5,
    ) = AgentRunRetentionSettings(enabled, retentionDays, batchSize, maxBatchesPerRun)

    /** Onthoudt de aanroepen en levert per aanroep het aantal "verwijderde" rijen uit [batches]. */
    private class FakeRuns(private val batches: MutableList<Int>) : AgentRunRepository {
        val cutoffs = mutableListOf<OffsetDateTime>()
        val batchSizes = mutableListOf<Int>()

        override fun deleteOlderThan(olderThan: OffsetDateTime, batchSize: Int): Int {
            cutoffs += olderThan
            batchSizes += batchSize
            return if (batches.isEmpty()) 0 else batches.removeAt(0)
        }

        // --- niet gebruikt door de retentie ---------------------------------------------------
        override fun recordStarted(
            storyRunId: Long,
            role: AgentRole,
            containerName: String,
            model: String?,
            effort: String?,
            level: Int?,
            workspacePath: String?,
            subtaskKey: String?,
        ): Long = 0

        override fun complete(
            containerName: String,
            completion: AgentRunCompletionRecord,
            endedAt: OffsetDateTime,
        ): CompletedAgentRun? = null

        override fun addUsageToStoryRun(storyRunId: Long, completion: AgentRunCompletionRecord) = Unit
        override fun activeRuns(): List<AgentRunRecord> = emptyList()
        override fun latestForRole(storyRunId: Long, role: AgentRole): AgentRunRecord? = null
        override fun recentForRole(
            storyRunId: Long,
            role: AgentRole,
            limit: Int,
            excludeQuotaFailures: Boolean,
        ): List<AgentRunRecord> = emptyList()

        override fun countForRole(storyRunId: Long, role: AgentRole): Int = 0
        override fun countForRoleAndSubtask(storyRunId: Long, role: AgentRole, subtaskKey: String): Int = 0
    }

    @Test
    fun `verwijdert runs ouder dan de retentieperiode`() {
        val runs = FakeRuns(mutableListOf(42))
        val poller = AgentRunRetentionPoller(runs, settings(), clock)

        val deleted = poller.cleanupOnce()

        assertEquals(42, deleted)
        assertEquals(OffsetDateTime.parse("2026-05-06T10:00:00Z"), runs.cutoffs.single())
        assertEquals(100, runs.batchSizes.single())
    }

    @Test
    fun `gaat door zolang een batch vol is en stopt bij de eerste halve batch`() {
        val runs = FakeRuns(mutableListOf(100, 100, 30))
        val poller = AgentRunRetentionPoller(runs, settings(), clock)

        assertEquals(230, poller.cleanupOnce())
        assertEquals(3, runs.cutoffs.size)
    }

    @Test
    fun `stopt na maxBatchesPerRun ook als er nog werk is`() {
        val runs = FakeRuns(MutableList(50) { 100 })
        val poller = AgentRunRetentionPoller(runs, settings(maxBatchesPerRun = 5), clock)

        assertEquals(500, poller.cleanupOnce())
        assertEquals(5, runs.cutoffs.size)
    }

    @Test
    fun `doet niets als de retentie uit staat`() {
        val runs = FakeRuns(mutableListOf(100))
        val log = RecordingCleanupLog()
        AgentRunRetentionPoller(runs, settings(enabled = false), clock, log).poll()

        assertTrue(runs.cutoffs.isEmpty())
        assertTrue(log.entries.isEmpty())
    }

    @Test
    fun `een fout in de opruiming laat de poller niet omvallen en belandt wel in de opruim-log`() {
        val failing = object : AgentRunRepository by FakeRuns(mutableListOf()) {
            override fun deleteOlderThan(olderThan: OffsetDateTime, batchSize: Int): Int = error("database even weg")
        }
        val log = RecordingCleanupLog()

        AgentRunRetentionPoller(failing, settings(), clock, log).poll()

        assertEquals(listOf(CleanupKinds.AGENT_RUNS to "database even weg"), log.entries.map { it.kind to it.error })
    }

    @Test
    fun `een geslaagde ronde met verwijderingen belandt in de opruim-log`() {
        val log = RecordingCleanupLog()

        AgentRunRetentionPoller(FakeRuns(mutableListOf(7)), settings(), clock, log).poll()

        val entry = log.entries.single()
        assertEquals(CleanupKinds.AGENT_RUNS, entry.kind)
        assertEquals(7, entry.itemsDeleted)
        assertEquals(null, entry.error)
    }

    @Test
    fun `een handmatige ronde draait dezelfde opruiming en logt met trigger manual`() {
        val runs = FakeRuns(mutableListOf(4))
        val log = RecordingCleanupLog()

        AgentRunRetentionPoller(runs, settings(), clock, log).runCleanupRoundLocked(CleanupTriggers.MANUAL)

        val entry = log.entries.single()
        assertEquals(CleanupKinds.AGENT_RUNS, entry.kind)
        assertEquals(4, entry.itemsDeleted)
        assertEquals(CleanupTriggers.MANUAL, entry.trigger)
        assertEquals(1, runs.cutoffs.size)
    }

    @Test
    fun `de geplande tick slaat over zolang er al een ronde van dit soort draait`() {
        val guard = CleanupRunGuard.inMemory()
        val runs = FakeRuns(mutableListOf(9))
        val log = RecordingCleanupLog(guard)
        guard.tryStart(CleanupKinds.AGENT_RUNS)

        AgentRunRetentionPoller(runs, settings(), clock, log).poll()

        assertTrue(runs.cutoffs.isEmpty(), "de opruiming had niet mogen draaien")
        assertTrue(log.entries.isEmpty())
    }

    @Test
    fun `de env-defaults zijn 90 dagen en ingeschakeld`() {
        val defaults = AgentRunRetentionSettings.fromEnvironment(emptyMap())

        assertTrue(defaults.enabled)
        assertEquals(90L, defaults.retentionDays)
        assertEquals(1_000, defaults.batchSize)
        assertEquals(20, defaults.maxBatchesPerRun)
    }

    @Test
    fun `de retentie is uit te zetten met de env-vlag`() {
        val off = AgentRunRetentionSettings.fromEnvironment(mapOf("SF_AGENT_RUN_RETENTION_ENABLED" to "false"))

        assertEquals(false, off.enabled)
    }

    @Test
    fun `onzinnige waarden uit de omgeving worden begrensd`() {
        val zero = AgentRunRetentionSettings.fromEnvironment(
            mapOf("SF_AGENT_RUN_RETENTION_DAYS" to "0", "SF_AGENT_RUN_RETENTION_BATCH_SIZE" to "1"),
        )
        val huge = AgentRunRetentionSettings.fromEnvironment(
            mapOf("SF_AGENT_RUN_RETENTION_DAYS" to "999999", "SF_AGENT_RUN_RETENTION_MAX_BATCHES" to "999999"),
        )

        assertEquals(1L, zero.retentionDays)
        assertEquals(100, zero.batchSize)
        assertEquals(3650L, huge.retentionDays)
        assertEquals(1_000, huge.maxBatchesPerRun)
    }
}
