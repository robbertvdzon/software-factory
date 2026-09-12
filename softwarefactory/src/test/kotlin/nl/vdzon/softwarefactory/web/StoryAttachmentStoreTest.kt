package nl.vdzon.softwarefactory.web

import nl.vdzon.softwarefactory.core.contracts.TrackerAttachment
import nl.vdzon.softwarefactory.web.DashboardApiFixtures.harness
import nl.vdzon.softwarefactory.web.DashboardApiFixtures.issue
import nl.vdzon.softwarefactory.web.controllers.AttachmentConflictException
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StoryAttachmentStoreTest {
    private fun sha(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    @Test
    fun `uploadt gevalideerde bytes idempotent`() {
        val bytes = byteArrayOf(1, 2, 3)
        val h = harness(issues = listOf(issue("SF-1")))

        val first = h.attachmentStore.put("SF-1", "product-factory-input__ux__ux.png", "image/png", sha(bytes), bytes)
        val retry = h.attachmentStore.put("SF-1", "product-factory-input__ux__ux.png", "image/png", sha(bytes), bytes)

        assertEquals(true, first.created)
        assertEquals(false, retry.created)
        assertEquals(1, h.tracker.listIssueAttachments("SF-1").size)
    }

    @Test
    fun `weigert andere bytes onder dezelfde naam`() {
        val oldBytes = byteArrayOf(1, 2, 3)
        val newBytes = byteArrayOf(3, 2, 1)
        val existing = TrackerAttachment(id = "old", name = "product-factory-input__ux__ux.png", url = null, mimeType = "image/png", size = 3, created = 1L)
        val h = harness(issues = listOf(issue("SF-1")), attachments = listOf(existing), attachmentBytes = mapOf("old" to oldBytes))

        assertFailsWith<AttachmentConflictException> {
            h.attachmentStore.put("SF-1", existing.name, "image/png", sha(newBytes), newBytes)
        }
    }

    @Test
    fun `weigert bytes die niet bij de opgegeven hash horen`() {
        val h = harness(issues = listOf(issue("SF-1")))

        assertFailsWith<IllegalArgumentException> {
            h.attachmentStore.put("SF-1", "product-factory-input__ux__ux.png", "image/png", "0".repeat(64), byteArrayOf(1))
        }
    }
}
