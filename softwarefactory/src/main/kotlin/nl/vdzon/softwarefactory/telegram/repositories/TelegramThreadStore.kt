package nl.vdzon.softwarefactory.telegram.repositories

import nl.vdzon.softwarefactory.telegram.models.*

import nl.vdzon.softwarefactory.telegram.clients.*
import nl.vdzon.softwarefactory.telegram.repositories.*
import nl.vdzon.softwarefactory.telegram.services.*

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

    /** De sessie-id van de meest recent actieve thread in [chatId], of null (geen history). */
    fun activeRootSession(chatId: String): String?

    /** Slaat [sessionId] op als de actieve root-sessie voor [chatId] (last-write-wins). */
    fun setActiveRootSession(chatId: String, sessionId: String)
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

    override fun activeRootSession(chatId: String): String? =
        jdbcTemplate.query(
            "SELECT value FROM $schema.telegram_state WHERE key = ?",
            { rs, _ -> rs.getString("value") },
            "active_root:$chatId",
        ).firstOrNull()

    override fun setActiveRootSession(chatId: String, sessionId: String) {
        jdbcTemplate.update(
            """
            INSERT INTO $schema.telegram_state (key, value)
            VALUES (?, ?)
            ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
            """.trimIndent(),
            "active_root:$chatId",
            sessionId,
        )
    }
}
