package nl.vdzon.softwarefactory.web.repositories

import com.zaxxer.hikari.HikariDataSource
import nl.vdzon.softwarefactory.config.FactorySecrets
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Repo-test voor SF-230: `screenshotEventsForStory` mag uitsluitend events met
 * `kind = 'tester-screenshot'` teruggeven. Gewone agent-log-events die toevallig
 * "screenshot" of ".png" in hun JSON-payload hebben (claude-user, docker-stdout,
 * documenter-output) horen er NIET meer in voor te komen.
 *
 * Draait tegen een echte Postgres (Testcontainers) omdat de query Postgres-specifieke
 * features gebruikt (`JSONB`, `payload::text`); Flyway bouwt het echte schema op.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FactoryDashboardRepositoryScreenshotTest {

    private val schema = "software_factory"
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var repository: FactoryDashboardRepository
    private var storyRunId: Long = 0
    private var agentRunId: Long = 0

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
        repository = FactoryDashboardRepository(jdbcTemplate, secrets)

        storyRunId = jdbcTemplate.queryForObject(
            "INSERT INTO $schema.story_runs (story_key, target_repo) VALUES (?, ?) RETURNING id",
            Long::class.java,
            "SF-230",
            "https://github.com/robbertvdzon/software-factory",
        )!!
        agentRunId = jdbcTemplate.queryForObject(
            "INSERT INTO $schema.agent_runs (story_run_id, role, container_name) VALUES (?, ?, ?) RETURNING id",
            Long::class.java,
            storyRunId,
            "tester",
            "test-container",
        )!!

        // Twee echte tester-screenshots.
        insertEvent("tester-screenshot", """{"path":"screens/login.png","label":"Login"}""")
        insertEvent("tester-screenshot", """{"path":"screens/dashboard.png","label":"Dashboard"}""")

        // Log-events die "screenshot"/".png" bevatten maar GEEN tester-screenshot zijn:
        // onder de oude LIKE-filter kwamen die ten onrechte mee.
        insertEvent("claude-user", """{"text":"please take a screenshot of the page"}""")
        insertEvent("docker-stdout", """{"line":"saved output to result.png"}""")
        insertEvent("documenter-output", """{"note":"added screenshot section, see foo.png"}""")
    }

    @AfterAll
    fun tearDown() {
        if (this::dataSource.isInitialized) dataSource.close()
        if (this::postgres.isInitialized) postgres.stop()
    }

    private fun insertEvent(kind: String, payloadJson: String) {
        JdbcTemplate(dataSource).update(
            "INSERT INTO $schema.agent_events (agent_run_id, kind, payload) VALUES (?, ?, ?::jsonb)",
            agentRunId,
            kind,
            payloadJson,
        )
    }

    @Test
    fun `returns only tester-screenshot events and excludes log events mentioning screenshot or png`() {
        val events = repository.screenshotEventsForStory(storyRunId)

        assertEquals(2, events.size, "alleen tester-screenshot-events horen terug te komen")
        assertEquals(setOf("tester-screenshot"), events.map { it.kind }.toSet())
        // De drie nep-PNG/log-events mogen niet voorkomen.
        val kinds = events.map { it.kind }
        listOf("claude-user", "docker-stdout", "documenter-output").forEach {
            assert(it !in kinds) { "$it zou niet als screenshot teruggegeven mogen worden" }
        }
    }

    @Test
    fun `orders events by ts and id descending`() {
        val events = repository.screenshotEventsForStory(storyRunId)

        val ids = events.map { it.id }
        assertEquals(ids.sortedDescending(), ids, "events horen op id DESC gesorteerd te zijn")
    }

    @Test
    fun `story without tester-screenshots returns empty list`() {
        val jdbcTemplate = JdbcTemplate(dataSource)
        val emptyStoryRunId = jdbcTemplate.queryForObject(
            "INSERT INTO $schema.story_runs (story_key, target_repo) VALUES (?, ?) RETURNING id",
            Long::class.java,
            "SF-220",
            "https://github.com/robbertvdzon/software-factory",
        )!!
        val emptyAgentRunId = jdbcTemplate.queryForObject(
            "INSERT INTO $schema.agent_runs (story_run_id, role, container_name) VALUES (?, ?, ?) RETURNING id",
            Long::class.java,
            emptyStoryRunId,
            "documenter",
            "test-container",
        )!!
        jdbcTemplate.update(
            "INSERT INTO $schema.agent_events (agent_run_id, kind, payload) VALUES (?, ?, ?::jsonb)",
            emptyAgentRunId,
            "documenter-output",
            """{"note":"see screenshot at foo.png"}""",
        )

        val events = repository.screenshotEventsForStory(emptyStoryRunId)

        assertEquals(emptyList<String>(), events.map { it.kind })
    }
}
