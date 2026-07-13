package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.telegram.clients.*
import nl.vdzon.softwarefactory.telegram.repositories.*
import nl.vdzon.softwarefactory.telegram.services.*

import nl.vdzon.softwarefactory.telegram.models.*

import nl.vdzon.softwarefactory.config.FactorySecrets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit-tests voor het opknippen van lange antwoorden ([TelegramClient.chunkText]). Telegram weigert
 * berichten boven 4096 tekens (HTTP 400 "message is too long"), waardoor lange assistent-antwoorden
 * vroeger stilletjes verdwenen.
 */
class TelegramClientChunkTest {

    private val client = TelegramClient(
        FactorySecrets(
            trackerProjects = emptyList(),
            githubToken = "gh",
            factoryDatabaseUrl = "jdbc:postgresql://db/sf",
            factoryDatabaseSchema = "sf",
            kubeconfig = null,
            aiCredentialsDir = null,
            loadedFrom = "test",
            aiOauthToken = "oauth-tok",
        ),
    )

    @Test
    fun `korte tekst blijft één bericht`() {
        val chunks = client.chunkText("hallo wereld", 4096)
        assertEquals(listOf("hallo wereld"), chunks)
    }

    @Test
    fun `breekt op regelgrenzen en blijft binnen de limiet`() {
        // Tien regels van 5 tekens. Bij limiet 12 passen er twee per bericht (5+1+5=11), een derde niet.
        val text = (1..10).joinToString("\n") { "abcde" }
        val chunks = client.chunkText(text, 12)
        assertTrue(chunks.all { it.length <= 12 }, "elk stuk binnen de limiet")
        // Geknipt op \n (geen regel werd hard doormidden geknipt), dus samenvoegen herstelt de tekst.
        assertEquals(text, chunks.joinToString("\n"))
    }

    @Test
    fun `een enkele te lange regel wordt hard doormidden geknipt`() {
        val text = "x".repeat(50)
        val chunks = client.chunkText(text, 20)
        assertEquals(listOf("x".repeat(20), "x".repeat(20), "x".repeat(10)), chunks)
    }

    @Test
    fun `tekst precies op de limiet blijft één bericht`() {
        val text = "y".repeat(4096)
        assertEquals(listOf(text), client.chunkText(text, 4096))
    }
}
