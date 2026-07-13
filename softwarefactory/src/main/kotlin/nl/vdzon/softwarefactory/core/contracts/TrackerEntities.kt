package nl.vdzon.softwarefactory.core.contracts

import nl.vdzon.softwarefactory.core.AgentComments
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.DeploymentConfig
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.*

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
