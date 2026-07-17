package nl.vdzon.softwarefactory.dashboard.repositories

import com.zaxxer.hikari.HikariDataSource
import nl.vdzon.softwarefactory.config.FactorySecrets
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Repo-test voor SF-1010: `agentRunById` levert de single agent-run op (voor de agent-log-detailweergave,
 * o.a. `outcome`/`endedAt` om te bepalen of de frontend nog moet pollen), of null als de run niet bestaat.
 * Draait tegen een echte Postgres (Testcontainers) zoals [FactoryDashboardRepositoryScreenshotTest].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FactoryDashboardRepositoryAgentRunTest {

    private val schema = "software_factory"
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var repository: FactoryDashboardRepository
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

        val jdbcTemplate = JdbcTemplate(dataSource)
        val secrets = FactorySecrets(
            trackerProjects = emptyList(),
            githubToken = "github-token",
            factoryDatabaseUrl = postgres.jdbcUrl,
            factoryDatabaseSchema = schema,
            kubeconfig = null,
            aiCredentialsDir = null,
            aiOauthToken = null,
            loadedFrom = "test",
        )
        repository = FactoryDashboardRepository(jdbcTemplate, secrets)

        storyRunId = jdbcTemplate.queryForObject(
            "INSERT INTO $schema.story_runs (story_key, target_repo) VALUES (?, ?) RETURNING id",
            Long::class.java,
            "SF-1010",
            "https://github.com/robbertvdzon/software-factory",
        )!!
    }

    @AfterAll
    fun tearDown() {
        if (this::dataSource.isInitialized) dataSource.close()
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `levert de agent-run met outcome en endedAt voor een afgeronde run`() {
        val jdbcTemplate = JdbcTemplate(dataSource)
        val agentRunId = jdbcTemplate.queryForObject(
            """
            INSERT INTO $schema.agent_runs (story_run_id, role, container_name, ended_at, outcome)
            VALUES (?, ?, ?, now(), 'developed')
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            storyRunId,
            "developer",
            "test-container",
        )!!

        val run = repository.agentRunById(agentRunId)

        assertEquals(agentRunId, run?.id)
        assertEquals("developed", run?.outcome)
        assertEquals(false, run?.endedAt == null)
    }

    @Test
    fun `levert null endedAt en outcome voor een nog actieve run`() {
        val jdbcTemplate = JdbcTemplate(dataSource)
        val agentRunId = jdbcTemplate.queryForObject(
            "INSERT INTO $schema.agent_runs (story_run_id, role, container_name) VALUES (?, ?, ?) RETURNING id",
            Long::class.java,
            storyRunId,
            "tester",
            "test-container-2",
        )!!

        val run = repository.agentRunById(agentRunId)

        assertEquals(agentRunId, run?.id)
        assertNull(run?.outcome)
        assertNull(run?.endedAt)
    }

    @Test
    fun `onbekende agentRunId levert null`() {
        val run = repository.agentRunById(-1L)

        assertNull(run)
    }
}
