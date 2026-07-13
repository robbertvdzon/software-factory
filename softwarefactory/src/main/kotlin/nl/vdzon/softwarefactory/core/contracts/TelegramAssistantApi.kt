package nl.vdzon.softwarefactory.core.contracts

import java.time.OffsetDateTime

/** Application port implemented by the Telegram adapter and consumed by transport-neutral callers. */
interface TelegramAssistantApi {
    val enabled: Boolean
    fun status(): AssistantStatus
    fun handle(chatId: String, rawText: String, photoFileId: String?, messageId: Long?, replyToMessageId: Long?)
}

data class AssistantStatus(
    val enabled: Boolean,
    val busy: Boolean,
    val activeChatCount: Int,
    val lastActivityAt: OffsetDateTime?,
)
