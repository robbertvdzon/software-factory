package nl.vdzon.softwarefactory.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
            JIRA_BASE_URL=https://vdzon.atlassian.net
            JIRA_EMAIL=robbert@example.com
            JIRA_API_KEY=jira-secret
            GITHUB_TOKEN=github-secret
            FACTORY_DATABASE_URL=postgresql://user:pass@example/db
            KUBECONFIG=/tmp/kubeconfig
            AI_CREDENTIALS_DIR=/tmp/ai
            """.trimIndent(),
        )

        val secrets = SecretsEnvLoader(secretsFile = secretsFile, environment = emptyMap()).load()

        assertEquals("https://vdzon.atlassian.net", secrets.jiraBaseUrl)
        assertEquals("robbert@example.com", secrets.jiraEmail)
        assertEquals("jira-secret", secrets.jiraApiKey)
        assertEquals("github-secret", secrets.githubToken)
        assertEquals("postgresql://user:pass@example/db", secrets.factoryDatabaseUrl)
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
            JIRA_BASE_URL=https://file-jira.example
            JIRA_EMAIL=file@example.com
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
            JIRA_BASE_URL=https://jira.example
            JIRA_EMAIL=
            """.trimIndent(),
        )

        val exception = assertThrows(MissingRequiredSecretsException::class.java) {
            SecretsEnvLoader(secretsFile = secretsFile, environment = emptyMap()).load()
        }

        assertEquals(
            "Missing required factory configuration: JIRA_EMAIL, JIRA_API_KEY, GITHUB_TOKEN, FACTORY_DATABASE_URL. " +
                "Set them in $secretsFile or as system environment variables.",
            exception.message,
        )
    }

    @Test
    fun `parses comments export prefix and quoted values`() {
        val secretsFile = tempDir.resolve("secrets.env")
        secretsFile.writeText(
            """
            # Local software factory config
            export JIRA_BASE_URL="https://jira.example"
            JIRA_EMAIL='robbert@example.com'
            JIRA_API_KEY=jira-token
            GITHUB_TOKEN=github-token
            FACTORY_DATABASE_URL="postgresql://user:pass@example/db"
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

    private fun requiredEnvironment(): Map<String, String> = mapOf(
        "JIRA_BASE_URL" to "https://jira.example",
        "JIRA_EMAIL" to "env@example.com",
        "JIRA_API_KEY" to "env-jira-token",
        "GITHUB_TOKEN" to "env-github-token",
        "FACTORY_DATABASE_URL" to "postgresql://env:pass@example/db",
    )
}
