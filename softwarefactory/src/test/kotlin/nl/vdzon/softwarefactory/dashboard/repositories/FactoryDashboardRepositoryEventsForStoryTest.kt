package nl.vdzon.softwarefactory.dashboard.repositories

import com.zaxxer.hikari.HikariDataSource
import nl.vdzon.softwarefactory.config.FactorySecrets
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer

/**
 * SF-1199: een enkele tester-run kan tientallen KB per event wegschrijven (volledige tool-output);
 * 200 van die events samen bracht de story-detailpagina eerder over de bridge's WebSocket-
 * buffergrens (2 MB) heen, waarna de hele pagina zonder data bleef hangen ("message too big",
 * code 1009). `eventsForStory` kapt de payload nu per event af — deze test pint dat gedrag vast.
 *
 * Draait tegen een echte Postgres (Testcontainers) omdat de query Postgres-specifieke features
 * gebruikt (`JSONB`, `payload::text`, `LEFT(...)`); Flyway bouwt het echte schema op.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FactoryDashboardRepositoryEventsForStoryTest {

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
            "SF-1199",
            "https://github.com/robbertvdzon/software-factory",
        )!!
        agentRunId = jdbcTemplate.queryForObject(
            "INSERT INTO $schema.agent_runs (story_run_id, role, container_name) VALUES (?, ?, ?) RETURNING id",
            Long::class.java,
            storyRunId,
            "tester",
            "test-container",
        )!!

        val hugeText = "x".repeat(40_000)
        insertEvent("claude-user", """{"text":"$hugeText"}""")
        insertEvent("claude-assistant", """{"text":"korte melding"}""")
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
    fun `kapt een grote payload af zodat het totaal onder de bridge-buffergrens blijft`() {
        val events = repository.eventsForStory(storyRunId)

        assertEquals(2, events.size)
        events.forEach { event ->
            assertTrue(
                event.payloadText.length <= 8_000,
                "payload van event ${event.id} is ${event.payloadText.length} tekens, verwacht <= 8000",
            )
        }
        // Totale respons voor deze story ruim onder de 2 MB WebSocket-buffergrens.
        val totalBytes = events.sumOf { it.payloadText.toByteArray(Charsets.UTF_8).size }
        assertTrue(totalBytes < 2 * 1024 * 1024, "totale payload is $totalBytes bytes, verwacht ruim onder 2 MB")
    }

    @Test
    fun `laat een korte payload ongemoeid`() {
        val events = repository.eventsForStory(storyRunId)

        val kort = events.first { it.kind == "claude-assistant" }
        // Postgres' jsonb-serialisatie voegt een spatie na ':' toe — vergelijk daarom op inhoud,
        // niet op de letterlijke, ongewijzigde brontekst.
        assertTrue(kort.payloadText.contains("korte melding"), "payload zou niet afgekapt/gewijzigd moeten zijn")
        assertEquals(kort.payloadText.length, """{"text": "korte melding"}""".length)
    }
}
