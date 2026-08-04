package nl.vdzon.softwarefactory.runtime.services

import nl.vdzon.softwarefactory.config.ConfigApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Instellingen voor [AgentRunRetentionPoller]. Bewust een eigen setje naast
 * [AgentEventRetentionSettings]: `agent_runs` bewaart de kostenhistorie van het agent-log-scherm en
 * mag dus langer blijven staan (90 dagen) dan de logregels erbij (30 dagen), en moet los aan/uit te
 * zetten zijn.
 */
data class AgentRunRetentionSettings(
    val enabled: Boolean,
    val retentionDays: Long,
    val batchSize: Int,
    /** Bovengrens per ronde, zodat een eerste opruiming van een enorme tabel de poller niet minutenlang bezet houdt. */
    val maxBatchesPerRun: Int,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String>): AgentRunRetentionSettings =
            AgentRunRetentionSettings(
                enabled = environment.boolean("SF_AGENT_RUN_RETENTION_ENABLED", default = true),
                retentionDays = environment.long("SF_AGENT_RUN_RETENTION_DAYS", default = 90L).coerceIn(1L, 3650L),
                batchSize = environment.long("SF_AGENT_RUN_RETENTION_BATCH_SIZE", default = 1_000L)
                    .coerceIn(100L, 100_000L).toInt(),
                maxBatchesPerRun = environment.long("SF_AGENT_RUN_RETENTION_MAX_BATCHES", default = 20L)
                    .coerceIn(1L, 1_000L).toInt(),
            )

        private fun Map<String, String>.boolean(key: String, default: Boolean): Boolean =
            this[key]?.takeIf { it.isNotBlank() }?.toBooleanStrictOrNull() ?: default

        private fun Map<String, String>.long(key: String, default: Long): Long =
            this[key]?.takeIf { it.isNotBlank() }?.toLongOrNull() ?: default
    }
}

@Configuration
class AgentRunRetentionConfiguration {
    @Bean
    fun agentRunRetentionSettings(factoryEnvironmentProvider: ConfigApi): AgentRunRetentionSettings =
        AgentRunRetentionSettings.fromEnvironment(factoryEnvironmentProvider.resolvedValues())
}
