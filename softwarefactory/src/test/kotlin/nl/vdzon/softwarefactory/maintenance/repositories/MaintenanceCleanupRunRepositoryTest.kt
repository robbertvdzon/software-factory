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
 * Dekt V30/V31 + [MaintenanceCleanupRunRepository] tegen een echte Postgres (Testcontainers),
 * zelfde recept als de `dashboard/repositories`-tests: opslaan (incl. de JSON-details) per soort,
 * nieuwste-eerst uitlezen met en zonder project-/soortfilter, factory-brede rondes zonder project,
 * een onbekende id, en de retentie-delete.
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
    fun `een github-releases-run wordt met details en al opgeslagen en teruggelezen`() {
        val started = now().minusMinutes(2)
        val finished = now().minusMinutes(1)

        val stored = repository.add(
            NewMaintenanceCleanupRun(
                kind = CleanupKinds.GITHUB_RELEASES,
                project = "softwarefactory",
                startedAt = started,
                finishedAt = finished,
                itemsDeleted = 3,
                itemsKept = 14,
                dryRun = false,
                error = null,
                details = CleanupDetails(
                    releaseTags = listOf("v1.0.0", "v1.0.1"),
                    packageVersions = listOf("app #11 (sha-oldold)"),
                    releasesDeleted = 2,
                    releasesKept = 5,
                    packagesDeleted = 1,
                    packagesKept = 9,
                ),
            ),
        )

        val read = repository.get(stored.id)
        // Op instant vergelijken, niet op OffsetDateTime: Postgres geeft de waarde terug in de
        // sessie-zone, dus de offset (en dus equals) kan afwijken van wat we insertten.
        assertEquals(started.toInstant(), read?.startedAt?.toInstant())
        assertEquals(finished.toInstant(), read?.finishedAt?.toInstant())
        assertEquals(CleanupKinds.GITHUB_RELEASES, read?.kind)
        assertEquals("softwarefactory", read?.project)
        assertEquals(3, read?.itemsDeleted)
        assertEquals(14, read?.itemsKept)
        assertEquals(false, read?.dryRun)
        assertNull(read?.error)
        assertEquals(listOf("v1.0.0", "v1.0.1"), read?.details?.releaseTags)
        assertEquals(listOf("app #11 (sha-oldold)"), read?.details?.packageVersions)
        assertEquals(5, read?.details?.releasesKept)
        assertEquals(9, read?.details?.packagesKept)
    }

    @Test
    fun `elk van de vijf soorten overleeft de rondgang, factory-breed zonder project`() {
        CleanupKinds.ALL.forEach { kind ->
            val stored = repository.add(
                NewMaintenanceCleanupRun(
                    kind = kind,
                    startedAt = now(),
                    finishedAt = now(),
                    itemsDeleted = 7,
                ),
            )

            val read = repository.get(stored.id)
            assertEquals(kind, read?.kind)
            // Factory-brede rondes hebben geen project: NULL moet echt NULL terugkomen.
            assertNull(read?.project)
            assertEquals(7, read?.itemsDeleted)
            assertEquals(0, read?.itemsKept)
            assertEquals(emptyList<String>(), read?.details?.releaseTags)
        }
    }

    @Test
    fun `de trigger overleeft de rondgang en staat standaard op scheduled`() {
        val gepland = repository.add(
            NewMaintenanceCleanupRun(
                kind = CleanupKinds.AGENT_RUNS,
                startedAt = now(),
                finishedAt = now(),
                itemsDeleted = 1,
            ),
        )
        val handmatig = repository.add(
            NewMaintenanceCleanupRun(
                kind = CleanupKinds.AGENT_RUNS,
                startedAt = now(),
                finishedAt = now(),
                itemsDeleted = 0,
                trigger = CleanupTriggers.MANUAL,
            ),
        )

        assertEquals(CleanupTriggers.SCHEDULED, repository.get(gepland.id)?.trigger)
        assertEquals(CleanupTriggers.MANUAL, repository.get(handmatig.id)?.trigger)
        assertEquals(CleanupTriggers.MANUAL, handmatig.trigger)
    }

    @Test
    fun `een lege dry-run met foutmelding overleeft de rondgang net zo goed`() {
        val stored = repository.add(
            NewMaintenanceCleanupRun(
                kind = CleanupKinds.GITHUB_RELEASES,
                project = "sf",
                startedAt = now(),
                finishedAt = now(),
                itemsDeleted = 0,
                dryRun = true,
                error = "GitHub gaf 500",
            ),
        )

        val read = repository.get(stored.id)
        assertEquals(true, read?.dryRun)
        assertEquals("GitHub gaf 500", read?.error)
        assertEquals(emptyList<String>(), read?.details?.releaseTags)
        assertEquals(emptyList<String>(), read?.details?.packageVersions)
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
    fun `recent filtert optioneel op soort en combineert dat met het projectfilter`() {
        val now = now()
        add("sf", now.minusDays(3))
        add(null, now.minusDays(2), kind = CleanupKinds.AGENT_RUNS)
        add(null, now.minusDays(1), kind = CleanupKinds.AGENT_EVENTS)

        assertEquals(3, repository.recent().size)
        assertEquals(
            listOf(CleanupKinds.AGENT_RUNS),
            repository.recent(kind = CleanupKinds.AGENT_RUNS).map { it.kind },
        )
        assertEquals(emptyList<String>(), repository.recent(kind = CleanupKinds.WORKSPACES).map { it.kind })
        // Project én soort samen: beide filters moeten gelden, niet één van beide.
        assertEquals(1, repository.recent(project = "sf", kind = CleanupKinds.GITHUB_RELEASES).size)
        assertEquals(0, repository.recent(project = "sf", kind = CleanupKinds.AGENT_RUNS).size)
    }

    @Test
    fun `recent respecteert de limiet`() {
        repeat(4) { add("sf", now().minusHours(it.toLong())) }

        assertEquals(2, repository.recent(limit = 2).size)
    }

    @Test
    fun `latestPerKindAndProject geeft per soort de laatste ronde en per project voor github-releases`() {
        val now = now()
        add("sf", now.minusDays(3))
        val laatsteSf = add("sf", now.minusDays(1))
        val laatsteAndere = add("andere", now.minusDays(2))
        add(null, now.minusDays(5), kind = CleanupKinds.AGENT_RUNS)
        val laatsteAgentRuns = add(null, now.minusHours(2), kind = CleanupKinds.AGENT_RUNS)

        val summary = repository.latestPerKindAndProject()

        // Twee github-releases-projecten + één factory-brede soort; de rest heeft nog niets gelogd.
        assertEquals(3, summary.size)
        assertEquals(
            setOf(laatsteSf.id, laatsteAndere.id, laatsteAgentRuns.id),
            summary.map { it.id }.toSet(),
        )
        val sfRegel = summary.single { it.project == "sf" }
        assertEquals(laatsteSf.startedAt.toInstant(), sfRegel.startedAt.toInstant())
        assertEquals(CleanupKinds.GITHUB_RELEASES, sfRegel.kind)
        // Factory-brede soort: één regel zonder project, ook al zijn er meerdere rondes.
        assertEquals(1, summary.count { it.kind == CleanupKinds.AGENT_RUNS })
        assertNull(summary.single { it.kind == CleanupKinds.AGENT_RUNS }.project)
    }

    @Test
    fun `latestPerKindAndProject kijkt voorbij de lijstlimiet van recent`() {
        val now = now()
        // Eén rustige soort met een oude ronde, daarna ruim meer dan DEFAULT_LIMIT (200) drukke rondes.
        val rustige = add(null, now.minusDays(30), kind = CleanupKinds.WORKSPACES)
        repeat(220) { add(null, now.minusMinutes(it.toLong()), kind = CleanupKinds.AGENT_EVENTS) }

        // De afgekapte lijst kent de rustige soort niet meer; de samenvatting wél.
        assertTrue(repository.recent().none { it.kind == CleanupKinds.WORKSPACES })
        assertEquals(rustige.id, repository.latestPerKindAndProject().single { it.kind == CleanupKinds.WORKSPACES }.id)
    }

    @Test
    fun `latestPerKindAndProject is leeg zolang er niets gelogd is`() {
        assertEquals(emptyList<Long>(), repository.latestPerKindAndProject().map { it.id })
    }

    @Test
    fun `een onbekende id levert null op ipv een fout`() {
        assertNull(repository.get(999_999L))
    }

    @Test
    fun `deleteOlderThan ruimt alle soorten van vóór de grens op`() {
        val now = now()
        add("sf", now.minusDays(120))
        add(null, now.minusDays(91), kind = CleanupKinds.AGENT_RUNS)
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

    private fun add(
        project: String?,
        startedAt: OffsetDateTime,
        kind: String = CleanupKinds.GITHUB_RELEASES,
    ) = repository.add(
        NewMaintenanceCleanupRun(
            kind = kind,
            project = project,
            startedAt = startedAt,
            finishedAt = startedAt.plusMinutes(1),
            itemsDeleted = 1,
            itemsKept = 1,
        ),
    )
}
