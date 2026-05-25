package nl.vdzon.softwarefactory.dashboard.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

data class DashboardSecrets(
    val youTrackBaseUrl: String,
    val youTrackToken: String,
    val youTrackProjects: List<String>,
    val githubToken: String,
    val databaseUrl: String,
    val databaseSchema: String,
    val dashboardUsername: String,
    val dashboardPassword: String,
    val rememberSecret: String,
) {
    val redactedSummary: Map<String, String> = mapOf(
        "youTrackBaseUrl" to youTrackBaseUrl,
        "youTrackToken" to "<redacted>",
        "youTrackProjects" to youTrackProjects.joinToString(",").ifBlank { "<auto-discover>" },
        "githubToken" to "<redacted>",
        "databaseUrl" to databaseUrl.replace(Regex("(jdbc:)?postgresql://[^\\s,}]+"), "postgresql://<redacted>"),
        "databaseSchema" to databaseSchema,
        "dashboardUsername" to dashboardUsername,
        "dashboardPassword" to "<redacted>",
        "rememberSecret" to "<redacted>",
    )
}

@Configuration
class DashboardConfig {
    @Bean
    fun dashboardSecrets(): DashboardSecrets = DashboardSecretsLoader().load()

    @Bean
    fun dataSource(secrets: DashboardSecrets): DataSource {
        val settings = PostgresSettings.from(secrets.databaseUrl)
        return DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = settings.jdbcUrl
            username = settings.username
            password = settings.password
        }
    }

    @Bean
    fun jdbcTemplate(dataSource: DataSource): JdbcTemplate = JdbcTemplate(dataSource)
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
        val password = optional("SF_DASHBOARD_PASSWORD") ?: "admin"
        return DashboardSecrets(
            youTrackBaseUrl = required("SF_YOUTRACK_BASE_URL").trimEnd('/'),
            youTrackToken = required("SF_YOUTRACK_TOKEN"),
            youTrackProjects = optional("SF_YOUTRACK_PROJECTS").orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() },
            githubToken = required("SF_GITHUB_TOKEN"),
            databaseUrl = required("SF_DATABASE_URL"),
            databaseSchema = required("SF_DATABASE_SCHEMA").also {
                require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(it)) { "SF_DATABASE_SCHEMA must be a valid Postgres identifier." }
            },
            dashboardUsername = username,
            dashboardPassword = password,
            rememberSecret = optional("SF_DASHBOARD_REMEMBER_SECRET") ?: "$username:$password",
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

private data class PostgresSettings(
    val jdbcUrl: String,
    val username: String?,
    val password: String?,
) {
    companion object {
        fun from(raw: String): PostgresSettings {
            val jdbc = if (raw.startsWith("jdbc:postgresql://")) raw else raw.replaceFirst("postgresql://", "jdbc:postgresql://")
            val uri = java.net.URI(jdbc.removePrefix("jdbc:"))
            val userInfo = uri.userInfo?.split(":", limit = 2)
            val clean = java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
            return PostgresSettings("jdbc:$clean", userInfo?.getOrNull(0), userInfo?.getOrNull(1))
        }
    }
}
