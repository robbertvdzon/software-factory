package nl.vdzon.softwarefactory.roadmap

import com.zaxxer.hikari.HikariDataSource
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.roadmap.models.CreateRoadmapEpicCommand
import nl.vdzon.softwarefactory.roadmap.models.UpdateRoadmapEpicCommand
import nl.vdzon.softwarefactory.roadmap.repositories.RoadmapRepository
import nl.vdzon.softwarefactory.roadmap.services.RoadmapService
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoadmapPersistenceTest {
    private val schema = "software_factory"
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var repository: RoadmapRepository
    private lateinit var service: RoadmapService
    private lateinit var transaction: TransactionTemplate

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
        repository = RoadmapRepository(
            jdbc,
            FactorySecrets(
                trackerProjects = emptyList(),
                githubToken = "test",
                factoryDatabaseUrl = postgres.jdbcUrl,
                factoryDatabaseSchema = schema,
                kubeconfig = null,
                aiCredentialsDir = null,
                aiOauthToken = null,
                loadedFrom = "test",
            ),
        )
        service = RoadmapService(repository, ApplicationEventPublisher { })
        transaction = TransactionTemplate(DataSourceTransactionManager(dataSource))
    }

    @BeforeEach
    fun clean() {
        jdbc.update("DELETE FROM $schema.roadmap_epic_dependencies")
        jdbc.update("DELETE FROM $schema.roadmap_epics")
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
        postgres.stop()
    }

    @Test
    fun `klant-rank verschuift tussenliggende epics en blijft uniek`() {
        val first = create("Eerste")
        val second = create("Tweede")
        val third = create("Derde")

        inTransaction {
            service.updateEpic(
                third.id,
                update(third.title, customerRank = 1),
            )
        }

        val byTitle = service.roadmap().epics.associateBy { it.title }
        assertEquals(1, byTitle.getValue("Derde").customerRank)
        assertEquals(2, byTitle.getValue("Eerste").customerRank)
        assertEquals(3, byTitle.getValue("Tweede").customerRank)
        assertEquals(setOf(first.id, second.id, third.id), byTitle.values.mapTo(mutableSetOf()) { it.id })
    }

    @Test
    fun `circulaire update rolt volledig terug`() {
        val first = create("Fundering")
        val second = create("Mobiel")
        inTransaction {
            service.updateEpic(first.id, update(first.title, first.customerRank, setOf(second.id)))
        }

        assertThrows<IllegalArgumentException> {
            inTransaction {
                service.updateEpic(second.id, update("Niet bewaren", second.customerRank, setOf(first.id)))
            }
        }

        val unchanged = service.roadmap().epics.first { it.id == second.id }
        assertEquals("Mobiel", unchanged.title)
        assertEquals(emptySet<Long>(), unchanged.dependencyIds)
    }

    private fun create(title: String) = inTransaction {
        service.createEpic(CreateRoadmapEpicCommand(title, "$title omschrijving"))
    }

    private fun update(title: String, customerRank: Int, dependencies: Set<Long> = emptySet()) =
        UpdateRoadmapEpicCommand(title, "$title omschrijving", "planned", customerRank, dependencies)

    private fun <T> inTransaction(block: () -> T): T = requireNotNull(transaction.execute { block() })
}
