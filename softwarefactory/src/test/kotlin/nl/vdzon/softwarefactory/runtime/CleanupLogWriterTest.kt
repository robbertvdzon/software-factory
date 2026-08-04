package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.maintenance.repositories.CleanupDetails
import nl.vdzon.softwarefactory.maintenance.repositories.CleanupKinds
import nl.vdzon.softwarefactory.maintenance.repositories.MaintenanceCleanupRunRecord
import nl.vdzon.softwarefactory.maintenance.repositories.MaintenanceCleanupRunRepository
import nl.vdzon.softwarefactory.maintenance.repositories.NewMaintenanceCleanupRun
import nl.vdzon.softwarefactory.runtime.services.CleanupLogWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * De schrijfregel van de gedeelde opruim-log voor de vier factory-brede opruimers: alléén een rij
 * bij verwijderingen of bij een fout. Zonder die regel zet de payload-purge (die aan de
 * completion-recovery van elke ~2 s hangt) het scherm binnen een dag vol lege rijen.
 */
class CleanupLogWriterTest {

    private val clock = Clock.fixed(Instant.parse("2026-08-04T10:00:00Z"), ZoneOffset.UTC)
    private val startedAt = OffsetDateTime.parse("2026-08-04T09:59:00Z")

    @Test
    fun `een ronde zonder verwijderingen en zonder fout levert geen rij op`() {
        val repository = InMemoryRepository()

        CleanupLogWriter(repository, clock).record(CleanupKinds.COMPLETION_PAYLOADS, startedAt, itemsDeleted = 0)

        assertEquals(emptyList<NewMaintenanceCleanupRun>(), repository.added)
    }

    @Test
    fun `een ronde met verwijderingen levert wel een rij op`() {
        val repository = InMemoryRepository()

        CleanupLogWriter(repository, clock).record(CleanupKinds.AGENT_EVENTS, startedAt, itemsDeleted = 12)

        val row = repository.added.single()
        assertEquals(CleanupKinds.AGENT_EVENTS, row.kind)
        assertEquals(12, row.itemsDeleted)
        assertEquals(startedAt.toInstant(), row.startedAt.toInstant())
        assertEquals(OffsetDateTime.now(clock).toInstant(), row.finishedAt.toInstant())
        // Factory-breed: geen project, en geen release/package-uitsplitsing.
        assertNull(row.project)
        assertNull(row.error)
        assertEquals(CleanupDetails(), row.details)
    }

    @Test
    fun `een mislukte ronde levert een rij op, ook zonder verwijderingen`() {
        val repository = InMemoryRepository()

        CleanupLogWriter(repository, clock)
            .record(CleanupKinds.WORKSPACES, startedAt, itemsDeleted = 0, error = "schijf vol")

        val row = repository.added.single()
        assertEquals(CleanupKinds.WORKSPACES, row.kind)
        assertEquals("schijf vol", row.error)
        assertEquals(0, row.itemsDeleted)
    }

    @Test
    fun `een falende insert laat de opruimronde zelf slagen`() {
        val exploding = object : InMemoryRepository() {
            override fun add(run: NewMaintenanceCleanupRun): MaintenanceCleanupRunRecord = error("database weg")
        }

        // Geen exception = de opruimer die dit aanroept draait gewoon door.
        CleanupLogWriter(exploding, clock).record(CleanupKinds.AGENT_RUNS, startedAt, itemsDeleted = 3)

        assertTrue(exploding.added.isEmpty())
    }

    @Test
    fun `describe valt terug op het exceptietype als er geen boodschap is`() {
        val writer = CleanupLogWriter(InMemoryRepository(), clock)

        assertEquals("database weg", writer.describe(IllegalStateException("database weg")))
        assertEquals("IllegalStateException", writer.describe(IllegalStateException()))
    }

    /** Handgeschreven subklasse-fake: deze repo heeft geen mock-framework. */
    private open class InMemoryRepository : MaintenanceCleanupRunRepository(JdbcTemplate(), fakeSecrets()) {
        val added = mutableListOf<NewMaintenanceCleanupRun>()

        override fun add(run: NewMaintenanceCleanupRun): MaintenanceCleanupRunRecord {
            added += run
            return MaintenanceCleanupRunRecord(
                id = added.size.toLong(),
                kind = run.kind,
                project = run.project,
                startedAt = run.startedAt,
                finishedAt = run.finishedAt,
                itemsDeleted = run.itemsDeleted,
                itemsKept = run.itemsKept,
                dryRun = run.dryRun,
                error = run.error,
                details = run.details,
            )
        }

        companion object {
            fun fakeSecrets() = FactorySecrets(
                trackerProjects = emptyList(),
                githubToken = "token",
                factoryDatabaseUrl = "jdbc:fake",
                factoryDatabaseSchema = "fake",
                kubeconfig = null,
                aiCredentialsDir = null,
                aiOauthToken = null,
                loadedFrom = "test",
            )
        }
    }
}
