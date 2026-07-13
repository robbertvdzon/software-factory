package nl.vdzon.softwarefactory.config.services

import nl.vdzon.softwarefactory.core.contracts.OrchestratorSettings
import java.time.Duration

/**
 * Bouwt [OrchestratorSettings] uit een omgevings-map (typisch [ConfigApi.resolvedValues]:
 * secrets.env + properties.env + System.getenv). Verhuisd uit de data class zelf: core hoort
 * geen omgevings-/infrastructuurkennis te hebben; het lezen van env-achtige config is een
 * verantwoordelijkheid van het config-package.
 */
object OrchestratorSettingsFactory {
    fun fromEnvironment(environment: Map<String, String>): OrchestratorSettings =
        OrchestratorSettings(
            pollInterval = Duration.ofMillis(environment.long("SF_POLL_INTERVAL_MS", default = 60000)),
            maxParallelRefiner = environment.int("SF_MAX_PARALLEL_REFINER", default = 1),
            maxParallelDeveloper = environment.int("SF_MAX_PARALLEL_DEVELOPER", default = 2),
            maxParallelReviewer = environment.int("SF_MAX_PARALLEL_REVIEWER", default = 2),
            maxParallelTester = environment.int("SF_MAX_PARALLEL_TESTER", default = 1),
            maxParallelTotal = environment.int("SF_MAX_PARALLEL_TOTAL", default = 4),
            maxDeveloperLoopbacks =
                environment.int(
                    "SF_MAX_DEVELOPER_LOOPBACKS",
                    default = OrchestratorSettings.DEFAULT_MAX_DEVELOPER_LOOPBACKS,
                ),
            maxTestChainResets =
                environment.int(
                    "SF_MAX_TEST_CHAIN_RESETS",
                    default = OrchestratorSettings.DEFAULT_MAX_TEST_CHAIN_RESETS,
                ),
            maxTransientRetries = environment.int("SF_MAX_TRANSIENT_RETRIES", default = 2),
            hardTimeout = Duration.ofMinutes(environment.long("SF_AGENT_HARD_TIMEOUT_MINUTES", default = 60)),
            activePhaseRecoveryDelay =
                Duration.ofMillis(environment.long("SF_ACTIVE_PHASE_RECOVERY_DELAY_MS", default = 60000)),
            costMonitorInterval =
                Duration.ofMillis(environment.long("SF_COST_MONITOR_INTERVAL_MS", default = 300000)),
            creditsPauseDefault =
                Duration.ofMinutes(environment.long("SF_CREDITS_PAUSE_DEFAULT_MINUTES", default = 30)),
        )

    private fun Map<String, String>.int(key: String, default: Int): Int =
        this[key]?.takeIf { it.isNotBlank() }?.toIntOrNull()?.takeIf { it >= 0 } ?: default

    private fun Map<String, String>.long(key: String, default: Long): Long =
        this[key]?.takeIf { it.isNotBlank() }?.toLongOrNull()?.takeIf { it > 0 } ?: default
}
