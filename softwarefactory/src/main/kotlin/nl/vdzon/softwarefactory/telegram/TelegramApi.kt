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
