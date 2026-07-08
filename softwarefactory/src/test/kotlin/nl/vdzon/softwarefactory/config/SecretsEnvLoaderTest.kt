package nl.vdzon.softwarefactory.config

import nl.vdzon.softwarefactory.config.services.*

import nl.vdzon.softwarefactory.config.configurations.*
import nl.vdzon.softwarefactory.config.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class SecretsEnvLoaderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `loads required secrets from file`() {
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText(
            """
            SF_TRACKER_PROJECTS=SP,PNF
            SF_GITHUB_TOKEN=github-secret
            SF_DATABASE_URL=postgresql://user:pass@example/db
            SF_DATABASE_SCHEMA=software_factory
            SF_KUBECONFIG=/tmp/kubeconfig
            SF_AI_CREDENTIALS_DIR=/tmp/ai
            SF_CODEX_CREDENTIALS_DIR=/tmp/codex
            """.trimIndent(),
        )

        val secrets = SecretsEnvLoader(secretsFile = secretsFile, environment = emptyMap()).load()

        assertEquals(listOf("SP", "PNF"), secrets.trackerProjects)
        assertEquals("github-secret", secrets.githubToken)
        assertEquals("postgresql://user:pass@example/db", secrets.factoryDatabaseUrl)
        assertEquals("software_factory", secrets.factoryDatabaseSchema)
        assertEquals("/tmp/kubeconfig", secrets.kubeconfig)
        assertEquals("/tmp/ai", secrets.aiCredentialsDir)
        assertEquals("/tmp/codex", secrets.codexCredentialsDir)
    }

    @Test
    fun `falls back to environment when file is missing`() {
        val secretsFile = tempDir.resolve("missing.env")

        val secrets = SecretsEnvLoader(
            secretsFile = secretsFile,
            environment = requiredEnvironment(),
        ).load()

        assertEquals("env-github-token", secrets.githubToken)
        assertEquals("postgresql://env:pass@example/db", secrets.factoryDatabaseUrl)
        assertEquals("software_factory", secrets.factoryDatabaseSchema)
        assertEquals("system environment", secrets.loadedFrom)
    }

    @Test
    fun `allows branch specific database schemas`() {
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText(
            """
            SF_GITHUB_TOKEN=github-token
            SF_DATABASE_URL=postgresql://software_factory:software_factory@localhost:5432/software_factory
            SF_DATABASE_SCHEMA=software_factory_sf_020
            """.trimIndent(),
        )

        val secrets = SecretsEnvLoader(secretsFile = secretsFile, environment = emptyMap()).load()

        assertEquals("software_factory_sf_020", secrets.factoryDatabaseSchema)
    }

    @Test
    fun `environment wins over file and file provides keys missing from environment`() {
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText(
            """
            SF_KUBECONFIG=/tmp/file-kubeconfig
            """.trimIndent(),
        )

        val secrets = SecretsEnvLoader(
            secretsFile = secretsFile,
            environment = requiredEnvironment(),
        ).load()

        assertEquals("env-github-token", secrets.githubToken)
        assertEquals("postgresql://env:pass@example/db", secrets.factoryDatabaseUrl)
        // The file still supplies keys that the environment does not set.
        assertEquals("/tmp/file-kubeconfig", secrets.kubeconfig)
    }

    @Test
    fun `fails when required secrets are not available`() {
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText("")

        val exception = assertThrows(MissingRequiredSecretsException::class.java) {
            SecretsEnvLoader(secretsFile = secretsFile, environment = emptyMap()).load()
        }

        assertEquals(
            "Missing required factory configuration: SF_GITHUB_TOKEN, SF_DATABASE_URL, SF_DATABASE_SCHEMA. " +
                "Set them in $secretsFile or as system environment variables.",
            exception.message,
        )
    }

    @Test
    fun `fails when database schema is factory`() {
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText(
            """
            SF_GITHUB_TOKEN=github-token
            SF_DATABASE_URL=postgresql://user:pass@example/db
            SF_DATABASE_SCHEMA=factory
            """.trimIndent(),
        )

        val exception = assertThrows(IllegalArgumentException::class.java) {
            SecretsEnvLoader(secretsFile = secretsFile, environment = emptyMap()).load()
        }

        assertEquals(
            "SF_DATABASE_SCHEMA must not be 'factory'; that schema belongs to another system.",
            exception.message,
        )
    }

    @Test
    fun `parses comments export prefix and quoted values`() {
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText(
            """
            # Local software factory config
            export SF_GITHUB_TOKEN="github-token"
            SF_DATABASE_URL='postgresql://user:pass@example/db'
            SF_DATABASE_SCHEMA=software_factory
            """.trimIndent(),
        )

        val secrets = SecretsEnvLoader(secretsFile = secretsFile, environment = emptyMap()).load()

        assertEquals("github-token", secrets.githubToken)
        assertEquals("postgresql://user:pass@example/db", secrets.factoryDatabaseUrl)
    }

    @Test
    fun `default secrets file is current directory secrets env`() {
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText("SF_GITHUB_TOKEN=token")

        assertEquals(
            secretsFile.toAbsolutePath().normalize(),
            SecretsEnvLoader.defaultSecretsFile(environment = emptyMap(), workingDirectory = tempDir).toAbsolutePath().normalize(),
        )
    }

    @Test
    fun `default secrets file falls back to parent directory secrets env`() {
        val moduleDir = tempDir.resolve("softwarefactory")
        java.nio.file.Files.createDirectories(moduleDir)
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText("SF_GITHUB_TOKEN=token")

        assertEquals(
            secretsFile.toAbsolutePath().normalize(),
            SecretsEnvLoader.defaultSecretsFile(environment = emptyMap(), workingDirectory = moduleDir).toAbsolutePath().normalize(),
        )
    }

    @Test
    fun `explicit secrets file override wins`() {
        val override = tempDir.resolve("custom.env")

        assertEquals(
            override.toAbsolutePath().normalize(),
            SecretsEnvLoader.defaultSecretsFile(
                environment = mapOf("SF_SECRETS_FILE" to override.toString()),
                workingDirectory = tempDir,
            ).toAbsolutePath().normalize(),
        )
    }

    @Test
    fun `environment variables win over file values and blank file values are skipped`() {
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText(
            """
            SF_POLL_INTERVAL_MS=2000
            SF_MAX_PARALLEL_TESTER=
            SF_MAX_PARALLEL_TOTAL=4
            """.trimIndent(),
        )

        val resolved = SecretsEnvLoader(
            secretsFile = secretsFile,
            environment = mapOf(
                "SF_POLL_INTERVAL_MS" to "15000",
                "SF_MAX_PARALLEL_TESTER" to "1",
            ),
        ).resolvedValues()

        // Env var wins over the file value.
        assertEquals("15000", resolved["SF_POLL_INTERVAL_MS"])
        // Blank file value is skipped, so the env var provides the value.
        assertEquals("1", resolved["SF_MAX_PARALLEL_TESTER"])
        // File value is used when no env var overrides it.
        assertEquals("4", resolved["SF_MAX_PARALLEL_TOTAL"])
    }

    @Test
    fun `properties layer in order defaults below overrides below secrets below env`() {
        val propertiesDefault = tempDir.resolve("properties.default.env")
        propertiesDefault.writeText(
            """
            SF_POLL_INTERVAL_MS=1000
            SF_MAX_PARALLEL_TOTAL=4
            SF_DASHBOARD_USERNAME=admin
            """.trimIndent(),
        )
        val properties = tempDir.resolve("properties.env")
        properties.writeText(
            """
            SF_POLL_INTERVAL_MS=5000
            SF_MAX_PARALLEL_TOTAL=8
            """.trimIndent(),
        )
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText(
            """
            SF_MAX_PARALLEL_TOTAL=2
            """.trimIndent(),
        )

        val resolved = SecretsEnvLoader(
            secretsFile = secretsFile,
            propertiesFile = properties,
            propertiesDefaultFile = propertiesDefault,
            environment = mapOf("SF_POLL_INTERVAL_MS" to "9000"),
        ).resolvedValues()

        // env var beats every file.
        assertEquals("9000", resolved["SF_POLL_INTERVAL_MS"])
        // secrets.env beats properties.env beats properties.default.env.
        assertEquals("2", resolved["SF_MAX_PARALLEL_TOTAL"])
        // only the default file has it.
        assertEquals("admin", resolved["SF_DASHBOARD_USERNAME"])
    }

    @Test
    fun `loads secrets from secrets file while properties come from the properties files`() {
        val propertiesDefault = tempDir.resolve("properties.default.env")
        propertiesDefault.writeText("SF_KUBECONFIG=/tmp/from-default")
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText(
            """
            SF_GITHUB_TOKEN=github-token
            SF_DATABASE_URL=postgresql://user:pass@example/db
            SF_DATABASE_SCHEMA=software_factory
            """.trimIndent(),
        )

        val secrets = SecretsEnvLoader(
            secretsFile = secretsFile,
            propertiesDefaultFile = propertiesDefault,
            propertiesFile = tempDir.resolve("absent-properties.env"),
            environment = emptyMap(),
        ).load()

        assertEquals("github-token", secrets.githubToken)
        // SF_KUBECONFIG lives in properties.default.env yet still feeds the secrets load.
        assertEquals("/tmp/from-default", secrets.kubeconfig)
    }

    @Test
    fun `redacts database url including query string passwords`() {
        val secrets = FactorySecrets(
            trackerProjects = emptyList(),
            githubToken = "github-token",
            factoryDatabaseUrl = "postgresql://host/db?user=owner&password=secret&sslmode=require",
            factoryDatabaseSchema = "software_factory",
            kubeconfig = null,
            aiCredentialsDir = null,
            aiOauthToken = null,
            loadedFrom = "test",
        )

        val summary = secrets.redactedSummary().toString()

        assertTrue(summary.contains("postgresql://<redacted>"))
        assertFalse(summary.contains("secret"))
        assertFalse(summary.contains("password="))
    }

    private fun requiredEnvironment(): Map<String, String> = mapOf(
        "SF_GITHUB_TOKEN" to "env-github-token",
        "SF_DATABASE_URL" to "postgresql://env:pass@example/db",
        "SF_DATABASE_SCHEMA" to "software_factory",
    )
}
