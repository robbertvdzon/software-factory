package nl.vdzon.softwarefactory.web.controllers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SafeRedirectTest {
    @Test
    fun `keeps a local path unchanged`() {
        assertEquals("/stories/SF-1", SafeRedirect.localPath("/stories/SF-1", "/dashboard"))
    }

    @Test
    fun `falls back to the default for null or blank`() {
        assertEquals("/dashboard", SafeRedirect.localPath(null, "/dashboard"))
        assertEquals("/dashboard", SafeRedirect.localPath("", "/dashboard"))
    }

    @Test
    fun `rejects an absolute external url`() {
        assertEquals("/dashboard", SafeRedirect.localPath("https://evil.com", "/dashboard"))
    }

    @Test
    fun `rejects a protocol-relative url`() {
        assertEquals("/dashboard", SafeRedirect.localPath("//evil.com", "/dashboard"))
    }

    @Test
    fun `rejects a backslash protocol-relative bypass`() {
        // Browsers normalise the backslash to a forward slash, turning /\evil.com into //evil.com.
        assertEquals("/dashboard", SafeRedirect.localPath("/\\evil.com", "/dashboard"))
        assertEquals("/dashboard", SafeRedirect.localPath("/\\/evil.com", "/dashboard"))
    }

    @Test
    fun `rejects a value not starting with a slash`() {
        assertEquals("/dashboard", SafeRedirect.localPath("evil.com", "/dashboard"))
    }
}
