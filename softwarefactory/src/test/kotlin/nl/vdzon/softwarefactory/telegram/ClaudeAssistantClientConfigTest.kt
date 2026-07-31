package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.telegram.clients.ClaudeAssistantClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Unit-tests voor het lezen van SF_ASSISTANT_IMAGE en SF_ASSISTANT_TIMEOUT_SECONDS via ConfigApi
 * (i.p.v. rechtstreeks System.getenv), inclusief de defaults bij ontbrekende/ongeldige waarden.
 */
class ClaudeAssistantClientConfigTest {

    private val minimalSecrets = FactorySecrets(
        trackerProjects = emptyList(),
        githubToken = "gh",
        factoryDatabaseUrl = "jdbc:postgresql://db/sf",
        factoryDatabaseSchema = "sf",
        kubeconfig = null,
        aiCredentialsDir = null,
        loadedFrom = "test",
        aiOauthToken = "oauth-tok",
    )

    private fun configWith(values: Map<String, String>): ConfigApi = object : ConfigApi {
        override fun resolvedValues(): Map<String, String> = values
    }

    private fun clientWith(values: Map<String, String>): ClaudeAssistantClient =
        ClaudeAssistantClient(minimalSecrets, configWith(values))

    private fun readPrivate(client: ClaudeAssistantClient, name: String): Any? {
        val field = ClaudeAssistantClient::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(client)
    }

    private fun image(client: ClaudeAssistantClient): String = readPrivate(client, "image") as String

    private fun timeoutSeconds(client: ClaudeAssistantClient): Long = readPrivate(client, "timeoutSeconds") as Long

    @Suppress("UNCHECKED_CAST")
    private fun dockerCommand(client: ClaudeAssistantClient): List<String> {
        val method = ClaudeAssistantClient::class.java.getDeclaredMethod(
            "dockerCommand",
            Path::class.java, String::class.java, String::class.java, String::class.java, String::class.java,
            Boolean::class.java, List::class.java, Map::class.java,
        )
        method.isAccessible = true
        return method.invoke(
            client,
            Path.of("work", "assistant", "chat", "sess").toAbsolutePath(),
            "sf-assistant-test",
            "system",
            "hallo",
            "session-id",
            false,
            emptyList<String>(),
            emptyMap<String, String>(),
        ) as List<String>
    }

    @Test
    fun `image en timeout komen uit de resolved config-waarden`() {
        val client = clientWith(
            mapOf("SF_ASSISTANT_IMAGE" to "assistant:sf-1487", "SF_ASSISTANT_TIMEOUT_SECONDS" to "120"),
        )

        assertEquals("assistant:sf-1487", image(client))
        assertEquals(120L, timeoutSeconds(client))
    }

    @Test
    fun `lege config levert de defaults op`() {
        val client = clientWith(emptyMap())

        assertEquals("assistant:local", image(client))
        assertEquals(3600L, timeoutSeconds(client))
    }

    @Test
    fun `blanke image valt terug op de default`() {
        val client = clientWith(mapOf("SF_ASSISTANT_IMAGE" to "   "))

        assertEquals("assistant:local", image(client))
    }

    @Test
    fun `niet-numerieke of niet-positieve timeout valt terug op de default`() {
        assertEquals(3600L, timeoutSeconds(clientWith(mapOf("SF_ASSISTANT_TIMEOUT_SECONDS" to "geen-getal"))))
        assertEquals(3600L, timeoutSeconds(clientWith(mapOf("SF_ASSISTANT_TIMEOUT_SECONDS" to ""))))
        assertEquals(3600L, timeoutSeconds(clientWith(mapOf("SF_ASSISTANT_TIMEOUT_SECONDS" to "0"))))
        assertEquals(3600L, timeoutSeconds(clientWith(mapOf("SF_ASSISTANT_TIMEOUT_SECONDS" to "-5"))))
    }

    @Test
    fun `docker-commando gebruikt de image uit de config`() {
        val command = dockerCommand(clientWith(mapOf("SF_ASSISTANT_IMAGE" to "assistant:sf-1487")))

        assertTrue(command.contains("assistant:sf-1487"), "Image uit de config ontbreekt in: $command")
        assertEquals("claude", command[command.indexOf("assistant:sf-1487") + 1], "Image staat niet vlak voor het commando")
    }

    @Test
    fun `docker-commando gebruikt de default image zonder config`() {
        val command = dockerCommand(clientWith(emptyMap()))

        assertTrue(command.contains("assistant:local"), "Default image ontbreekt in: $command")
    }
}
