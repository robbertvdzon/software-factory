package nl.vdzon.softwarefactory.dashboard.config

import nl.vdzon.softwarefactory.dashboard.api.GoogleIdTokenVerifier
import nl.vdzon.softwarefactory.dashboard.api.NimbusGoogleIdTokenVerifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path

data class DashboardSecrets(
    // Ondertekent (HMAC) het sessie-token dat de backend na een geslaagde Google-login afgeeft.
    val rememberSecret: String,
    // OAuth-client-ID (audience) waartegen Google-ID-tokens worden gevalideerd.
    val googleClientId: String,
    // Toegestane, geverifieerde e-mailadressen (genormaliseerd naar lowercase). Alleen deze
    // adressen krijgen een sessie-token, ongeacht een verder geldig Google-token.
    val allowedEmails: Set<String>,
    // Gedeeld token dat de factory meestuurt in de bridge-hello (zie
    // docs/ontwerp-bridge-dashboard.md §5). Leeg => elke hello wordt geweigerd (geen onbedoeld
    // open bridge-endpoint).
    val bridgeToken: String = "",
)

@Configuration
class DashboardConfig {
    @Bean
    fun dashboardSecrets(): DashboardSecrets = DashboardSecretsLoader().load()

    @Bean
    fun googleIdTokenVerifier(secrets: DashboardSecrets): GoogleIdTokenVerifier =
        NimbusGoogleIdTokenVerifier(secrets.googleClientId)
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

        // Losgekoppeld van de oude username/password: het session-signing-geheim is nu een eigen,
        // verplichte secret (een vergeten waarde mag geen forgebare tokens opleveren).
        val rememberSecret = required("SF_DASHBOARD_REMEMBER_SECRET")
        val googleClientId = required("SF_GOOGLE_CLIENT_ID")
        val allowedEmails = parseAllowedEmails(optional("SF_ALLOWED_EMAILS") ?: DEFAULT_ALLOWED_EMAIL)
        return DashboardSecrets(
            rememberSecret = rememberSecret,
            googleClientId = googleClientId,
            allowedEmails = allowedEmails,
            bridgeToken = optional("SF_BRIDGE_TOKEN").orEmpty(),
        )
    }

    private fun parseAllowedEmails(raw: String): Set<String> =
        raw.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

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

    private companion object {
        const val DEFAULT_ALLOWED_EMAIL = "robbert@vdzon.com"
    }
}
