package nl.vdzon.softwarefactory.maintenance.repositories

import com.zaxxer.hikari.HikariDataSource
import nl.vdzon.softwarefactory.config.FactorySecrets
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * Dekt V30 + [MaintenanceCleanupRunRepository] tegen een echte Postgres (Testcontainers), zelfde
 * recept als de `dashboard/repositories`-tests: opslaan (incl. de JSON-details), nieuwste-eerst
 * uitlezen met en zonder projectfilter, een onbekende id, en de retentie-delete.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MaintenanceCleanupRunRepositoryTest {

    private val schema = "software_factory"
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var repository: MaintenanceCleanupRunRepository

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }

        dataSource = HikariDataSource().apply {
            driverClassName = "org.postgresql.Driver"
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 2
        }

        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .placeholders(mapOf("schema" to schema))
            .locations("classpath:db/migration")
            .load()
            .migrate()

        jdbcTemplate = JdbcTemplate(dataSource)
        repository = MaintenanceCleanupRunRepository(
            jdbcTemplate,
            FactorySecrets(
                trackerProjects = emptyList(),
                githubToken = "github-token",
                factoryDatabaseUrl = postgres.jdbcUrl,
                factoryDatabaseSchema = schema,
                kubeconfig = null,
                aiCredentialsDir = null,
                aiOauthToken = null,
                loadedFrom = "test",
            ),
        )
    }

    @BeforeEach
    fun clean() {
        jdbcTemplate.update("DELETE FROM $schema.maintenance_cleanup_runs")
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
        postgres.stop()
    }

    @Test
    fun `een run wordt met details en al opgeslagen en teruggelezen`() {
        val started = now().minusMinutes(2)
        val finished = now().minusMinutes(1)

        val stored = repository.add(
            NewMaintenanceCleanupRun(
                project = "softwarefactory",
                startedAt = started,
                finishedAt = finished,
                releasesDeleted = 2,
                releasesKept = 5,
                packagesDeleted = 1,
                packagesKept = 9,
                dryRun = false,
                error = null,
                deletedReleaseTags = listOf("v1.0.0", "v1.0.1"),
                deletedPackageVersions = listOf("app #11 (sha-oldold)"),
            ),
        )

        val read = repository.get(stored.id)
        // Op instant vergelijken, niet op OffsetDateTime: Postgres geeft de waarde terug in de
        // sessie-zone, dus de offset (en dus equals) kan afwijken van wat we insertten.
        assertEquals(started.toInstant(), read?.startedAt?.toInstant())
        assertEquals(finished.toInstant(), read?.finishedAt?.toInstant())
        assertEquals("softwarefactory", read?.project)
        assertEquals(2, read?.releasesDeleted)
        assertEquals(9, read?.packagesKept)
        assertEquals(false, read?.dryRun)
        assertNull(read?.error)
        assertEquals(listOf("v1.0.0", "v1.0.1"), read?.deletedReleaseTags)
        assertEquals(listOf("app #11 (sha-oldold)"), read?.deletedPackageVersions)
    }

    @Test
    fun `een lege dry-run met foutmelding overleeft de rondgang net zo goed`() {
        val stored = repository.add(
            NewMaintenanceCleanupRun(
                project = "sf",
                startedAt = now(),
                finishedAt = now(),
                releasesDeleted = 0,
                releasesKept = 0,
                packagesDeleted = 0,
                packagesKept = 0,
                dryRun = true,
                error = "GitHub gaf 500",
            ),
        )

        val read = repository.get(stored.id)
        assertEquals(true, read?.dryRun)
        assertEquals("GitHub gaf 500", read?.error)
        assertEquals(emptyList<String>(), read?.deletedReleaseTags)
        assertEquals(emptyList<String>(), read?.deletedPackageVersions)
    }

    @Test
    fun `recent geeft nieuwste eerst en filtert optioneel op project`() {
        val now = now()
        add("sf", now.minusDays(3))
        add("andere", now.minusDays(2))
        add("sf", now.minusDays(1))

        assertEquals(listOf("sf", "andere", "sf"), repository.recent().map { it.project })
        assertEquals(
            listOf(now.minusDays(1).toInstant(), now.minusDays(3).toInstant()),
            repository.recent(project = "sf").map { it.startedAt.toInstant() },
        )
        assertEquals(1, repository.recent(project = "andere").size)
        assertEquals(emptyList<String>(), repository.recent(project = "bestaat-niet").map { it.project })
    }

    @Test
    fun `recent respecteert de limiet`() {
        repeat(4) { add("sf", now().minusHours(it.toLong())) }

        assertEquals(2, repository.recent(limit = 2).size)
    }

    @Test
    fun `een onbekende id levert null op ipv een fout`() {
        assertNull(repository.get(999_999L))
    }

    @Test
    fun `deleteOlderThan ruimt alleen runs van vóór de grens op`() {
        val now = now()
        add("sf", now.minusDays(120))
        add("sf", now.minusDays(91))
        val recent = add("sf", now.minusDays(1))

        val deleted = repository.deleteOlderThan(now.minusDays(90))

        assertEquals(2, deleted)
        val overgebleven = repository.recent()
        assertEquals(1, overgebleven.size)
        assertEquals(recent.id, overgebleven.single().id)
        assertTrue(repository.get(recent.id) != null)
    }

    /** Postgres bewaart TIMESTAMPTZ op microseconde-precisie; nanoseconden zouden nooit terugkomen. */
    private fun now(): OffsetDateTime = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS)

    private fun add(project: String, startedAt: OffsetDateTime) = repository.add(
        NewMaintenanceCleanupRun(
            project = project,
            startedAt = startedAt,
            finishedAt = startedAt.plusMinutes(1),
            releasesDeleted = 1,
            releasesKept = 1,
            packagesDeleted = 0,
            packagesKept = 0,
            dryRun = false,
        ),
    )
}
