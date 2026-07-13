package nl.vdzon.softwarefactory.telegram.repositories

import nl.vdzon.softwarefactory.telegram.models.*

import nl.vdzon.softwarefactory.telegram.clients.*
import nl.vdzon.softwarefactory.telegram.repositories.*
import nl.vdzon.softwarefactory.telegram.services.*

import nl.vdzon.softwarefactory.config.FactorySecrets
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/**
 * Sentinel-`source_phase` voor een "klaar om te mergen"-melding: geen echte fase, maar een marker
 * zodat de reply-handler weet dat een `merge`-reply de MERGE-command moet queue'en.
 */
/** Een openstaande vraag waarop via Telegram-reply geantwoord kan worden. */
data class PendingQuestion(
    val chatId: String,
    val messageId: Long,
    val issueKey: String,
    /** "STORY" of "SUBTASK" — bepaalt welke fase-route het antwoord volgt. */
    val issueLevel: String,
    /** De `*-with-questions`-fase (trackerValue) waarin het issue stond toen we de vraag stuurden. */
    val sourcePhase: String,
)

/**
 * Persistente staat voor de Telegram-integratie:
 *  - welke meldingen al verstuurd zijn (idempotentie per fase-transitie),
 *  - de koppeling vraag-bericht -> issue (voor replies),
 *  - de getUpdates-offset.
 */
interface TelegramStore {
    fun alreadyNotified(issueKey: String, signature: String): Boolean
    fun recordNotified(issueKey: String, signature: String)

    /** Wist alle melding-registraties van [issueKey], zodat een volgende toestand opnieuw meldt. */
    fun clearNotifications(issueKey: String)

    fun savePending(chatId: String, messageId: Long, issueKey: String, issueLevel: String, sourcePhase: String)
    fun findPending(chatId: String, messageId: Long): PendingQuestion?
    fun deletePending(chatId: String, messageId: Long)

    fun getUpdatesOffset(): Long?
    fun setUpdatesOffset(offset: Long)
}

@Repository
class JdbcTelegramStore(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) : TelegramStore {
    private val schema = factorySecrets.factoryDatabaseSchema

    override fun alreadyNotified(issueKey: String, signature: String): Boolean {
        val count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM $schema.telegram_notifications WHERE issue_key = ? AND signature = ?",
            Long::class.java,
            issueKey,
            signature,
        )
        return (count ?: 0L) > 0
    }

    override fun recordNotified(issueKey: String, signature: String) {
        jdbcTemplate.update(
            """
            INSERT INTO $schema.telegram_notifications (issue_key, signature)
            VALUES (?, ?)
            ON CONFLICT (issue_key, signature) DO NOTHING
            """.trimIndent(),
            issueKey,
            signature,
        )
    }

    override fun clearNotifications(issueKey: String) {
        jdbcTemplate.update("DELETE FROM $schema.telegram_notifications WHERE issue_key = ?", issueKey)
    }

    override fun savePending(chatId: String, messageId: Long, issueKey: String, issueLevel: String, sourcePhase: String) {
        jdbcTemplate.update(
            """
            INSERT INTO $schema.telegram_pending_questions (chat_id, message_id, issue_key, issue_level, source_phase)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (chat_id, message_id) DO NOTHING
            """.trimIndent(),
            chatId,
            messageId,
            issueKey,
            issueLevel,
            sourcePhase,
        )
    }

    override fun findPending(chatId: String, messageId: Long): PendingQuestion? =
        jdbcTemplate.query(
            "SELECT chat_id, message_id, issue_key, issue_level, source_phase FROM $schema.telegram_pending_questions WHERE chat_id = ? AND message_id = ?",
            { rs, _ ->
                PendingQuestion(
                    chatId = rs.getString("chat_id"),
                    messageId = rs.getLong("message_id"),
                    issueKey = rs.getString("issue_key"),
                    issueLevel = rs.getString("issue_level"),
                    sourcePhase = rs.getString("source_phase"),
                )
            },
            chatId,
            messageId,
        ).firstOrNull()

    override fun deletePending(chatId: String, messageId: Long) {
        jdbcTemplate.update("DELETE FROM $schema.telegram_pending_questions WHERE chat_id = ? AND message_id = ?", chatId, messageId)
    }

    override fun getUpdatesOffset(): Long? =
        jdbcTemplate.query(
            "SELECT value FROM $schema.telegram_state WHERE key = ?",
            { rs, _ -> rs.getString("value").toLongOrNull() },
            UPDATES_OFFSET_KEY,
        ).firstOrNull()

    override fun setUpdatesOffset(offset: Long) {
        jdbcTemplate.update(
            """
            INSERT INTO $schema.telegram_state (key, value)
            VALUES (?, ?)
            ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
            """.trimIndent(),
            UPDATES_OFFSET_KEY,
            offset.toString(),
        )
    }

    private companion object {
        private const val UPDATES_OFFSET_KEY = "updates_offset"
    }
}
