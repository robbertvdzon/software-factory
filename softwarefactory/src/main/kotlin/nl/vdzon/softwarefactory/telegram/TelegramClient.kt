package nl.vdzon.softwarefactory.telegram

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Eén binnengekomen Telegram-update (alleen de velden die we gebruiken). */
data class TelegramUpdate(
    val updateId: Long,
    val chatId: String?,
    val text: String?,
    /** Gezet wanneer dit bericht een reply is op een eerder bericht. */
    val replyToMessageId: Long?,
)

/**
 * Dunne client voor de Telegram Bot API. Telegram draait op het publieke internet, dus de
 * standaard-truststore volstaat (anders dan [nl.vdzon.softwarefactory.youtrack.clients.YouTrackClient],
 * die ook het lab-CA moet vertrouwen).
 *
 * De client is een no-op zolang [FactorySecrets.telegramEnabled] false is: dan blijft de hele
 * feature uit zonder dat de rest van de factory ervan weet.
 */
@Component
class TelegramClient(
    private val secrets: FactorySecrets,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    val enabled: Boolean get() = secrets.telegramEnabled

    private val apiBase: String?
        get() = secrets.telegramBotToken?.takeIf { it.isNotBlank() }?.let { "https://api.telegram.org/bot$it" }

    /** Het standaard-kanaal (globale chat) waar meldingen heen gaan zonder project-kanaal. */
    val defaultChatId: String? get() = secrets.telegramChatId?.takeIf { it.isNotBlank() }

    /**
     * Stuurt [text] naar [chatId] (of het globale kanaal als die null/leeg is). Geeft het Telegram
     * message_id terug (nodig om een latere reply te koppelen), of null bij een fout of wanneer de
     * feature uit staat.
     */
    fun sendMessage(text: String, replyToMessageId: Long? = null, chatId: String? = null): Long? {
        val base = apiBase ?: return null
        val targetChat = chatId?.takeIf { it.isNotBlank() } ?: defaultChatId ?: return null
        val body = buildMap<String, Any?> {
            put("chat_id", targetChat)
            put("text", text)
            // Telegram linkt URLs in platte tekst vanzelf; geen parse_mode => geen escaping-gedoe.
            put("disable_web_page_preview", true)
            replyToMessageId?.let { put("reply_to_message_id", it) }
        }
        val response = post("$base/sendMessage", body) ?: return null
        return objectMapper.readTree(response).path("result").path("message_id")
            .takeIf { it.isNumber }?.asLong()
    }

    /** Toont kort een status ("typing", "upload_photo", …) in de chat. Best-effort; faalt stil. */
    fun sendChatAction(chatId: String, action: String = "typing") {
        val base = apiBase ?: return
        val target = chatId.takeIf { it.isNotBlank() } ?: defaultChatId ?: return
        post("$base/sendChatAction", mapOf("chat_id" to target, "action" to action))
    }

    /**
     * Long-poll voor nieuwe updates vanaf [offset]. Blokkeert maximaal [timeoutSeconds] op de server
     * tot er iets is. Geeft een lege lijst terug bij een fout of wanneer de feature uit staat.
     */
    fun getUpdates(offset: Long?, timeoutSeconds: Int = 25): List<TelegramUpdate> {
        val base = apiBase ?: return emptyList()
        val body = buildMap<String, Any?> {
            put("timeout", timeoutSeconds)
            offset?.let { put("offset", it) }
            // Alleen normale berichten; callback-queries e.d. negeren we.
            put("allowed_updates", listOf("message"))
        }
        val response = post("$base/getUpdates", body, readTimeout = Duration.ofSeconds((timeoutSeconds + 10).toLong()))
            ?: return emptyList()
        val root = objectMapper.readTree(response)
        if (!root.path("ok").asBoolean(false)) {
            logger.warn("Telegram getUpdates niet ok: {}", root.path("description").asText(""))
            return emptyList()
        }
        return root.path("result").mapNotNull { node ->
            val updateId = node.path("update_id").takeIf { it.isNumber }?.asLong() ?: return@mapNotNull null
            val message = node.path("message")
            TelegramUpdate(
                updateId = updateId,
                chatId = message.path("chat").path("id").takeIf { it.isNumber || it.isTextual }?.asText(),
                // Bij een foto/bestand staat de tekst in `caption` i.p.v. `text`. De afbeelding zelf
                // kunnen we (nog) niet lezen, maar zo gaat een bijschrift niet verloren.
                text = message.path("text").asText(null)?.takeIf { it.isNotBlank() }
                    ?: message.path("caption").asText(null)?.takeIf { it.isNotBlank() },
                replyToMessageId = message.path("reply_to_message").path("message_id")
                    .takeIf { it.isNumber }?.asLong(),
            )
        }
    }

    private fun post(url: String, body: Map<String, Any?>, readTimeout: Duration = Duration.ofSeconds(15)): String? =
        try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(readTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                logger.warn("Telegram-call {} faalde met status {}: {}", url.substringAfterLast('/'), response.statusCode(), response.body()?.take(300))
                null
            } else {
                response.body()
            }
        } catch (exception: Exception) {
            logger.warn("Telegram-call {} faalde: {}", url.substringAfterLast('/'), exception.message)
            null
        }
}
