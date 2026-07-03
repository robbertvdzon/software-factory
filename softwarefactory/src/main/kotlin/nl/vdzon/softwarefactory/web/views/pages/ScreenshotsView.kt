package nl.vdzon.softwarefactory.web.views.pages

import nl.vdzon.softwarefactory.web.models.StoryDetailPageData
import nl.vdzon.softwarefactory.web.views.shared.DetailChrome
import nl.vdzon.softwarefactory.web.views.shared.Formatters
import nl.vdzon.softwarefactory.web.views.shared.alerts
import nl.vdzon.softwarefactory.web.views.shared.e
import nl.vdzon.softwarefactory.web.views.shared.empty

/** Screenshots-pagina van een story: tester-screenshots als kaart-grid. */
internal class ScreenshotsView(
    private val chrome: DetailChrome,
    private val fmt: Formatters,
) {

    fun render(page: StoryDetailPageData): String =
        chrome.detailLayout(page, "Screenshots") {
            alerts(page.errors) +
                chrome.backLink(page.storyKey) +
                if (page.events.isEmpty()) {
                    empty("Nog geen tester-screenshots gevonden.")
                } else {
                    """<section class="screenshot-grid">""" +
                        page.events.joinToString("") { event ->
                            """
                            <article class="screenshot-card">
                              <div class="screenshot-thumb">PNG</div>
                              <strong>${event.kind.e()}</strong>
                              <span class="muted">${fmt.relative(event.ts)} - ${event.role.e()}</span>
                              <pre>${event.payloadText.take(500).e()}</pre>
                            </article>
                            """.trimIndent()
                        } +
                        "</section>"
                }
        }
}
