package nl.vdzon.softwarefactory.orchestrator.repositories

import com.zaxxer.hikari.HikariDataSource
import nl.vdzon.softwarefactory.config.FactorySecrets
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.time.OffsetDateTime

/**
 * De veiligheidsregels van `agent_runs`-retentie zitten in de `WHERE` en zijn alleen tegen een echte
 * Postgres te bewijzen: een verlopen afgeronde run verdwijnt, maar een lopende run (`ended_at IS
 * NULL`) en een run met een onafgeronde durable completion blijven staan — hoe oud ze ook zijn.
 * Testcontainers-recept gelijk aan [nl.vdzon.softwarefactory.dashboard.repositories.FactoryDashboardRepositoryStoryUsageTest].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentRunRetentionRepositoryTest {

    private val schema = "software_factory"
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var repository: JdbcAgentRunRepository
    private var storyRunId: Long = 0

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
        repository = JdbcAgentRunRepository(
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
        jdbcTemplate.update("DELETE FROM $schema.story_runs")
        storyRunId = requireNotNull(
            jdbcTemplate.queryForObject(
                "INSERT INTO $schema.story_runs (story_key, target_repo) VALUES ('SF-1', 'repo') RETURNING id",
                Long::class.java,
            ),
        )
    }

    @AfterAll
    fun tearDown() {
        if (this::dataSource.isInitialized) dataSource.close()
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `een verlopen afgeronde run verdwijnt, een verse run blijft staan`() {
        val oud = insertRun("oud", startedDaysAgo = 120, ended = true)
        val vers = insertRun("vers", startedDaysAgo = 1, ended = true)

        assertEquals(1, repository.deleteOlderThan(cutoff(90), batchSize = 100))
        assertEquals(listOf(vers), remainingIds())
        assertEquals(0, countRuns(oud))
    }

    @Test
    fun `een lopende run blijft staan, ongeacht leeftijd`() {
        val lopend = insertRun("lopend", startedDaysAgo = 400, ended = false)

        assertEquals(0, repository.deleteOlderThan(cutoff(90), batchSize = 100))
        assertEquals(listOf(lopend), remainingIds())
    }

    @Test
    fun `een run met een onafgeronde completion blijft staan, een terminale completion blokkeert niet`() {
        val pending = insertRun("pending", startedDaysAgo = 200, ended = true).also { completion(it, "PENDING") }
        val inProgress = insertRun("bezig", startedDaysAgo = 200, ended = true).also { completion(it, "IN_PROGRESS") }
        val retryable = insertRun("retry", startedDaysAgo = 200, ended = true).also { completion(it, "FAILED_RETRYABLE") }
        val done = insertRun("klaar", startedDaysAgo = 200, ended = true).also { completion(it, "COMPLETED") }
        val permanent = insertRun("stuk", startedDaysAgo = 200, ended = true).also { completion(it, "FAILED_PERMANENT") }

        assertEquals(2, repository.deleteOlderThan(cutoff(90), batchSize = 100))
        assertEquals(listOf(pending, inProgress, retryable), remainingIds())
        assertEquals(0, countRuns(done))
        assertEquals(0, countRuns(permanent))
    }

    @Test
    fun `de batchgrootte begrenst hoeveel er per aanroep weggaat`() {
        repeat(5) { insertRun("oud-$it", startedDaysAgo = 200, ended = true) }

        assertEquals(2, repository.deleteOlderThan(cutoff(90), batchSize = 2))
        assertEquals(2, repository.deleteOlderThan(cutoff(90), batchSize = 2))
        assertEquals(1, repository.deleteOlderThan(cutoff(90), batchSize = 2))
        assertEquals(0, repository.deleteOlderThan(cutoff(90), batchSize = 2))
    }

    @Test
    fun `events en completions van een verwijderde run gaan mee via de cascade`() {
        val run = insertRun("oud", startedDaysAgo = 200, ended = true)
        completion(run, "COMPLETED")
        jdbcTemplate.update(
            "INSERT INTO $schema.agent_events (agent_run_id, kind, payload) VALUES (?, 'log', '{}'::jsonb)",
            run,
        )

        repository.deleteOlderThan(cutoff(90), batchSize = 100)

        assertEquals(0, count("agent_events", "agent_run_id", run))
        assertEquals(0, count("agent_run_completions", "agent_run_id", run))
    }

    private fun cutoff(days: Long): OffsetDateTime = OffsetDateTime.now().minusDays(days)

    private fun insertRun(containerName: String, startedDaysAgo: Long, ended: Boolean): Long =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                INSERT INTO $schema.agent_runs (story_run_id, role, container_name, started_at, ended_at)
                VALUES (?, 'developer', ?, ?, ?)
                RETURNING id
                """.trimIndent(),
                Long::class.java,
                storyRunId,
                containerName,
                OffsetDateTime.now().minusDays(startedDaysAgo),
                if (ended) OffsetDateTime.now().minusDays(startedDaysAgo).plusMinutes(5) else null,
            ),
        )

    private fun completion(agentRunId: Long, status: String) {
        jdbcTemplate.update(
            """
            INSERT INTO $schema.agent_run_completions
                (agent_run_id, story_run_id, story_key, container_name, payload_hash, status)
            VALUES (?, ?, 'SF-1', 'c-' || ?, 'hash', ?)
            """.trimIndent(),
            agentRunId,
            storyRunId,
            agentRunId,
            status,
        )
    }

    private fun remainingIds(): List<Long> =
        jdbcTemplate.queryForList("SELECT id FROM $schema.agent_runs ORDER BY id", Long::class.java)

    private fun countRuns(id: Long): Int = count("agent_runs", "id", id)

    private fun count(table: String, column: String, value: Long): Int =
        requireNotNull(
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $schema.$table WHERE $column = ?", Int::class.java, value),
        )
}
