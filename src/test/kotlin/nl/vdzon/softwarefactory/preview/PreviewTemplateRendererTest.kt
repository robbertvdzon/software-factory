package nl.vdzon.softwarefactory.preview

import nl.vdzon.softwarefactory.preview.services.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PreviewTemplateRendererTest {
    @Test
    fun `renders PR number into preview templates`() {
        assertEquals(
            "https://app-pr-42.example.com",
            PreviewTemplateRenderer.render("https://app-pr-{pr_num}.example.com", 42),
        )
        assertEquals("app-pr-42", PreviewTemplateRenderer.render("app-pr-{pr_num}", 42))
    }

    @Test
    fun `returns null for blank templates or missing PR number`() {
        assertNull(PreviewTemplateRenderer.render("", 42))
        assertNull(PreviewTemplateRenderer.render("app-pr-{pr_num}", null))
        assertNull(PreviewTemplateRenderer.render("app-pr-{pr_num}", 0))
    }
}
