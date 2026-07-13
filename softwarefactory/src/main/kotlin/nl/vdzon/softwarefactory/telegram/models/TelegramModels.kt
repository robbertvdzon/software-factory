package nl.vdzon.softwarefactory.telegram.models

data class AssistantTip(val category: String, val key: String, val content: String)

data class AssistantReply(
    val text: String,
    val isError: Boolean,
    val sessionId: String?,
    val costUsd: Double,
    val stopped: Boolean = false,
    val tips: List<AssistantTip> = emptyList(),
)

data class TelegramUpdate(
    val updateId: Long,
    val chatId: String?,
    val text: String?,
    val messageId: Long?,
    val replyToMessageId: Long?,
    val photoFileId: String? = null,
)
