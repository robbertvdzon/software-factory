package nl.vdzon.softwarefactory.e2e

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests op de test-dubbel zelf (SF-1615). Geen Spring/Testcontainers nodig, maar het bestand staat
 * in het `e2e`-package en draait daarom mee onder failsafe (`mvn verify`), zie `softwarefactory/pom.xml`.
 *
 * Zonder deze tests raakt geen enkele testroute `getUpdates`/`sendPhoto`/`reset` van de dubbel aan
 * en kan de registratie stil verrotten.
 */
class RecordingTelegramClientTest {

    @Test
    fun `getUpdates blokkeert kort en geeft een lege lijst terug`() {
        val client = RecordingTelegramClient()

        val startedAt = System.nanoTime()
        val updates = client.getUpdates(offset = null, timeoutSeconds = 30)
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(updates.isEmpty(), "de e2e-suite voert geen inkomende updates op")
        // AC1: één poll-ronde kost minimaal ~100 ms, dus de TelegramPoller-thread spint niet.
        assertTrue(elapsedMillis >= 100, "getUpdates blokkeerde maar ${elapsedMillis}ms; verwacht >= 100ms")
    }

    @Test
    fun `sendPhoto legt de verzending vast en geeft true terug`() {
        val client = RecordingTelegramClient()

        val sent = client.sendPhoto(chatId = "chat-1", file = Path.of("/tmp/shot", "screenshot.png"), caption = "kijk")

        assertTrue(sent, "de dubbel doet alsof de upload slaagde")
        assertEquals(
            listOf(RecordingTelegramClient.SentPhoto("chat-1", "screenshot.png", "kijk")),
            client.photos.toList(),
        )
    }

    @Test
    fun `reset leegt zowel de berichten als de vastgelegde fotos`() {
        val client = RecordingTelegramClient()
        client.sendMessage("hallo", replyToMessageId = null, chatId = null)
        client.sendPhoto(chatId = "chat-1", file = Path.of("screenshot.png"), caption = null)

        client.reset()

        // AC3: E2eTestBase.resetSharedState roept reset() aan, dus elke test start met beide leeg.
        assertTrue(client.messages.isEmpty(), "messages leeg na reset")
        assertTrue(client.photos.isEmpty(), "photos leeg na reset")
    }
}
