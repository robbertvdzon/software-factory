package nl.vdzon.softwarefactory.config

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
            SF_JIRA_BASE_URL=https://vdzon.atlassian.net
            SF_JIRA_EMAIL=robbert@example.com
            SF_JIRA_API_KEY=jira-secret
            SF_GITHUB_TOKEN=github-secret
            SF_DATABASE_URL=postgresql://user:pass@example/db
            SF_DATABASE_SCHEMA=software_factory
            SF_KUBECONFIG=/tmp/kubeconfig
            SF_AI_CREDENTIALS_DIR=/tmp/ai
            """.trimIndent(),
        )

        val secrets = SecretsEnvLoader(secretsFile = secretsFile, environment = emptyMap()).load()

        assertEquals("https://vdzon.atlassian.net", secrets.jiraBaseUrl)
        assertEquals("robbert@example.com", secrets.jiraEmail)
        assertEquals("jira-secret", secrets.jiraApiKey)
        assertEquals("github-secret", secrets.githubToken)
        assertEquals("postgresql://user:pass@example/db", secrets.factoryDatabaseUrl)
        assertEquals("software_factory", secrets.factoryDatabaseSchema)
        assertEquals("/tmp/kubeconfig", secrets.kubeconfig)
        assertEquals("/tmp/ai", secrets.aiCredentialsDir)
    }

    @Test
    fun `falls back to environment when file is missing`() {
        val secretsFile = tempDir.resolve("missing.env")

        val secrets = SecretsEnvLoader(
            secretsFile = secretsFile,
            environment = requiredEnvironment(),
        ).load()

        assertEquals("https://jira.example", secrets.jiraBaseUrl)
        assertEquals("env@example.com", secrets.jiraEmail)
        assertEquals("env-jira-token", secrets.jiraApiKey)
        assertEquals("env-github-token", secrets.githubToken)
        assertEquals("postgresql://env:pass@example/db", secrets.factoryDatabaseUrl)
        assertEquals("system environment", secrets.loadedFrom)
    }

    @Test
    fun `uses environment for keys missing from file`() {
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText(
            """
            SF_JIRA_BASE_URL=https://file-jira.example
            SF_JIRA_EMAIL=file@example.com
            """.trimIndent(),
        )

        val secrets = SecretsEnvLoader(
            secretsFile = secretsFile,
            environment = requiredEnvironment(),
        ).load()

        assertEquals("https://file-jira.example", secrets.jiraBaseUrl)
        assertEquals("file@example.com", secrets.jiraEmail)
        assertEquals("env-jira-token", secrets.jiraApiKey)
        assertEquals("env-github-token", secrets.githubToken)
        assertEquals("postgresql://env:pass@example/db", secrets.factoryDatabaseUrl)
    }

    @Test
    fun `fails when required secrets are not available`() {
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText(
            """
            SF_JIRA_BASE_URL=https://jira.example
            SF_JIRA_EMAIL=
            """.trimIndent(),
        )

        val exception = assertThrows(MissingRequiredSecretsException::class.java) {
            SecretsEnvLoader(secretsFile = secretsFile, environment = emptyMap()).load()
        }

        assertEquals(
            "Missing required factory configuration: SF_JIRA_EMAIL, SF_JIRA_API_KEY, SF_GITHUB_TOKEN, SF_DATABASE_URL, SF_DATABASE_SCHEMA. " +
                "Set them in $secretsFile or as system environment variables.",
            exception.message,
        )
    }

    @Test
    fun `fails when database schema is factory`() {
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText(
            """
            SF_JIRA_BASE_URL=https://jira.example
            SF_JIRA_EMAIL=robbert@example.com
            SF_JIRA_API_KEY=jira-token
            SF_GITHUB_TOKEN=github-token
            SF_DATABASE_URL=postgresql://user:pass@example/db
            SF_DATABASE_SCHEMA=factory
            """.trimIndent(),
        )

        val exception = assertThrows(IllegalArgumentException::class.java) {
            SecretsEnvLoader(secretsFile = secretsFile, environment = emptyMap()).load()
        }

        assertEquals(
            "SF_DATABASE_SCHEMA must be 'software_factory'; do not use the existing 'factory' schema.",
            exception.message,
        )
    }

    @Test
    fun `parses comments export prefix and quoted values`() {
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText(
            """
            # Local software factory config
            export SF_JIRA_BASE_URL="https://jira.example"
            SF_JIRA_EMAIL='robbert@example.com'
            SF_JIRA_API_KEY=jira-token
            SF_GITHUB_TOKEN=github-token
            SF_DATABASE_URL="postgresql://user:pass@example/db"
            SF_DATABASE_SCHEMA=software_factory
            """.trimIndent(),
        )

        val secrets = SecretsEnvLoader(secretsFile = secretsFile, environment = emptyMap()).load()

        assertEquals("https://jira.example", secrets.jiraBaseUrl)
        assertEquals("robbert@example.com", secrets.jiraEmail)
        assertEquals("postgresql://user:pass@example/db", secrets.factoryDatabaseUrl)
    }

    @Test
    fun `default secrets file is repo root secrets env`() {
        assertEquals(
            Path.of("secrets.env").toAbsolutePath().normalize(),
            SecretsEnvLoader.defaultSecretsFile().toAbsolutePath().normalize(),
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
            jiraBaseUrl = "https://jira.example",
            jiraEmail = "robbert@example.com",
            jiraApiKey = "jira-token",
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
        "SF_JIRA_BASE_URL" to "https://jira.example",
        "SF_JIRA_EMAIL" to "env@example.com",
        "SF_JIRA_API_KEY" to "env-jira-token",
        "SF_GITHUB_TOKEN" to "env-github-token",
        "SF_DATABASE_URL" to "postgresql://env:pass@example/db",
        "SF_DATABASE_SCHEMA" to "software_factory",
    )
}
