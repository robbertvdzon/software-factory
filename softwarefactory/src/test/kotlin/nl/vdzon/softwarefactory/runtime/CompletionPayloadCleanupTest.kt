package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.maintenance.CleanupRunGuard
import nl.vdzon.softwarefactory.maintenance.repositories.CleanupKinds
import nl.vdzon.softwarefactory.maintenance.repositories.CleanupTriggers
import nl.vdzon.softwarefactory.runtime.services.CompletionPayloadCleanup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * De payload-purge als losse opruimsoort (SF-1929). Zonder durable-coordinator valt er niets op te
 * ruimen; dat is precies de "uitgezet"-melding die de knop hoort te geven. De handmatige ronde levert
 * dan tóch een logregel op — anders zie je na een klik niets terug.
 */
class CompletionPayloadCleanupTest {

    /** Handgeschreven fake (geen mock-framework in deze repo): lege omgeving = alle defaults. */
    private val config = object : ConfigApi {
        override fun resolvedValues(): Map<String, String> = emptyMap()
    }

    @Test
    fun `zonder durable-coordinator geldt de purge als uitgezet`() {
        assertFalse(CompletionPayloadCleanup(config).cleanupEnabled())
    }

    @Test
    fun `een handmatige ronde levert altijd een rij op, ook zonder werk`() {
        val log = RecordingCleanupLog()

        CompletionPayloadCleanup(config, log).runCleanupRoundLocked(CleanupTriggers.MANUAL)

        val entry = log.entries.single()
        assertEquals(CleanupKinds.COMPLETION_PAYLOADS, entry.kind)
        assertEquals(0, entry.itemsDeleted)
        assertEquals(CleanupTriggers.MANUAL, entry.trigger)
    }

    @Test
    fun `de geplande purge slaat over zolang er al een ronde draait`() {
        val guard = CleanupRunGuard.inMemory()
        val log = RecordingCleanupLog(guard)
        guard.tryStart(CleanupKinds.COMPLETION_PAYLOADS)

        CompletionPayloadCleanup(config, log).purgeScheduled()

        assertEquals(emptyList<RecordingCleanupLog.Entry>(), log.entries)
    }
}
