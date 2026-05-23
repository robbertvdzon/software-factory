package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.jira.AgentRole
import nl.vdzon.softwarefactory.config.SecretsEnvLoader
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

data class OrchestratorSettings(
    val pollingEnabled: Boolean,
    val pollInterval: Duration,
    val maxParallelRefiner: Int,
    val maxParallelDeveloper: Int,
    val maxParallelReviewer: Int,
    val maxParallelTester: Int,
    val maxParallelTotal: Int,
    val maxDeveloperLoopbacks: Int,
    val maxTransientRetries: Int,
    val hardTimeout: Duration,
) {
    fun maxParallelFor(role: AgentRole): Int =
        when (role) {
            AgentRole.REFINER -> maxParallelRefiner
            AgentRole.DEVELOPER -> maxParallelDeveloper
            AgentRole.REVIEWER -> maxParallelReviewer
            AgentRole.TESTER -> maxParallelTester
            AgentRole.COST_MONITOR,
            AgentRole.ORCHESTRATOR,
            -> 1
        }

    companion object {
        fun fromEnvironment(environment: Map<String, String>): OrchestratorSettings =
            OrchestratorSettings(
                pollingEnabled = environment.boolean("SF_ORCHESTRATOR_POLLING_ENABLED", default = false),
                pollInterval = Duration.ofMillis(environment.long("SF_POLL_INTERVAL_MS", default = 15000)),
                maxParallelRefiner = environment.int("SF_MAX_PARALLEL_REFINER", default = 1),
                maxParallelDeveloper = environment.int("SF_MAX_PARALLEL_DEVELOPER", default = 2),
                maxParallelReviewer = environment.int("SF_MAX_PARALLEL_REVIEWER", default = 2),
                maxParallelTester = environment.int("SF_MAX_PARALLEL_TESTER", default = 1),
                maxParallelTotal = environment.int("SF_MAX_PARALLEL_TOTAL", default = 4),
                maxDeveloperLoopbacks = environment.int("SF_MAX_DEVELOPER_LOOPBACKS", default = 5),
                maxTransientRetries = environment.int("SF_MAX_TRANSIENT_RETRIES", default = 2),
                hardTimeout = Duration.ofMinutes(environment.long("SF_AGENT_HARD_TIMEOUT_MINUTES", default = 60)),
            )

        private fun Map<String, String>.boolean(key: String, default: Boolean): Boolean =
            this[key]?.takeIf { it.isNotBlank() }?.toBooleanStrictOrNull() ?: default

        private fun Map<String, String>.int(key: String, default: Int): Int =
            this[key]?.takeIf { it.isNotBlank() }?.toIntOrNull()?.takeIf { it >= 0 } ?: default

        private fun Map<String, String>.long(key: String, default: Long): Long =
            this[key]?.takeIf { it.isNotBlank() }?.toLongOrNull()?.takeIf { it > 0 } ?: default
    }
}

@Configuration
class OrchestratorConfiguration {
    @Bean
    fun orchestratorSettings(): OrchestratorSettings =
        OrchestratorSettings.fromEnvironment(SecretsEnvLoader().resolvedValues())

    @Bean
    fun factoryClock(): Clock =
        Clock.systemUTC()
}
