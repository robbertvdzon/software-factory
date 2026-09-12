package nl.vdzon.softwarefactory.web.services

import nl.vdzon.softwarefactory.dashboard.DashboardQueries
import nl.vdzon.softwarefactory.tracker.AttachmentPort
import nl.vdzon.softwarefactory.web.controllers.AttachmentConflictException
import nl.vdzon.softwarefactory.web.controllers.DashboardNotFoundException
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.HexFormat

/** Uitkomst van een idempotente attachment-upload: [created] is false als het bestand er al identiek stond. */
data class StoredStoryAttachment(
    val id: String,
    val name: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
    val created: Boolean,
)

/**
 * Idempotente opslag van storybijlagen voor de Product Factory-integratie: dezelfde naam met
 * dezelfde inhoud is een no-op, dezelfde naam met andere inhoud een conflict. Zo kan Product
 * Factory een aanmaakverzoek veilig herhalen na een time-out.
 */
@Service
class StoryAttachmentStore(
    private val dashboard: DashboardQueries,
    private val attachments: AttachmentPort,
) {
    fun put(storyKey: String, name: String, mediaType: String, expectedSha: String, bytes: ByteArray): StoredStoryAttachment {
        val normalizedMediaType = mediaType.lowercase()
        val normalizedSha = expectedSha.lowercase()
        validateMetadata(name, normalizedMediaType, normalizedSha)
        require(sha256(bytes) == normalizedSha) { "Attachment $name heeft niet de opgegeven SHA-256." }
        if (dashboard.storyDetail(storyKey).issue == null) {
            throw DashboardNotFoundException("Story $storyKey niet gevonden.")
        }
        return existing(storyKey, name, normalizedMediaType, normalizedSha, bytes)
            ?: upload(storyKey, name, normalizedMediaType, normalizedSha, bytes)
    }

    private fun validateMetadata(name: String, mediaType: String, expectedSha: String) {
        val invalidName = name.isBlank() || name.contains("..") ||
            name.contains('/') || name.contains('\\') || name.any(Char::isISOControl)
        require(!invalidName) { "Ongeldige attachmentnaam." }
        require(mediaType.isNotBlank()) { "Attachment-MIME-type is verplicht." }
        require(expectedSha.matches(SHA_256)) { "Ongeldige attachment-SHA-256." }
    }

    private fun existing(storyKey: String, name: String, mediaType: String, expectedSha: String, bytes: ByteArray): StoredStoryAttachment? {
        val attachment = attachments.listIssueAttachments(storyKey).firstOrNull { it.name == name } ?: return null
        val existingBytes = attachments.downloadAttachmentBytes(attachment)
            ?: throw AttachmentConflictException("Bestaand attachment $name kan niet worden gecontroleerd.")
        if (sha256(existingBytes) != expectedSha) {
            throw AttachmentConflictException("Attachment $name bestaat al met andere inhoud.")
        }
        return StoredStoryAttachment(
            attachment.id,
            attachment.name,
            attachment.mimeType ?: mediaType,
            attachment.size ?: bytes.size.toLong(),
            expectedSha,
            created = false,
        )
    }

    private fun upload(storyKey: String, name: String, mediaType: String, expectedSha: String, bytes: ByteArray): StoredStoryAttachment {
        val uploaded = attachments.uploadIssueAttachment(storyKey, name, mediaType, bytes)
        return StoredStoryAttachment(
            uploaded.id,
            uploaded.name,
            uploaded.mimeType ?: mediaType,
            uploaded.size ?: bytes.size.toLong(),
            expectedSha,
            created = true,
        )
    }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private companion object {
        val SHA_256 = Regex("[a-f0-9]{64}")
    }
}
