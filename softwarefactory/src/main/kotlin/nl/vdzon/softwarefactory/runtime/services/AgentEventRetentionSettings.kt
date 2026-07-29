package nl.vdzon.softwarefactory.runtime.services

import nl.vdzon.softwarefactory.config.ConfigApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Instellingen voor [AgentEventRetentionPoller]. Defaults in dezelfde geest als de bestaande
 * opruimingen: 30 dagen bewaren (net als `SF_COMPLETION_RETENTION_DAYS`), uit te zetten met een
 * env-vlag (net als `SF_WORK_CLEANUP_ENABLED`).
 */
data class AgentEventRetentionSettings(
    val enabled: Boolean,
    val retentionDays: Long,
    val batchSize: Int,
    /** Bovengrens per ronde, zodat een eerste opruiming van een enorme tabel de poller niet minutenlang bezet houdt. */
    val maxBatchesPerRun: Int,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String>): AgentEventRetentionSettings =
            AgentEventRetentionSettings(
                enabled = environment.boolean("SF_AGENT_EVENT_RETENTION_ENABLED", default = true),
                retentionDays = environment.long("SF_AGENT_EVENT_RETENTION_DAYS", default = 30L).coerceIn(1L, 3650L),
                batchSize = environment.long("SF_AGENT_EVENT_RETENTION_BATCH_SIZE", default = 5_000L)
                    .coerceIn(100L, 100_000L).toInt(),
                maxBatchesPerRun = environment.long("SF_AGENT_EVENT_RETENTION_MAX_BATCHES", default = 20L)
                    .coerceIn(1L, 1_000L).toInt(),
            )

        private fun Map<String, String>.boolean(key: String, default: Boolean): Boolean =
            this[key]?.takeIf { it.isNotBlank() }?.toBooleanStrictOrNull() ?: default

        private fun Map<String, String>.long(key: String, default: Long): Long =
            this[key]?.takeIf { it.isNotBlank() }?.toLongOrNull() ?: default
    }
}

@Configuration
class AgentEventRetentionConfiguration {
    @Bean
    fun agentEventRetentionSettings(factoryEnvironmentProvider: ConfigApi): AgentEventRetentionSettings =
        AgentEventRetentionSettings.fromEnvironment(factoryEnvironmentProvider.resolvedValues())
}
