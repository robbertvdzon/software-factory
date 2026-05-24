package nl.vdzon.softwarefactory.config

import org.flywaydb.core.Flyway
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.sql.DataSource

@Configuration
class DatabaseConfiguration {
    @Bean
    fun dataSource(factorySecrets: FactorySecrets): DataSource =
        DriverManagerDataSource().apply {
            val settings = PostgresConnectionSettings.from(factorySecrets.factoryDatabaseUrl)
            setDriverClassName("org.postgresql.Driver")
            url = settings.jdbcUrl
            settings.username?.let { username = it }
            settings.password?.let { password = it }
        }

    @Bean
    fun flyway(dataSource: DataSource, factorySecrets: FactorySecrets): Flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(factorySecrets.factoryDatabaseSchema)
            .defaultSchema(factorySecrets.factoryDatabaseSchema)
            .createSchemas(true)
            .placeholders(mapOf("schema" to factorySecrets.factoryDatabaseSchema))
            .locations("classpath:db/migration")
            .load()

    @Bean
    fun flywayMigrationInitializer(flyway: Flyway): FlywayMigrationInitializer =
        FlywayMigrationInitializer(flyway)

}

data class PostgresConnectionSettings(
    val jdbcUrl: String,
    val username: String?,
    val password: String?,
) {
    companion object {
        fun from(rawUrl: String): PostgresConnectionSettings {
            if (rawUrl.startsWith("jdbc:")) {
                return PostgresConnectionSettings(rawUrl, username = null, password = null)
            }

            val uri = URI.create(rawUrl)
            require(uri.scheme == "postgresql") {
                "SF_DATABASE_URL must use postgresql:// or jdbc:postgresql://."
            }
            val host = requireNotNull(uri.host) {
                "SF_DATABASE_URL must include a host."
            }
            val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
            val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
            val credentials = uri.rawUserInfo?.parseUserInfo()
            val query = uri.rawQuery?.parseQuery()
            val username = credentials?.first ?: query?.username
            val password = credentials?.second ?: query?.password
            val remainingQuery = query?.remainingRaw?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()

            return PostgresConnectionSettings(
                jdbcUrl = "jdbc:postgresql://$host$port$path$remainingQuery",
                username = username,
                password = password,
            )
        }

        private fun String.parseUserInfo(): Pair<String, String?> {
            val parts = split(":", limit = 2)
            return parts[0].urlDecoded() to parts.getOrNull(1)?.urlDecoded()
        }

        private fun String.parseQuery(): QueryCredentials {
            var username: String? = null
            var password: String? = null
            val remaining = split("&")
                .filter { it.isNotBlank() }
                .filterNot { part ->
                    val key = part.substringBefore("=").urlDecoded()
                    when (key) {
                        "user" -> {
                            username = part.substringAfter("=", "").urlDecoded()
                            true
                        }
                        "password" -> {
                            password = part.substringAfter("=", "").urlDecoded()
                            true
                        }
                        else -> false
                    }
                }
            return QueryCredentials(username, password, remaining.joinToString("&"))
        }

        private fun String.urlDecoded(): String =
            URLDecoder.decode(this, StandardCharsets.UTF_8)
    }
}

private data class QueryCredentials(
    val username: String?,
    val password: String?,
    val remainingRaw: String,
)
