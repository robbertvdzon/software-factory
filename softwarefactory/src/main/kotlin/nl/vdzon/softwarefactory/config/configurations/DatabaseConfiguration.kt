package nl.vdzon.softwarefactory.config.configurations

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.configurations.PostgresConnectionSettings
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

@Configuration
class DatabaseConfiguration {
    @Bean
    fun dataSource(factorySecrets: FactorySecrets): DataSource =
        HikariDataSource().apply {
            val settings = PostgresConnectionSettings.from(factorySecrets.factoryDatabaseUrl)
            driverClassName = "org.postgresql.Driver"
            jdbcUrl = settings.jdbcUrl
            settings.username?.let { username = it }
            settings.password?.let { password = it }
            poolName = "software-factory-db"
            maximumPoolSize = MAX_POOL_SIZE
            minimumIdle = 1
            connectionTimeout = CONNECTION_TIMEOUT_MS
            idleTimeout = IDLE_TIMEOUT_MS
            maxLifetime = MAX_LIFETIME_MS
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

    private companion object {
        /** HikariCP connection-pool afstemming voor de factory-database. */
        const val MAX_POOL_SIZE = 5
        const val CONNECTION_TIMEOUT_MS = 10_000L
        const val IDLE_TIMEOUT_MS = 600_000L
        const val MAX_LIFETIME_MS = 1_800_000L
    }
}
