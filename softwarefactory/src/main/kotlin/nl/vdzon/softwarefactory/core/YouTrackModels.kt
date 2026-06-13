package nl.vdzon.softwarefactory.core

data class TrackerProject(
    val id: String,
    val key: String,
    val name: String,
)

data class TrackerAttachment(
    val id: String,
    val name: String,
    val url: String?,
    val mimeType: String?,
    val size: Long?,
    val created: Long?,
)

enum class ProcessedCommentMarker {
    TRACKER_COMMENT_MARKER,
    DATABASE_FALLBACK,
}
