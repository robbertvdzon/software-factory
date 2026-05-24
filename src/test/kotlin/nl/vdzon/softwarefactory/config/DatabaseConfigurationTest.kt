package nl.vdzon.softwarefactory.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DatabaseConfigurationTest {
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
