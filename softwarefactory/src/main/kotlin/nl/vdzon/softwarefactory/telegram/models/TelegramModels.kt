package nl.vdzon.softwarefactory.telegram.models

import java.time.OffsetDateTime

data class AssistantTip(val category: String, val key: String, val content: String)

data class AssistantReply(
    val text: String,
    val isError: Boolean,
    val sessionId: String?,
    val costUsd: Double,
    val stopped: Boolean = false,
    val tips: List<AssistantTip> = emptyList(),
)

data class AssistantStatus(
    val enabled: Boolean,
    val busy: Boolean,
    val activeChatCount: Int,
    val lastActivityAt: OffsetDateTime?,
)

data class TelegramUpdate(
    val updateId: Long,
    val chatId: String?,
    val text: String?,
    val messageId: Long?,
    val replyToMessageId: Long?,
    val photoFileId: String? = null,
)
