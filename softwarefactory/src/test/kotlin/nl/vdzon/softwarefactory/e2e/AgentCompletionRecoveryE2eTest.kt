package nl.vdzon.softwarefactory.e2e

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zaxxer.hikari.HikariDataSource
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.runtime.models.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.models.AgentRunEventPayload
import nl.vdzon.softwarefactory.runtime.models.CompletionExecutionPolicy
import nl.vdzon.softwarefactory.runtime.errors.CompletionPayloadConflictException
import nl.vdzon.softwarefactory.runtime.errors.CompletionPayloadRejectedException
import nl.vdzon.softwarefactory.runtime.repositories.JdbcCompletionInboxRepository
import nl.vdzon.softwarefactory.runtime.repositories.JdbcAgentEventRepository
import nl.vdzon.softwarefactory.orchestrator.repositories.JdbcAgentRunRepository
import nl.vdzon.softwarefactory.runtime.services.DurableCompletionCoordinator
import nl.vdzon.softwarefactory.runtime.types.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Postgres proof for REL-01: replay, restart, leases, conflict and retention. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentCompletionRecoveryE2eTest {
    private val schema = "software_factory"
    private val mapper = jacksonObjectMapper()
    private val clock = MutableClock(Instant.parse("2026-07-13T12:00:00Z"))
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var repository: JdbcCompletionInboxRepository

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        dataSource = HikariDataSource().apply {
            driverClassName = "org.postgresql.Driver"
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 6
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
        repository = JdbcCompletionInboxRepository(jdbc, secrets(), mapper, clock)
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
        postgres.stop()
    }

    @BeforeEach
    fun reset() {
        jdbc.update("DELETE FROM $schema.agent_run_completion_requeues")
        jdbc.update("DELETE FROM $schema.agent_run_completion_steps")
        jdbc.update("DELETE FROM $schema.agent_run_completions")
        jdbc.update("DELETE FROM $schema.agent_runs")
        jdbc.update("DELETE FROM $schema.story_runs")
        clock.instantValue = Instant.parse("2026-07-13T12:00:00Z")
    }

    @Test
    fun `same payload is idempotent and a conflicting redelivery is rejected`() {
        val request = newRequest("idem")
        val first = requireNotNull(repository.accept(request))
        repository.storeValidatedPayload(first.completion.id, request.copy(outcome = "validated-ok"))
        val replay = requireNotNull(repository.accept(request))

        assertEquals(first.completion.id, replay.completion.id)
        assertEquals("validated-ok", replay.request.outcome)
        assertEquals(12, repository.steps(first.completion.id).size)
        assertThrows(CompletionPayloadConflictException::class.java) {
            repository.accept(request.copy(summaryText = "different"))
        }
    }

    @Test
    fun `database effects use agent scoped idempotency keys`() {
        val request = newRequest("effects")
        val accepted = requireNotNull(repository.accept(request))
        val runs = JdbcAgentRunRepository(jdbc, secrets())
        val events = JdbcAgentEventRepository(jdbc, secrets(), mapper)

        repeat(2) {
            runs.addUsageToStoryRunOnce(
                accepted.completion.agentRunId,
                accepted.completion.storyRunId,
                request.toCompletionRecord(),
            )
            events.appendOnce(accepted.completion.agentRunId, "event-0", "log", mapOf("payload" to "once"))
        }

        assertEquals(10, jdbc.queryForObject(
            "SELECT total_input_tokens FROM $schema.story_runs WHERE id = ?",
            Int::class.java,
            accepted.completion.storyRunId,
        ))
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM $schema.agent_events WHERE agent_run_id = ?",
            Int::class.java,
            accepted.completion.agentRunId,
        ))
    }

    @Test
    fun `every stable step recovers before effect after effect and after acknowledgement`() {
        CompletionStep.entries.forEach { failedStep ->
            FailurePoint.entries.forEach { point ->
                val accepted = requireNotNull(repository.accept(newRequest("${failedStep.name}-${point.name}")))
                val firstProcess = DurableCompletionCoordinator(repository, mapper)
                val durableEffects = mutableSetOf<String>()

                CompletionStep.entries.takeWhile { it != failedStep }.forEach { step ->
                    assertTrue(firstProcess.runStep(accepted.completion.id, step) { durableEffects += step.name })
                }

                when (point) {
                    FailurePoint.BEFORE_EFFECT -> {
                        assertFalse(firstProcess.runStep(
                            accepted.completion.id,
                            failedStep,
                            CompletionExecutionPolicy(maxAttempts = 1),
                        ) {
                            error("failure before effect")
                        })
                        firstProcess.manualRequeue(accepted.completion.id, failedStep, "e2e-operator", "restart test")
                    }
                    FailurePoint.AFTER_EFFECT -> {
                        assertFalse(firstProcess.runStep(
                            accepted.completion.id,
                            failedStep,
                            CompletionExecutionPolicy(maxAttempts = 1),
                        ) {
                            durableEffects += failedStep.name // idempotency key survives the simulated crash
                            error("failure after effect")
                        })
                        firstProcess.manualRequeue(accepted.completion.id, failedStep, "e2e-operator", "restart test")
                    }
                    FailurePoint.AFTER_ACK -> {
                        assertTrue(firstProcess.runStep(accepted.completion.id, failedStep) {
                            durableEffects += failedStep.name
                        })
                    }
                }

                // A new coordinator represents a new JVM. Completed effects are skipped; failed
                // effects are reclaimed and use their stable idempotency key.
                val restarted = DurableCompletionCoordinator(repository, mapper)
                assertTrue(restarted.runStep(accepted.completion.id, failedStep) {
                    durableEffects += failedStep.name
                })
                CompletionStep.entries.dropWhile { it != failedStep }.drop(1).forEach { step ->
                    assertTrue(restarted.runStep(accepted.completion.id, step) { durableEffects += step.name })
                }

                assertEquals(CompletionStep.entries.map { it.name }.toSet(), durableEffects)
                assertEquals(CompletionStatus.COMPLETED, repository.get(accepted.completion.id)?.status)
                assertTrue(repository.steps(accepted.completion.id).all { it.status == CompletionStatus.COMPLETED })
            }
        }
    }

    @Test
    fun `claim lease allows only one concurrent worker and expired work is reclaimed`() {
        val accepted = requireNotNull(repository.accept(newRequest("lease")))
        val effects = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val workers = listOf(
            DurableCompletionCoordinator(repository, mapper),
            DurableCompletionCoordinator(repository, mapper),
        )
        val futures = workers.map { worker ->
            pool.submit<Boolean> {
                worker.runStep(accepted.completion.id, CompletionStep.ACCEPT_RUN_RESULT) {
                    effects.incrementAndGet()
                    entered.countDown()
                    release.await(2, TimeUnit.SECONDS)
                }
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        release.countDown()
        futures.forEach { it.get(2, TimeUnit.SECONDS) }
        pool.shutdownNow()
        assertEquals(1, effects.get())

        val second = requireNotNull(repository.accept(newRequest("expired-lease")))
        assertNotNull(repository.claimStep(second.completion.id, CompletionStep.ACCEPT_RUN_RESULT, "dead-worker", Duration.ofSeconds(5)))
        clock.advance(Duration.ofSeconds(6))
        assertNotNull(repository.claimStep(second.completion.id, CompletionStep.ACCEPT_RUN_RESULT, "restart", Duration.ofMinutes(1)))
    }

    @Test
    fun `bounded retry manual audit payload limits and tombstone retention are enforced`() {
        val accepted = requireNotNull(repository.accept(newRequest("ops")))
        val coordinator = DurableCompletionCoordinator(repository, mapper)
        assertFalse(coordinator.runStep(
            accepted.completion.id,
            CompletionStep.ACCEPT_RUN_RESULT,
            CompletionExecutionPolicy(maxAttempts = 2, backoff = Duration.ofSeconds(2)),
        ) { error("temporary") })
        assertNull(repository.claimStep(
            accepted.completion.id,
            CompletionStep.ACCEPT_RUN_RESULT,
            "too-early",
            Duration.ofMinutes(1),
        ))
        clock.advance(Duration.ofSeconds(2))
        assertTrue(repository.dueCompletionIds().contains(accepted.completion.id))
        assertFalse(coordinator.runStep(
            accepted.completion.id,
            CompletionStep.ACCEPT_RUN_RESULT,
            CompletionExecutionPolicy(maxAttempts = 2),
        ) { error("permanent") })
        assertEquals(CompletionStatus.FAILED_PERMANENT, repository.steps(accepted.completion.id).first().status)
        coordinator.manualRequeue(accepted.completion.id, CompletionStep.ACCEPT_RUN_RESULT, "admin", "dependency repaired")
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM $schema.agent_run_completion_requeues", Int::class.java))

        // Event-payloadlimiet is MAX_EVENT_BYTES (CompletionInboxRepository.kt) = 262_144 bytes; 1 byte
        // erover raakt de validatie ongeacht een eventuele latere aanpassing van die constante.
        assertThrows(CompletionPayloadRejectedException::class.java) {
            repository.accept(newRequest("oversize").copy(events = listOf(AgentRunEventPayload("log", "x".repeat(262_145)))))
        }

        CompletionStep.entries.forEach { step ->
            assertTrue(coordinator.runStep(accepted.completion.id, step) { })
        }
        clock.advance(Duration.ofDays(31))
        assertEquals(1, coordinator.purgePayloads(Duration.ofDays(30)))
        val tombstone = requireNotNull(repository.get(accepted.completion.id))
        assertNull(tombstone.payloadJson)
        assertEquals(CompletionStatus.COMPLETED, tombstone.status)
        assertEquals(12, repository.steps(tombstone.id).size)
    }

    private fun newRequest(suffix: String): AgentRunCompleteRequest {
        val storyRunId = requireNotNull(jdbc.queryForObject(
            "INSERT INTO $schema.story_runs (story_key, target_repo) VALUES (?, 'repo') RETURNING id",
            Long::class.java, "SF-$suffix",
        ))
        val container = "agent-$suffix"
        jdbc.update(
            "INSERT INTO $schema.agent_runs (story_run_id, role, container_name) VALUES (?, 'developer', ?)",
            storyRunId, container,
        )
        return AgentRunCompleteRequest(
            storyKey = "SF-$suffix",
            role = "developer",
            containerName = container,
            outcome = "ok",
            summaryText = "done",
            inputTokens = 10,
            outputTokens = 5,
        )
    }

    private fun secrets() = FactorySecrets(
        trackerProjects = emptyList(),
        githubToken = "token",
        factoryDatabaseUrl = postgres.jdbcUrl,
        factoryDatabaseSchema = schema,
        kubeconfig = null,
        aiCredentialsDir = null,
        aiOauthToken = null,
        loadedFrom = "test",
    )

    private enum class FailurePoint { BEFORE_EFFECT, AFTER_EFFECT, AFTER_ACK }

    private class MutableClock(var instantValue: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = instantValue
        fun advance(duration: Duration) { instantValue = instantValue.plus(duration) }
    }
}
