package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.config.FactorySecrets
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/**
 * Onthoudt per Telegram-chat de claude session-id, zodat een vervolgvraag via `claude --resume`
 * de eerdere conversatie meeneemt (multi-turn). `/new` wist de koppeling → volgende vraag start vers.
 */
interface TelegramConversationStore {
    fun sessionId(chatId: String): String?
    fun setSessionId(chatId: String, sessionId: String)
    fun clear(chatId: String)
}

@Repository
class JdbcTelegramConversationStore(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) : TelegramConversationStore {
    private val schema = factorySecrets.factoryDatabaseSchema

    override fun sessionId(chatId: String): String? =
        jdbcTemplate.query(
            "SELECT session_id FROM $schema.telegram_conversations WHERE chat_id = ?",
            { rs, _ -> rs.getString("session_id") },
            chatId,
        ).firstOrNull()

    override fun setSessionId(chatId: String, sessionId: String) {
        jdbcTemplate.update(
            """
            INSERT INTO $schema.telegram_conversations (chat_id, session_id, updated_at)
            VALUES (?, ?, now())
            ON CONFLICT (chat_id) DO UPDATE SET session_id = EXCLUDED.session_id, updated_at = now()
            """.trimIndent(),
            chatId,
            sessionId,
        )
    }

    override fun clear(chatId: String) {
        jdbcTemplate.update("DELETE FROM $schema.telegram_conversations WHERE chat_id = ?", chatId)
    }
}
