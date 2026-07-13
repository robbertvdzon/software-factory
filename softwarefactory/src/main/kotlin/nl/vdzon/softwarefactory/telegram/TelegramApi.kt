package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.telegram.models.AssistantReply
import nl.vdzon.softwarefactory.telegram.models.AssistantStatus

internal const val MERGE_READY_PHASE = "merge-ready"

interface TelegramNotifier {
    fun notifyPending()
}

interface TelegramAssistantApi {
    val enabled: Boolean
    fun status(): AssistantStatus
    fun handle(chatId: String, rawText: String, photoFileId: String?, messageId: Long?, replyToMessageId: Long?)
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
