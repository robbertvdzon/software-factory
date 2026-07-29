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
        insertAgentRun(jdbcTemplate, codeRun, "developer", model = "claude-opus-4-8", durationMs = 120_000, input = 100, cacheRead = 1_000, cacheCreation = 200, output = 50)
        insertAgentRun(jdbcTemplate, codeRun, "reviewer", model = "claude-opus-4-8", durationMs = 60_000, input = 10, cacheRead = 2_000, cacheCreation = 300, output = 25)
        insertAgentRun(jdbcTemplate, mergeRun, "documenter", model = "claude-haiku-4-5", durationMs = 30_000, input = 5, cacheRead = 500, cacheCreation = 0, output = 5)

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
        // Ontdubbeld en alfabetisch: twee agents draaiden op hetzelfde model, één op een ander.
        assertEquals(listOf("claude-haiku-4-5", "claude-opus-4-8"), usage.models)
        assertEquals(0.75, usage.costUsdEst, 0.0001)
    }

    @Test
    fun `storyUsage voor één story geeft dezelfde totalen als de lijstvariant`() {
        assertEquals(repository.storyUsageTotals()["SF-1"], repository.storyUsage("SF-1"))
    }

    @Test
    fun `een story zonder agent-runs komt niet in de uitkomst voor`() {
        assertNull(repository.storyUsageTotals()["SF-2"])
        assertNull(repository.storyUsage("SF-2"))
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
        model: String,
        durationMs: Long,
        input: Long,
        cacheRead: Long,
        cacheCreation: Long,
        output: Long,
        costUsd: Double = 0.25,
    ) {
        jdbc.update(
            """
            INSERT INTO $schema.agent_runs
                (story_run_id, role, container_name, model, started_at, ended_at, duration_ms,
                 input_tokens, cache_read_input_tokens, cache_creation_input_tokens, output_tokens,
                 cost_usd_est)
            VALUES (?, ?, ?, ?, now() - interval '1 hour', now() - interval '30 minutes', ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            storyRunId, role, "test-$role", model, durationMs, input, cacheRead, cacheCreation, output, costUsd,
        )
    }
}
