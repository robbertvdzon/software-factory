package nl.vdzon.softwarefactory.config.configurations

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.PostgresConnectionSettings
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
            maximumPoolSize = 5
            minimumIdle = 1
            connectionTimeout = 10_000
            idleTimeout = 600_000
            maxLifetime = 1_800_000
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
