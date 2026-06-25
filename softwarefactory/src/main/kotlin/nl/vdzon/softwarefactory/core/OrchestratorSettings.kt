package nl.vdzon.softwarefactory.core

import nl.vdzon.softwarefactory.core.AgentRole
import java.time.Duration

data class OrchestratorSettings(
    /** Poll-interval wanneer er actief werk loopt (agent draait of er gebeurde een transitie). */
    val pollInterval: Duration,
    /** Trager poll-interval wanneer alles idle is (niets draait, niets wacht op verwerking). */
    val pollIntervalIdle: Duration = Duration.ofSeconds(45),
    val maxParallelRefiner: Int,
    val maxParallelDeveloper: Int,
    val maxParallelReviewer: Int,
    val maxParallelTester: Int,
    val maxParallelTotal: Int,
    val maxDeveloperLoopbacks: Int,
    /**
     * SF-200 — cap op het aantal keren dat een test-bevinding de hele subtaak-keten opnieuw mag
     * resetten. Analoog aan [maxDeveloperLoopbacks], maar conceptueel een andere grens (test-chain
     * reset i.p.v. gerichte developer-loopback). Default veilig laag zodat een story niet eindeloos
     * de keten blijft herstarten.
     */
    val maxTestChainResets: Int = DEFAULT_MAX_TEST_CHAIN_RESETS,
    val maxTransientRetries: Int,
    val hardTimeout: Duration,
    val activePhaseRecoveryDelay: Duration = Duration.ofMinutes(1),
    val costMonitorInterval: Duration,
    val creditsPauseDefault: Duration,
) {
    fun maxParallelFor(role: AgentRole): Int =
        when (role) {
            AgentRole.REFINER -> maxParallelRefiner
            AgentRole.PLANNER -> maxParallelRefiner
            AgentRole.DEVELOPER -> maxParallelDeveloper
            AgentRole.REVIEWER -> maxParallelReviewer
            AgentRole.TESTER -> maxParallelTester
            AgentRole.SUMMARIZER -> maxParallelTester
            AgentRole.ASSISTANT,
            AgentRole.COST_MONITOR,
            AgentRole.ORCHESTRATOR,
            -> 1
        }

    companion object {
        fun fromEnvironment(environment: Map<String, String>): OrchestratorSettings =
            OrchestratorSettings(
                pollInterval = Duration.ofMillis(environment.long("SF_POLL_INTERVAL_MS", default = 1000)),
                pollIntervalIdle = Duration.ofMillis(environment.long("SF_POLL_INTERVAL_IDLE_MS", default = 1000)),
                maxParallelRefiner = environment.int("SF_MAX_PARALLEL_REFINER", default = 1),
                maxParallelDeveloper = environment.int("SF_MAX_PARALLEL_DEVELOPER", default = 2),
                maxParallelReviewer = environment.int("SF_MAX_PARALLEL_REVIEWER", default = 2),
                maxParallelTester = environment.int("SF_MAX_PARALLEL_TESTER", default = 1),
                maxParallelTotal = environment.int("SF_MAX_PARALLEL_TOTAL", default = 4),
                maxDeveloperLoopbacks = environment.int("SF_MAX_DEVELOPER_LOOPBACKS", default = DEFAULT_MAX_DEVELOPER_LOOPBACKS),
                maxTestChainResets = environment.int("SF_MAX_TEST_CHAIN_RESETS", default = DEFAULT_MAX_TEST_CHAIN_RESETS),
                maxTransientRetries = environment.int("SF_MAX_TRANSIENT_RETRIES", default = 2),
                hardTimeout = Duration.ofMinutes(environment.long("SF_AGENT_HARD_TIMEOUT_MINUTES", default = 60)),
                activePhaseRecoveryDelay = Duration.ofMillis(environment.long("SF_ACTIVE_PHASE_RECOVERY_DELAY_MS", default = 60000)),
                costMonitorInterval = Duration.ofMillis(environment.long("SF_COST_MONITOR_INTERVAL_MS", default = 300000)),
                creditsPauseDefault = Duration.ofMinutes(environment.long("SF_CREDITS_PAUSE_DEFAULT_MINUTES", default = 30)),
            )

        private fun Map<String, String>.int(key: String, default: Int): Int =
            this[key]?.takeIf { it.isNotBlank() }?.toIntOrNull()?.takeIf { it >= 0 } ?: default

        private fun Map<String, String>.long(key: String, default: Long): Long =
            this[key]?.takeIf { it.isNotBlank() }?.toLongOrNull()?.takeIf { it > 0 } ?: default

        const val DEFAULT_MAX_DEVELOPER_LOOPBACKS = 5
        const val DEFAULT_MAX_TEST_CHAIN_RESETS = 3
    }
}
