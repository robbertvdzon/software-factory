package nl.vdzon.softwarefactory.config

import com.zaxxer.hikari.HikariDataSource
import nl.vdzon.softwarefactory.config.services.*

import nl.vdzon.softwarefactory.config.configurations.*
import nl.vdzon.softwarefactory.config.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DatabaseConfigurationTest {
    @Test
    fun `database configuration uses hikari connection pool`() {
        val dataSource = DatabaseConfiguration().dataSource(
            FactorySecrets(
                youTrackBaseUrl = "https://youtrack.example",
                youTrackToken = "token",
                youTrackProjects = emptyList(),
                githubToken = "github-token",
                factoryDatabaseUrl = "postgresql://software_factory:software_factory@localhost:5432/software_factory",
                factoryDatabaseSchema = "software_factory",
                kubeconfig = null,
                aiCredentialsDir = null,
                aiOauthToken = null,
                loadedFrom = "test",
            ),
        ) as HikariDataSource

        assertEquals("software-factory-db", dataSource.poolName)
        assertEquals(5, dataSource.maximumPoolSize)
        assertEquals(1, dataSource.minimumIdle)
        assertEquals(10_000, dataSource.connectionTimeout)
        assertEquals(600_000, dataSource.idleTimeout)
        assertEquals(1_800_000, dataSource.maxLifetime)
        dataSource.close()
    }

    @Test
    fun `parses postgresql url with authority credentials for jdbc`() {
        val settings = PostgresConnectionSettings.from(
            "postgresql://software_factory:software_factory@localhost:5432/software_factory",
        )

        assertEquals("jdbc:postgresql://localhost:5432/software_factory", settings.jdbcUrl)
        assertEquals("software_factory", settings.username)
        assertEquals("software_factory", settings.password)
    }

    @Test
    fun `parses neon style query credentials and keeps remaining jdbc properties`() {
        val settings = PostgresConnectionSettings.from(
            "postgresql://host.example/db?user=owner&password=secret&sslmode=require",
        )

        assertEquals("jdbc:postgresql://host.example/db?sslmode=require", settings.jdbcUrl)
        assertEquals("owner", settings.username)
        assertEquals("secret", settings.password)
    }

    @Test
    fun `keeps explicit jdbc url unchanged`() {
        val settings = PostgresConnectionSettings.from("jdbc:postgresql://localhost:5432/software_factory")

        assertEquals("jdbc:postgresql://localhost:5432/software_factory", settings.jdbcUrl)
        assertNull(settings.username)
        assertNull(settings.password)
    }
}
