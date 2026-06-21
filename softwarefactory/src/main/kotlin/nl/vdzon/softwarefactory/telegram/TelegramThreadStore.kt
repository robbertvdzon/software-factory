package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.config.FactorySecrets
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/**
 * Koppelt Telegram-berichten aan de claude-sessie van hun thread (reply-keten). Een reply op een
 * bericht hervat de sessie van dat bericht; een niet-reply-bericht start een nieuwe thread.
 */
interface TelegramThreadStore {
    /** De sessie van de thread waartoe [messageId] behoort, of null (onbekend → nieuwe thread). */
    fun sessionFor(chatId: String, messageId: Long): String?

    /** Koppelt [messageId] aan [sessionId] (idempotent). */
    fun map(chatId: String, messageId: Long, sessionId: String)
}

@Repository
class JdbcTelegramThreadStore(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) : TelegramThreadStore {
    private val schema = factorySecrets.factoryDatabaseSchema

    override fun sessionFor(chatId: String, messageId: Long): String? =
        jdbcTemplate.query(
            "SELECT session_id FROM $schema.telegram_threads WHERE chat_id = ? AND message_id = ?",
            { rs, _ -> rs.getString("session_id") },
            chatId,
            messageId,
        ).firstOrNull()

    override fun map(chatId: String, messageId: Long, sessionId: String) {
        jdbcTemplate.update(
            """
            INSERT INTO $schema.telegram_threads (chat_id, message_id, session_id)
            VALUES (?, ?, ?)
            ON CONFLICT (chat_id, message_id) DO UPDATE SET session_id = EXCLUDED.session_id
            """.trimIndent(),
            chatId,
            messageId,
            sessionId,
        )
    }
}
