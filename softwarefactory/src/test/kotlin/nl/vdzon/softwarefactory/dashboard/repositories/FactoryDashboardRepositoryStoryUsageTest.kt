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
 * [FactoryDashboardRepository.storyUsageTotals] telt het verbruik van een story op over ál z'n
 * story-runs. Dat is precies waar het stories-overzicht op vastliep: het toonde één (lopende) run,
 * en die is voor een afgeronde story leeg of afwezig — vandaar "- tokens · $0.00" bij elke story.
 * Draait tegen een echte Postgres (Testcontainers), zelfde recept als
 * [FactoryDashboardRepositoryLatestStoryRunTest].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FactoryDashboardRepositoryStoryUsageTest {

    private val schema = "software_factory"
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var repository: FactoryDashboardRepository

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

        // SF-1 heeft twee runs: een afgesloten code-run met twee agents, en een latere (nog open)
        // merge-run met één agent — de vorm die het overzicht op nul liet staan.
        val codeRun = insertStoryRun(jdbcTemplate, "SF-1", endedMinutesAgo = 20)
        val mergeRun = insertStoryRun(jdbcTemplate, "SF-1", endedMinutesAgo = null)
        insertAgentRun(jdbcTemplate, codeRun, "developer", durationMs = 120_000, input = 100, cacheRead = 1_000, cacheCreation = 200, output = 50)
        insertAgentRun(jdbcTemplate, codeRun, "reviewer", durationMs = 60_000, input = 10, cacheRead = 2_000, cacheCreation = 300, output = 25)
        insertAgentRun(jdbcTemplate, mergeRun, "documenter", durationMs = 30_000, input = 5, cacheRead = 500, cacheCreation = 0, output = 5)

        // SF-2 heeft alleen een run zonder agents — mag niet in de uitkomst opduiken.
        insertStoryRun(jdbcTemplate, "SF-2", endedMinutesAgo = 5)
    }

    @AfterAll
    fun tearDown() {
        if (this::dataSource.isInitialized) dataSource.close()
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `telt agents, looptijd en tokens op over alle runs van de story`() {
        val usage = repository.storyUsageTotals().getValue("SF-1")

        assertEquals(3, usage.agentRuns)
        assertEquals(210_000L, usage.agentDurationMs)
        assertEquals(115L, usage.inputTokens)
        assertEquals(3_500L, usage.cacheReadTokens)
        assertEquals(500L, usage.cacheCreationTokens)
        assertEquals(80L, usage.outputTokens)
        assertEquals(4_195L, usage.totalTokens)
    }

    @Test
    fun `een story zonder agent-runs komt niet in de uitkomst voor`() {
        assertNull(repository.storyUsageTotals()["SF-2"])
    }

    private fun insertStoryRun(jdbc: JdbcTemplate, storyKey: String, endedMinutesAgo: Int?): Long =
        jdbc.queryForObject(
            """
            INSERT INTO $schema.story_runs (story_key, target_repo, started_at, ended_at)
            VALUES (?, 'https://github.com/robbertvdzon/software-factory', now() - interval '1 hour',
                    ${if (endedMinutesAgo == null) "NULL" else "now() - interval '$endedMinutesAgo minutes'"})
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            storyKey,
        )!!

    private fun insertAgentRun(
        jdbc: JdbcTemplate,
        storyRunId: Long,
        role: String,
        durationMs: Long,
        input: Long,
        cacheRead: Long,
        cacheCreation: Long,
        output: Long,
    ) {
        jdbc.update(
            """
            INSERT INTO $schema.agent_runs
                (story_run_id, role, container_name, started_at, ended_at, duration_ms,
                 input_tokens, cache_read_input_tokens, cache_creation_input_tokens, output_tokens)
            VALUES (?, ?, ?, now() - interval '1 hour', now() - interval '30 minutes', ?, ?, ?, ?, ?)
            """.trimIndent(),
            storyRunId, role, "test-$role", durationMs, input, cacheRead, cacheCreation, output,
        )
    }
}
