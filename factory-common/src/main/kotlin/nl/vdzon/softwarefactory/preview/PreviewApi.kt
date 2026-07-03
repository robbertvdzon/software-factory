package nl.vdzon.softwarefactory.preview

import nl.vdzon.softwarefactory.preview.services.PreviewTemplateRenderer

/**
 * Public API of the preview module.
 *
 * The preview module owns preview URL/namespace rendering and cleanup of
 * deployed preview environments after a story is merged or cancelled.
 */
interface PreviewApi {
    fun render(template: String?, prNumber: Int?): String?

    fun cleanup(namespace: String): Boolean

    companion object {
        fun renderTemplate(template: String?, prNumber: Int?): String? =
            PreviewTemplateRenderer.render(template, prNumber)
    }
}
