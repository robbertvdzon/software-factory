package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.telegram.models.AssistantReply

internal const val MERGE_READY_PHASE = "merge-ready"

interface TelegramNotifier {
    fun notifyPending()
}

interface TelegramMessageGateway {
    val enabled: Boolean
    val defaultChatId: String?
    fun sendMessage(text: String, replyToMessageId: Long? = null, chatId: String? = null): Long?
}

interface AssistantClient {
    val enabled: Boolean
    fun askForSummary(systemPrompt: String, userMessage: String, extraEnv: Map<String, String>, timeoutSeconds: Long): AssistantReply
}

/**
 * Meldt een blokkerende vraag van een auditor en onthoudt het verstuurde bericht, zodat een reply
 * erop als antwoord telt (zie `TelegramReplyService`).
 *
 * Bestaat als poort in de root van de telegram-module omdat de audit-kant (`dashboard`) wél van
 * `telegram` mag afhangen maar niet van `telegram :: repositories` — het opslaan van de
 * reply-koppeling hoort dus aan deze kant van de grens te gebeuren.
 */
interface AuditQuestionNotifier {
    fun notifyAuditQuestion(project: String, auditType: String, questionId: Long, question: String)
}
