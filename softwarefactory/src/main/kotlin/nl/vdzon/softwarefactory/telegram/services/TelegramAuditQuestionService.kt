package nl.vdzon.softwarefactory.telegram.services

import nl.vdzon.softwarefactory.telegram.AuditQuestionNotifier
import nl.vdzon.softwarefactory.telegram.TelegramMessageGateway
import nl.vdzon.softwarefactory.telegram.repositories.TelegramStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Stuurt de Telegram-melding voor een auditvraag en legt de reply-koppeling vast.
 *
 * Een audit heeft geen tracker-issue, dus de `telegram_pending_questions`-rij gebruikt dezelfde
 * synthetische sleutel als de rest van het audit-pad (`AUDIT:<project>:<type>`) en zet het
 * vraag-id in `source_phase` — daarmee weet [TelegramReplyService] welke vraag een reply beantwoordt
 * zonder dat de telegram-module van de audit-module hoeft af te weten.
 */
@Service
class TelegramAuditQuestionService(
    private val telegramClient: TelegramMessageGateway,
    private val store: TelegramStore,
) : AuditQuestionNotifier {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun notifyAuditQuestion(project: String, auditType: String, questionId: Long, question: String) {
        if (!telegramClient.enabled) return
        val chatId = telegramClient.defaultChatId ?: return
        val text = buildString {
            appendLine("❓ De audit $project/$auditType heeft een vraag")
            appendLine()
            appendLine(question.trim())
            appendLine()
            appendLine("↩️ Antwoord door op dit bericht te replyen. De audit draait daarna meteen verder.")
        }
        val messageId = telegramClient.sendMessage(text, chatId = chatId)
        if (messageId == null) {
            logger.warn("Telegram-melding voor auditvraag {} kon niet verstuurd worden.", questionId)
            return
        }
        store.savePending(chatId, messageId, auditKey(project, auditType), ISSUE_LEVEL, "$SOURCE_PHASE_PREFIX$questionId")
        logger.info("Auditvraag {} gemeld in Telegram ({}/{}).", questionId, project, auditType)
    }

    companion object {
        const val ISSUE_LEVEL = "AUDIT"
        const val SOURCE_PHASE_PREFIX = "audit-question:"

        fun auditKey(project: String, auditType: String) = "AUDIT:$project:$auditType"

        /** Het vraag-id uit een `source_phase` van een audit-pending, of null als het geen audit is. */
        fun questionIdFrom(sourcePhase: String): Long? =
            sourcePhase.removePrefix(SOURCE_PHASE_PREFIX).takeIf { it != sourcePhase }?.toLongOrNull()
    }
}
