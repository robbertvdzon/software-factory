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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/** Eén binnengekomen Telegram-update (alleen de velden die we gebruiken). */
data class TelegramUpdate(
    val updateId: Long,
    val chatId: String?,
    val text: String?,
    /** Het eigen message_id van dit bericht (om op te kunnen replyen/koppelen aan een thread). */
    val messageId: Long?,
    /** Gezet wanneer dit bericht een reply is op een eerder bericht. */
    val replyToMessageId: Long?,
    /** `file_id` van een meegestuurde foto (hoogste resolutie), of null. */
    val photoFileId: String? = null,
)

/**
 * Dunne client voor de Telegram Bot API. Telegram draait op het publieke internet, dus de
 * standaard-truststore volstaat (geen lab-CA nodig, anders dan bij cluster-interne calls).
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
     * message_id van het EERSTE verstuurde bericht terug (nodig om een latere reply te koppelen), of
     * null bij een fout of wanneer de feature uit staat.
     *
     * Telegram weigert berichten boven [TELEGRAM_MAX_CHARS] tekens (HTTP 400 "message is too long").
     * Daarom knippen we lange tekst op in losse berichten — maximaal [MAX_MESSAGES] achter elkaar. Past
     * de tekst daar niet in, dan is het laatste bericht [TRUNCATION_NOTICE] i.p.v. nóg een stuk tekst.
     * Alleen het eerste bericht is een reply op [replyToMessageId]; de rest volgt er los onder.
     */
    fun sendMessage(text: String, replyToMessageId: Long? = null, chatId: String? = null): Long? {
        val base = apiBase ?: return null
        val targetChat = chatId?.takeIf { it.isNotBlank() } ?: defaultChatId ?: return null
        val chunks = chunkText(text, TELEGRAM_MAX_CHARS)
        val toSend = if (chunks.size <= MAX_MESSAGES) chunks
        else chunks.take(MAX_MESSAGES - 1) + TRUNCATION_NOTICE
        var firstMessageId: Long? = null
        toSend.forEachIndexed { index, chunk ->
            val id = sendSingleMessage(base, targetChat, chunk, replyToMessageId.takeIf { index == 0 })
            if (index == 0) firstMessageId = id
        }
        return firstMessageId
    }

    private fun sendSingleMessage(base: String, targetChat: String, text: String, replyToMessageId: Long?): Long? {
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

    /**
     * Knipt [text] op in stukken van hooguit [limit] tekens, bij voorkeur op regelgrenzen (zodat een
     * bericht niet middenin een regel breekt). Een losse regel die zelf langer is dan [limit] wordt hard
     * doormidden geknipt. Past de tekst in één stuk, dan komt er één element terug.
     */
    internal fun chunkText(text: String, limit: Int): List<String> {
        if (text.length <= limit) return listOf(text)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                chunks.add(current.toString())
                current.setLength(0)
            }
        }
        for (line in text.split("\n")) {
            var remaining = line
            // Een enkele regel die niet eens op zichzelf past: hard in stukken van [limit] knippen.
            while (remaining.length > limit) {
                flush()
                chunks.add(remaining.substring(0, limit))
                remaining = remaining.substring(limit)
            }
            val piece = if (current.isEmpty()) remaining else "\n$remaining"
            if (current.length + piece.length > limit) {
                flush()
                current.append(remaining)
            } else {
                current.append(piece)
            }
        }
        flush()
        return chunks
    }

    /** Downloadt een Telegram-bestand (op `file_id`) naar [dest]. @return true bij succes. */
    fun downloadFile(fileId: String, dest: Path): Boolean {
        val base = apiBase ?: return false
        val token = secrets.telegramBotToken?.takeIf { it.isNotBlank() } ?: return false
        val getFile = post("$base/getFile", mapOf("file_id" to fileId)) ?: return false
        val filePath = objectMapper.readTree(getFile).path("result").path("file_path").asText(null)
            ?.takeIf { it.isNotBlank() } ?: return false
        return try {
            Files.createDirectories(dest.parent)
            val request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/file/bot$token/$filePath"))
                .timeout(Duration.ofSeconds(60)).GET().build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(dest))
            response.statusCode() in 200..299
        } catch (exception: Exception) {
            logger.warn("Telegram-download faalde: {}", exception.message)
            false
        }
    }

    /** Stuurt een afbeelding naar [chatId] (multipart upload). @return true bij succes. */
    fun sendPhoto(chatId: String, file: Path, caption: String? = null): Boolean {
        val base = apiBase ?: return false
        val target = chatId.takeIf { it.isNotBlank() } ?: defaultChatId ?: return false
        return try {
            val boundary = "----sf-telegram-${UUID.randomUUID()}"
            val request = HttpRequest.newBuilder(URI.create("$base/sendPhoto"))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(photoMultipart(boundary, target, caption, file)))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                logger.warn("Telegram-sendPhoto faalde met status {}: {}", response.statusCode(), response.body()?.take(300))
                false
            } else {
                true
            }
        } catch (exception: Exception) {
            logger.warn("Telegram-sendPhoto faalde: {}", exception.message)
            false
        }
    }

    private fun photoMultipart(boundary: String, chatId: String, caption: String?, file: Path): ByteArray {
        val textPart = StringBuilder()
        fun field(name: String, value: String) {
            textPart.append("--$boundary\r\n")
                .append("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                .append(value).append("\r\n")
        }
        field("chat_id", chatId)
        caption?.takeIf { it.isNotBlank() }?.let { field("caption", it.take(1000)) }
        val fileHeader = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"photo\"; filename=\"${file.fileName}\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
        return textPart.toString().toByteArray(StandardCharsets.UTF_8) +
            fileHeader.toByteArray(StandardCharsets.UTF_8) +
            Files.readAllBytes(file) +
            "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)
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
                messageId = message.path("message_id").takeIf { it.isNumber }?.asLong(),
                // Bij een foto staat de tekst in `caption` i.p.v. `text`.
                text = message.path("text").asText(null)?.takeIf { it.isNotBlank() }
                    ?: message.path("caption").asText(null)?.takeIf { it.isNotBlank() },
                replyToMessageId = message.path("reply_to_message").path("message_id")
                    .takeIf { it.isNumber }?.asLong(),
                // `photo` is een lijst resoluties; pak de grootste (hoogste file_size).
                photoFileId = message.path("photo").takeIf { it.isArray && it.size() > 0 }
                    ?.maxByOrNull { it.path("file_size").asLong(0) }
                    ?.path("file_id")?.asText(null)?.takeIf { it.isNotBlank() },
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

    companion object {
        /** Harde limiet van Telegram per bericht; daarboven volgt HTTP 400 "message is too long". */
        private const val TELEGRAM_MAX_CHARS = 4096

        /** Hooguit zoveel berichten achter elkaar voor één antwoord (incl. de truncatie-melding). */
        private const val MAX_MESSAGES = 8

        /** Het laatste bericht als de tekst niet binnen [MAX_MESSAGES] berichten past. */
        private const val TRUNCATION_NOTICE = "rest of message truncated"
    }
}
