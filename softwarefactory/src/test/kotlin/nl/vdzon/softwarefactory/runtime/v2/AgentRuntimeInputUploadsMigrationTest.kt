package nl.vdzon.softwarefactory.runtime.v2

import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals

class AgentRuntimeInputUploadsMigrationTest {
    @Test
    fun `v38 upgrades an existing v37 database without changing existing runtime jobs`() {
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        try {
            HikariDataSource().use { dataSource ->
                dataSource.jdbcUrl = postgres.jdbcUrl
                dataSource.username = postgres.username
                dataSource.password = postgres.password
                val schema = "software_factory"
                val configuration = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .placeholders(mapOf("schema" to schema))
                    .locations("classpath:db/migration")
                configuration.target(MigrationVersion.fromVersion("37")).load().migrate()
                val jdbc = JdbcTemplate(dataSource)
                jdbc.update(
                    "WITH story AS (" +
                        "INSERT INTO $schema.story_runs (story_key, target_repo) " +
                        "VALUES ('SF-1', 'repo') RETURNING id" +
                        "), agent AS (" +
                        "INSERT INTO $schema.agent_runs (story_run_id, role, container_name) " +
                        "SELECT id, 'developer', 'runtime-job' FROM story RETURNING id" +
                        ") INSERT INTO $schema.agent_runtime_jobs " +
                        "(agent_run_id, runtime_job_id, idempotency_key, runtime_status, runtime_phase) " +
                        "SELECT id, ?::uuid, 'existing-job', 'SUCCEEDED', 'FINISHED' FROM agent",
                    "11111111-1111-1111-1111-111111111111",
                )

                configuration.target(MigrationVersion.fromVersion("38")).load().migrate()

                assertEquals(
                    1,
                    jdbc.queryForObject(
                        "SELECT COUNT(*) FROM $schema.agent_runtime_jobs WHERE idempotency_key = 'existing-job'",
                        Int::class.java,
                    ),
                )
                assertEquals(
                    1,
                    jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables " +
                            "WHERE table_schema = '$schema' AND table_name = 'agent_runtime_input_uploads'",
                        Int::class.java,
                    ),
                )
            }
        } finally {
            postgres.stop()
        }
    }
}
