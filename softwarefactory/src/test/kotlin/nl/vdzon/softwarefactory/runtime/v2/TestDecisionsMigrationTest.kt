package nl.vdzon.softwarefactory.runtime.v2

import com.zaxxer.hikari.HikariDataSource
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AgentRunCompletionRecord
import nl.vdzon.softwarefactory.orchestrator.repositories.JdbcAgentRunRepository
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.time.OffsetDateTime
import kotlin.test.assertEquals

class TestDecisionsMigrationTest {
    @Test
    fun `upgrade preserves historical verdict and revision and counts only substantive rejections`() {
        PostgreSQLContainer("postgres:16-alpine").apply { start() }.use { postgres ->
            HikariDataSource().use { ds ->
                ds.jdbcUrl = postgres.jdbcUrl
                ds.username = postgres.username
                ds.password = postgres.password
                val schema = "software_factory"
                val config = Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                    .createSchemas(true).placeholders(mapOf("schema" to schema)).locations("classpath:db/migration")
                config.target(MigrationVersion.fromVersion("41")).load().migrate()
                val jdbc = JdbcTemplate(ds)
                jdbc.update("INSERT INTO $schema.story_runs (id, story_key, target_repo) VALUES (1, 'SF-1', 'repo')")
                jdbc.update("INSERT INTO $schema.agent_runs (id, story_run_id, role, container_name, subtask_key, outcome, ended_at) " +
                    "VALUES (100, 1, 'tester', 'old-run', 'SF-2', 'success', now())")
                jdbc.update("INSERT INTO $schema.agent_run_completions " +
                    "(agent_run_id, story_run_id, story_key, container_name, payload_json, payload_hash, status) " +
                    "VALUES (100, 1, 'SF-2', 'old-run', '{\"phase\":\"test-rejected\"}', 'hash', 'COMPLETED')")
                val sha = "a".repeat(40)
                jdbc.update("INSERT INTO $schema.agent_runtime_jobs " +
                    "(agent_run_id, runtime_job_id, idempotency_key, runtime_status, runtime_phase, checkout_commit_sha) " +
                    "VALUES (100, '11111111-1111-1111-1111-111111111111', 'old', 'SUCCEEDED', 'FINISHED', ?)", sha)
                config.target(MigrationVersion.fromVersion("42")).load().migrate()
                val secrets = FactorySecrets(emptyList(), "", postgres.jdbcUrl, schema, null, loadedFrom = "test")
                val runs = JdbcAgentRunRepository(jdbc, secrets)
                assertEquals("test-rejected", runs.latestForRole(1, AgentRole.TESTER)!!.resultPhase)
                assertEquals(sha, runs.latestForRole(1, AgentRole.TESTER)!!.checkoutCommitSha)
                for ((key, phase) in listOf("SF-2" to "test-environment-repair", "SF-2" to "tested-with-questions", "SF-3" to "test-rejected")) {
                    val container = "$key-$phase"
                    runs.recordStarted(1, AgentRole.TESTER, container, null, null, null, null, key)
                    runs.complete(container, AgentRunCompletionRecord("success", 0, 0, 0, 0, 1, 1, 0.0,
                        "Evidence", resultPhase = phase, checkoutCommitSha = sha), OffsetDateTime.now())
                }
                assertEquals(2, runs.countTestRejections(1, "SF-2"))
                assertEquals(1, runs.countTestRejections(1, "SF-3"))
                // De teller en auditrevision blijven bestaan als de grote completion-payload is opgeruimd.
                jdbc.update("UPDATE $schema.agent_run_completions SET payload_json = NULL")
                assertEquals(2, runs.countTestRejections(1, "SF-2"))
                assertEquals(sha, runs.latestForRole(1, AgentRole.TESTER)!!.checkoutCommitSha)
            }
        }
    }
}
