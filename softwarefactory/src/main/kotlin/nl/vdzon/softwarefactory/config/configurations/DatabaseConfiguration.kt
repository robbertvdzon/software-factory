package nl.vdzon.softwarefactory.config.configurations

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.PostgresConnectionSettings
import org.flywaydb.core.Flyway
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DriverManagerDataSource
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
