package nl.vdzon.softwarefactory.maintenance.repositories

import com.zaxxer.hikari.HikariDataSource
import nl.vdzon.softwarefactory.config.FactorySecrets
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * V31 generaliseert `maintenance_cleanup_runs`. Bestaande GitHub-cleanup-rijen moeten die verbouwing
 * overleven: ze krijgen `kind = 'github-releases'`, opgetelde generieke tellers, en hun
 * release/package-uitsplitsing verhuist naar `details` zodat het detailscherm niets verliest.
 *
 * Daarvoor migreert deze test bewust eerst tot en met V30, schrijft dan een rij in de oude vorm, en
 * draait pas daarna de rest van de migraties.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MaintenanceCleanupRunMigrationTest {

    private val schema = "software_factory"
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var repository: MaintenanceCleanupRunRepository
    /** Postgres bewaart TIMESTAMPTZ op microseconde-precisie; nanoseconden zouden nooit terugkomen. */
    private val startedAt: OffsetDateTime = OffsetDateTime.now().minusDays(2).truncatedTo(ChronoUnit.MICROS)

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

        migrate(target = MigrationVersion.fromVersion("30"))

        val jdbcTemplate = JdbcTemplate(dataSource)
        jdbcTemplate.update(
            """
            INSERT INTO $schema.maintenance_cleanup_runs
                (project, started_at, finished_at, releases_deleted, releases_kept,
                 packages_deleted, packages_kept, dry_run, error, details)
            VALUES ('sf', ?, ?, 2, 5, 1, 9, FALSE, 'GitHub gaf 500', ?)
            """.trimIndent(),
            startedAt,
            startedAt.plusMinutes(1),
            """{"releaseTags":["v1.0.0","v1.0.1"],"packageVersions":["app #11 (sha-oldold)"]}""",
        )

        migrate(target = MigrationVersion.LATEST)

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

    @AfterAll
    fun tearDown() {
        if (this::dataSource.isInitialized) dataSource.close()
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `een bestaande rij wordt zichtbaar als github-releases met opgetelde tellers`() {
        val migrated = repository.recent().single()

        assertEquals(CleanupKinds.GITHUB_RELEASES, migrated.kind)
        assertEquals("sf", migrated.project)
        assertEquals(3, migrated.itemsDeleted)
        assertEquals(14, migrated.itemsKept)
        assertEquals("GitHub gaf 500", migrated.error)
        assertEquals(startedAt.toInstant(), migrated.startedAt.toInstant())
    }

    @Test
    fun `de release- en package-uitsplitsing blijft in details staan voor het detailscherm`() {
        val migrated = repository.recent().single()

        assertEquals(listOf("v1.0.0", "v1.0.1"), migrated.details.releaseTags)
        assertEquals(listOf("app #11 (sha-oldold)"), migrated.details.packageVersions)
        assertEquals(2, migrated.details.releasesDeleted)
        assertEquals(5, migrated.details.releasesKept)
        assertEquals(1, migrated.details.packagesDeleted)
        assertEquals(9, migrated.details.packagesKept)
    }

    @Test
    fun `het soortfilter vindt de gemigreerde rij en negeert andere soorten`() {
        assertEquals(1, repository.recent(kind = CleanupKinds.GITHUB_RELEASES).size)
        assertEquals(0, repository.recent(kind = CleanupKinds.AGENT_RUNS).size)
    }

    private fun migrate(target: MigrationVersion) {
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true)
            .placeholders(mapOf("schema" to schema))
            .locations("classpath:db/migration")
            .target(target)
            .load()
            .migrate()
    }
}
