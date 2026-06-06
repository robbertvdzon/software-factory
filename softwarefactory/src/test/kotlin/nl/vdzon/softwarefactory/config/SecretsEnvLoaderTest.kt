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
            SF_YOUTRACK_BASE_URL=https://youtrack.example
            SF_YOUTRACK_TOKEN=youtrack-secret
            SF_YOUTRACK_PROJECTS=SP,PNF
            SF_GITHUB_TOKEN=github-secret
            SF_DATABASE_URL=postgresql://user:pass@example/db
            SF_DATABASE_SCHEMA=software_factory
            SF_KUBECONFIG=/tmp/kubeconfig
            SF_AI_CREDENTIALS_DIR=/tmp/ai
            SF_CODEX_CREDENTIALS_DIR=/tmp/codex
            SF_AUTO_SYNC_AFTER_AGENT=false
            """.trimIndent(),
        )

        val secrets = SecretsEnvLoader(secretsFile = secretsFile, environment = emptyMap()).load()

        assertEquals("https://youtrack.example", secrets.youTrackBaseUrl)
        assertEquals("youtrack-secret", secrets.youTrackToken)
        assertEquals(listOf("SP", "PNF"), secrets.youTrackProjects)
        assertEquals("github-secret", secrets.githubToken)
        assertEquals("postgresql://user:pass@example/db", secrets.factoryDatabaseUrl)
        assertEquals("software_factory", secrets.factoryDatabaseSchema)
        assertEquals("/tmp/kubeconfig", secrets.kubeconfig)
        assertEquals("/tmp/ai", secrets.aiCredentialsDir)
        assertEquals("/tmp/codex", secrets.codexCredentialsDir)
        assertFalse(secrets.autoSyncAfterAgent)
    }

    @Test
    fun `falls back to environment when file is missing`() {
        val secretsFile = tempDir.resolve("missing.env")

        val secrets = SecretsEnvLoader(
            secretsFile = secretsFile,
            environment = requiredEnvironment(),
        ).load()

        assertEquals("https://youtrack.example", secrets.youTrackBaseUrl)
        assertEquals("env-youtrack-token", secrets.youTrackToken)
        assertEquals("env-github-token", secrets.githubToken)
        assertEquals("postgresql://env:pass@example/db", secrets.factoryDatabaseUrl)
        assertEquals("software_factory", secrets.factoryDatabaseSchema)
        assertEquals("system environment", secrets.loadedFrom)
        assertTrue(secrets.autoSyncAfterAgent)
    }

    @Test
    fun `fails when auto sync flag is not boolean`() {
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText(
            """
            SF_YOUTRACK_BASE_URL=https://youtrack.example
            SF_YOUTRACK_TOKEN=youtrack-token
            SF_GITHUB_TOKEN=github-token
            SF_DATABASE_URL=postgresql://user:pass@example/db
            SF_DATABASE_SCHEMA=software_factory
            SF_AUTO_SYNC_AFTER_AGENT=sometimes
            """.trimIndent(),
        )

        val exception = assertThrows(IllegalArgumentException::class.java) {
            SecretsEnvLoader(secretsFile = secretsFile, environment = emptyMap()).load()
        }

        assertEquals("SF_AUTO_SYNC_AFTER_AGENT must be either 'true' or 'false'.", exception.message)
    }

    @Test
    fun `allows branch specific database schemas`() {
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText(
            """
            SF_YOUTRACK_BASE_URL=https://youtrack.example
            SF_YOUTRACK_TOKEN=youtrack-token
            SF_GITHUB_TOKEN=github-token
            SF_DATABASE_URL=postgresql://software_factory:software_factory@localhost:5432/software_factory
            SF_DATABASE_SCHEMA=software_factory_sf_020
            """.trimIndent(),
        )

        val secrets = SecretsEnvLoader(secretsFile = secretsFile, environment = emptyMap()).load()

        assertEquals("software_factory_sf_020", secrets.factoryDatabaseSchema)
    }

    @Test
    fun `uses environment for keys missing from file`() {
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText(
            """
            SF_YOUTRACK_BASE_URL=https://file-youtrack.example
            """.trimIndent(),
        )

        val secrets = SecretsEnvLoader(
            secretsFile = secretsFile,
            environment = requiredEnvironment(),
        ).load()

        assertEquals("https://file-youtrack.example", secrets.youTrackBaseUrl)
        assertEquals("env-youtrack-token", secrets.youTrackToken)
        assertEquals("env-github-token", secrets.githubToken)
        assertEquals("postgresql://env:pass@example/db", secrets.factoryDatabaseUrl)
    }

    @Test
    fun `fails when required secrets are not available`() {
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText(
            """
            SF_YOUTRACK_BASE_URL=https://youtrack.example
            SF_YOUTRACK_TOKEN=
            """.trimIndent(),
        )

        val exception = assertThrows(MissingRequiredSecretsException::class.java) {
            SecretsEnvLoader(secretsFile = secretsFile, environment = emptyMap()).load()
        }

        assertEquals(
            "Missing required factory configuration: SF_YOUTRACK_TOKEN, SF_GITHUB_TOKEN, SF_DATABASE_URL, SF_DATABASE_SCHEMA. " +
                "Set them in $secretsFile or as system environment variables.",
            exception.message,
        )
    }

    @Test
    fun `fails when database schema is factory`() {
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText(
            """
            SF_YOUTRACK_BASE_URL=https://youtrack.example
            SF_YOUTRACK_TOKEN=youtrack-token
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
            export SF_YOUTRACK_BASE_URL="https://youtrack.example"
            SF_YOUTRACK_TOKEN='youtrack-token'
            SF_GITHUB_TOKEN=github-token
            SF_DATABASE_URL="postgresql://user:pass@example/db"
            SF_DATABASE_SCHEMA=software_factory
            """.trimIndent(),
        )

        val secrets = SecretsEnvLoader(secretsFile = secretsFile, environment = emptyMap()).load()

        assertEquals("https://youtrack.example", secrets.youTrackBaseUrl)
        assertEquals("youtrack-token", secrets.youTrackToken)
        assertEquals("postgresql://user:pass@example/db", secrets.factoryDatabaseUrl)
    }

    @Test
    fun `default secrets file is current directory secrets env`() {
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText("SF_YOUTRACK_TOKEN=token")

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
        secretsFile.writeText("SF_YOUTRACK_TOKEN=token")

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
    fun `resolved values merge file values over environment and skip blank file values`() {
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText(
            """
            SF_POLL_INTERVAL_MS=2000
            SF_MAX_PARALLEL_TESTER=
            """.trimIndent(),
        )

        val resolved = SecretsEnvLoader(
            secretsFile = secretsFile,
            environment = mapOf(
                "SF_POLL_INTERVAL_MS" to "15000",
                "SF_MAX_PARALLEL_TESTER" to "1",
            ),
        ).resolvedValues()

        assertEquals("2000", resolved["SF_POLL_INTERVAL_MS"])
        assertEquals("1", resolved["SF_MAX_PARALLEL_TESTER"])
    }

    @Test
    fun `redacts database url including query string passwords`() {
        val secrets = FactorySecrets(
            youTrackBaseUrl = "https://youtrack.example",
            youTrackToken = "youtrack-token",
            youTrackProjects = emptyList(),
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
        "SF_YOUTRACK_BASE_URL" to "https://youtrack.example",
        "SF_YOUTRACK_TOKEN" to "env-youtrack-token",
        "SF_GITHUB_TOKEN" to "env-github-token",
        "SF_DATABASE_URL" to "postgresql://env:pass@example/db",
        "SF_DATABASE_SCHEMA" to "software_factory",
    )
}
