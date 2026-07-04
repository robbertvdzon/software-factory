package nl.vdzon.softwarefactory.dashboard.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path

data class DashboardSecrets(
    val dashboardUsername: String,
    val dashboardPassword: String,
    val rememberSecret: String,
    // Gedeeld token dat de factory meestuurt in de bridge-hello (zie
    // docs/ontwerp-bridge-dashboard.md §5). Leeg => elke hello wordt geweigerd (geen onbedoeld
    // open bridge-endpoint).
    val bridgeToken: String = "",
)

@Configuration
class DashboardConfig {
    @Bean
    fun dashboardSecrets(): DashboardSecrets = DashboardSecretsLoader().load()
}

class DashboardSecretsLoader(
    private val environment: Map<String, String> = System.getenv(),
    private val secretFiles: List<Path>? = null,
) {
    fun load(): DashboardSecrets {
        val fileValues = candidateSecretFiles()
            .firstOrNull { Files.exists(it) }
            ?.let { parse(it) }
            ?: emptyMap()
        fun required(key: String): String =
            resolve(key, fileValues) ?: error("Missing required dashboard configuration: $key")
        fun optional(key: String): String? = resolve(key, fileValues)

        val username = optional("SF_DASHBOARD_USERNAME") ?: "admin"
        // Geen default-wachtwoord: een vergeten secret mag nooit een raadbaar admin/admin-login
        // (en daarmee forgebare remember-me-tokens) opleveren.
        val password = required("SF_DASHBOARD_PASSWORD")
        return DashboardSecrets(
            dashboardUsername = username,
            dashboardPassword = password,
            rememberSecret = optional("SF_DASHBOARD_REMEMBER_SECRET") ?: "$username:$password",
            bridgeToken = optional("SF_BRIDGE_TOKEN").orEmpty(),
        )
    }

    private fun resolve(key: String, fileValues: Map<String, String>): String? =
        fileValues[key]?.takeIf { it.isNotBlank() } ?: environment[key]?.takeIf { it.isNotBlank() }

    private fun candidateSecretFiles(): List<Path> {
        secretFiles?.let { return it }
        val override = environment["SF_SECRETS_FILE"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        return listOfNotNull(override) + listOf(Path.of("secrets.env"), Path.of("../secrets.env"))
    }

    private fun parse(path: Path): Map<String, String> =
        Files.readAllLines(path).mapNotNull { raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val normalized = line.removePrefix("export ").trim()
            val separator = normalized.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            normalized.substring(0, separator).trim() to normalized.substring(separator + 1).trim().stripQuotes()
        }.toMap()

    private fun String.stripQuotes(): String =
        if ((startsWith("\"") && endsWith("\"")) || (startsWith("'") && endsWith("'"))) substring(1, length - 1) else this
}
