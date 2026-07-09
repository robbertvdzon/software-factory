package nl.vdzon.softwarefactory.runtime.workspaces

import nl.vdzon.softwarefactory.config.ConfigApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

data class WorkCleanupSettings(
    val enabled: Boolean,
    val retentionDays: Long,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String>): WorkCleanupSettings =
            WorkCleanupSettings(
                enabled = environment.boolean("SF_WORK_CLEANUP_ENABLED", default = true),
                retentionDays = environment.long("SF_WORK_CLEANUP_RETENTION_DAYS", default = 7L),
            )

        private fun Map<String, String>.boolean(key: String, default: Boolean): Boolean =
            this[key]?.takeIf { it.isNotBlank() }?.toBooleanStrictOrNull() ?: default

        private fun Map<String, String>.long(key: String, default: Long): Long =
            this[key]?.takeIf { it.isNotBlank() }?.toLongOrNull() ?: default
    }
}

@Configuration
class WorkCleanupConfiguration {
    @Bean
    fun workCleanupSettings(factoryEnvironmentProvider: ConfigApi): WorkCleanupSettings =
        WorkCleanupSettings.fromEnvironment(factoryEnvironmentProvider.resolvedValues())
}
