package nl.vdzon.softwarefactory.nightly

import com.zaxxer.hikari.HikariDataSource
import nl.vdzon.softwarefactory.config.FactorySecrets
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * Round-trip-tests voor de nightly-repositories tegen een echte Postgres (Testcontainers); Flyway
 * bouwt het schema via de echte migratie `V11__nightly_scheduler.sql`. Vereist Docker en draait dus
 * in de pipeline, niet in de offline dev-omgeving.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NightlyRepositoriesTest {

    private val schema = "software_factory"
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var settingsRepo: NightlySettingsRepository
    private lateinit var runRepo: NightlyRunRepository
    private lateinit var jobRepo: NightlyRunJobRepository

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

        jdbc = JdbcTemplate(dataSource)
        val secrets = FactorySecrets(
            youTrackBaseUrl = "https://youtrack.example",
            youTrackToken = "token",
            youTrackProjects = emptyList(),
            githubToken = "github-token",
            factoryDatabaseUrl = postgres.jdbcUrl,
            factoryDatabaseSchema = schema,
            kubeconfig = null,
            aiCredentialsDir = null,
            aiOauthToken = null,
            loadedFrom = "test",
        )
        settingsRepo = NightlySettingsRepository(jdbc, secrets)
        runRepo = NightlyRunRepository(jdbc, secrets)
        jobRepo = NightlyRunJobRepository(jdbc, secrets)
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
        postgres.stop()
    }

    @Test
    fun `settings default to disabled with neutral times`() {
        // De migratie seedt de single row met de defaults.
        val read = settingsRepo.read()
        assertFalse(read.enabled)
        assertEquals(LocalTime.of(2, 0), read.startTime)
        assertEquals(LocalTime.of(7, 0), read.summaryTime)
    }

    @Test
    fun `settings round-trip persists enabled and times`() {
        settingsRepo.save(NightlySettings(enabled = true, startTime = LocalTime.of(1, 30), summaryTime = LocalTime.of(6, 45)))
        val read = settingsRepo.read()
        assertTrue(read.enabled)
        assertEquals(LocalTime.of(1, 30), read.startTime)
        assertEquals(LocalTime.of(6, 45), read.summaryTime)

        // Een tweede save overschrijft dezelfde enkele rij (geen tweede rij).
        settingsRepo.save(NightlySettings(enabled = false, startTime = LocalTime.of(2, 0), summaryTime = LocalTime.of(7, 0)))
        val count = jdbc.queryForObject("SELECT COUNT(*) FROM $schema.nightly_settings", Int::class.java)
        assertEquals(1, count)
        assertFalse(settingsRepo.read().enabled)
    }

    @Test
    fun `multiple runs per day get distinct ids and track kind`() {
        val date = LocalDate.of(2026, 6, 1)
        val now = OffsetDateTime.parse("2026-06-01T00:05:00Z")
        val scheduled = runRepo.create(date, now, kind = NightlyRunKind.SCHEDULED)
        val manual = runRepo.create(date, now.plusMinutes(30), kind = NightlyRunKind.MANUAL)
        assertNotEquals(scheduled.id, manual.id)
        assertEquals(NightlyRunKind.SCHEDULED, scheduled.kind)
        assertEquals(NightlyRunKind.MANUAL, manual.kind)
        assertEquals(date, scheduled.runDate)
        // Twee rijen voor dezelfde run_date toegestaan; forDate geeft de meest recente.
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM $schema.nightly_run WHERE run_date = ?", Int::class.java, java.sql.Date.valueOf(date)))
        assertEquals(manual.id, runRepo.forDate(date)?.id)
        assertTrue(runRepo.hasScheduledRunOn(date))
    }

    @Test
    fun `hasScheduledRunOn is false when only a manual run exists`() {
        val date = LocalDate.of(2026, 5, 9)
        runRepo.create(date, OffsetDateTime.parse("2026-05-09T12:00:00Z"), kind = NightlyRunKind.MANUAL)
        assertFalse(runRepo.hasScheduledRunOn(date))
    }

    @Test
    fun `run jobs round-trip through their lifecycle`() {
        val date = LocalDate.of(2026, 6, 2)
        val run = runRepo.create(date, OffsetDateTime.parse("2026-06-02T00:05:00Z"))
        val jobId = jobRepo.add(run.id, project = "sample", jobName = "dependencies", title = "Bump deps")

        val pending = jobRepo.get(jobId)!!
        assertEquals(NightlyJobStatus.PENDING, pending.status)
        assertNull(pending.storyKey)

        jobRepo.markRunning(jobId, storyKey = "SF-999", startedAt = OffsetDateTime.parse("2026-06-02T00:06:00Z"))
        val running = jobRepo.get(jobId)!!
        assertEquals(NightlyJobStatus.RUNNING, running.status)
        assertEquals("SF-999", running.storyKey)
        assertNotNull(running.startedAt)

        jobRepo.markTerminal(jobId, NightlyJobStatus.DONE, OffsetDateTime.parse("2026-06-02T00:30:00Z"))
        val done = jobRepo.get(jobId)!!
        assertTrue(NightlyJobStatus.isTerminal(done.status))
        assertNull(done.error)

        assertEquals(listOf(jobId), jobRepo.forRunAndProject(run.id, "sample").map { it.id })
    }

    @Test
    fun `run status and summary flags update`() {
        val date = LocalDate.of(2026, 6, 3)
        val run = runRepo.create(date, OffsetDateTime.parse("2026-06-03T00:05:00Z"))
        val sentAt = OffsetDateTime.parse("2026-06-03T05:00:00Z")
        runRepo.markSummarySent(run.id, sentAt)
        runRepo.updateStatus(run.id, NightlyRunStatus.ENDED, endedAt = OffsetDateTime.parse("2026-06-03T06:00:00Z"))

        val ended = runRepo.get(run.id)!!
        assertEquals(NightlyRunStatus.ENDED, ended.status)
        assertNotNull(ended.endedAt)
        assertNotNull(ended.summarySentAt)
        // De laatste run blijft vindbaar, maar is niet langer "actief".
        assertEquals(run.id, runRepo.latestRun()?.id)
    }
}
