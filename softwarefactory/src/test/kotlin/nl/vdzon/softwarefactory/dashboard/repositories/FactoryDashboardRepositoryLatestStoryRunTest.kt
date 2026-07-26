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
 * Reproduceert het SF-1281-scenario: een afgeronde story heeft meerdere `story_runs`-rijen (de
 * eigenlijke code-run mét branch/PR, gevolgd door latere summary-/merge-/deploy-subtaakruns zonder
 * eigen branch). [FactoryDashboardRepository.latestStoryRun] bevoordeelt terecht een nog-open latere
 * run (voor "toon de laatste activiteit"), maar dat liet storyDetail() voor zo'n story zonder
 * branchName/prNumber — precies wat de Buildstraat-pagina nodig heeft. Draait tegen een echte
 * Postgres (Testcontainers), zelfde recept als [FactoryDashboardRepositoryEventsForStoryTest].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FactoryDashboardRepositoryLatestStoryRunTest {

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

        // Zelfde volgorde/vorm als de echte SF-1281-rijen: de code-run sluit af mét branch/PR, dan
        // een 'done'-subtaakrun, dan een nog-open ('actieve') subtaakrun — beide zonder branch.
        jdbcTemplate.update(
            """
            INSERT INTO $schema.story_runs
                (story_key, target_repo, branch_name, pr_number, pr_url, final_status, started_at, ended_at)
            VALUES
                ('SF-1281', 'https://github.com/robbertvdzon/software-factory', 'ai/SF-1281', 180,
                 'https://github.com/robbertvdzon/software-factory/pull/180', 'merged',
                 now() - interval '30 minutes', now() - interval '20 minutes')
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO $schema.story_runs (story_key, target_repo, final_status, started_at, ended_at)
            VALUES ('SF-1281', '', 'done', now() - interval '19 minutes', now() - interval '18 minutes')
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO $schema.story_runs (story_key, target_repo, started_at, ended_at)
            VALUES ('SF-1281', '', now() - interval '17 minutes', NULL)
            """.trimIndent(),
        )
    }

    @AfterAll
    fun tearDown() {
        if (this::dataSource.isInitialized) dataSource.close()
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `latestStoryRun bevoordeelt de nog-open latere subtaakrun, zonder branch`() {
        val run = repository.latestStoryRun("SF-1281")

        assertEquals(null, run?.endedAt)
        assertNull(run?.branchName)
    }

    @Test
    fun `latestStoryRunWithBranch vindt de oudere code-run met branch en PR`() {
        val run = repository.latestStoryRunWithBranch("SF-1281")

        assertEquals("ai/SF-1281", run?.branchName)
        assertEquals(180, run?.prNumber)
        assertEquals("https://github.com/robbertvdzon/software-factory/pull/180", run?.prUrl)
    }

    @Test
    fun `latestStoryRunWithBranch levert null op als geen enkele run een branch heeft`() {
        assertNull(repository.latestStoryRunWithBranch("SF-ONBEKEND"))
    }
}
