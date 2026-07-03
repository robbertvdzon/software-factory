package nl.vdzon.softwarefactory.web.views.shared

/**
 * Page-chrome voor alle dashboard-pagina's: het `<html>`-skelet, de sidebar-navigatie en de
 * verwijzingen naar de statics (`sf-ui.css`, `dashboard.js`). De losse pagina-views leveren
 * alleen nog hun `main.content`.
 */
internal class HtmlLayout {

    /**
     * Cache-bust voor statics: een korte hash van de actuele bestandsinhoud, als `?v=`-query
     * op de URL. Wijzigt het bestand, dan wijzigt de URL en haalt de browser 'm opnieuw op —
     * geen stale cache. Per pad éénmalig berekend (lazy per proces, net als voorheen).
     */
    private val assetVersions = mutableMapOf<String, String>()

    private fun assetVersion(path: String): String = synchronized(assetVersions) {
        assetVersions.getOrPut(path) {
            runCatching {
                javaClass.getResourceAsStream("/static$path")?.use { input ->
                    Integer.toHexString(input.readBytes().contentHashCode())
                }
            }.getOrNull() ?: "1"
        }
    }

    /** `<link>`-tag voor de gedeelde stylesheet, met cache-bust. */
    val cssLink: String by lazy { """<link rel="stylesheet" href="/sf-ui.css?v=${assetVersion("/sf-ui.css")}">""" }

    /** `<script src>`-tag voor een JS-static (bv. `/stories.js`), met cache-bust. */
    fun scriptTag(path: String): String = """<script src="$path?v=${assetVersion(path)}"></script>"""

    /** SF-icoon als inline SVG; vervangt de standaard browser-favicon. */
    val favicon: String =
        "<link rel=\"icon\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='8' fill='%233f3d56'/%3E%3Ctext x='16' y='22' font-family='Arial,Helvetica,sans-serif' font-size='15' font-weight='bold' fill='%23ffffff' text-anchor='middle'%3ESF%3C/text%3E%3C/svg%3E\">"

    fun layout(
        active: String,
        title: String,
        subtitle: String,
        autoRefreshSeconds: Int? = null,
        eyebrow: String? = null,
        browserTitle: String = title,
        content: () -> String,
    ): String =
        """
        <!doctype html>
        <html lang="nl">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          $favicon
          <title>${browserTitle.e()} - Software Factory</title>
          $cssLink
        </head>
        <body${autoRefreshSeconds?.let { " data-refresh=\"$it\"" } ?: ""}>
          <div class="shell">
            <aside class="sidebar">
              <a class="brand" href="/dashboard"><span class="brand-mark">SF</span>Software Factory</a>
              <nav class="nav">
                ${nav(active, "dashboard", "/dashboard", "Dashboard")}
                ${nav(active, "projects", "/projects", "Projects")}
                ${nav(active, "stories", "/stories", "Stories")}
                ${navMyActions(active)}
                ${nav(active, "nightly", "/nightly", "Nightly")}
                ${nav(active, "agents", "/agents", "Agents")}
                ${nav(active, "merged", "/merged", "Recent merged")}
                ${nav(active, "downloads", "/downloads", "Downloads")}
                ${nav(active, "settings", "/settings", "Settings")}
              </nav>
            </aside>
            <main class="content">
              ${eyebrow?.let { """<div class="eyebrow">$it</div>""" } ?: ""}
              <h1>${title.e()}</h1>
              ${if (subtitle.isNotBlank()) """<p class="page-sub">${subtitle.e()}</p>""" else ""}
              ${content()}
            </main>
          </div>
          ${scriptTag("/dashboard.js")}
        </body>
        </html>
        """.trimIndent()

    private fun nav(active: String, key: String, href: String, label: String): String =
        """<a class="${if (active == key) "active" else ""}" href="$href">$label</a>"""

    /** Nav-item "My actions" met een telbolletje dat door `dashboard.js` live wordt gevuld. */
    private fun navMyActions(active: String): String =
        """<a class="${if (active == "my-actions") "active" else ""}" href="/my-actions">My actions <span class="nav-badge" data-myactions-badge hidden></span></a>"""
}
