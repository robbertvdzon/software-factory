package nl.vdzon.softwarefactory.web.views.pages

import nl.vdzon.softwarefactory.web.views.shared.HtmlLayout

/** Downloads-pagina: placeholder tot de factory artifacts registreert. */
internal class DownloadsView(private val layout: HtmlLayout) {

    fun render(): String =
        layout.layout("downloads", "Downloads", "Build artifacts en APK's") {
            """
            <div class="empty">
              <strong>Nog geen artifact store gekoppeld.</strong>
              De UI-route staat klaar; zodra de factory artifacts registreert kunnen APK's en andere downloads hier verschijnen.
            </div>
            """.trimIndent()
        }
}
