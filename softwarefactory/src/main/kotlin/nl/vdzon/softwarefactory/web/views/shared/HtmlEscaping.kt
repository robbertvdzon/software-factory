package nl.vdzon.softwarefactory.web.views.shared

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/*
 * HTML-escaping voor alle views. Voorheen private extensies in FactoryDashboardViews;
 * `internal` zodat elke view-class (en test binnen de module) ze kan gebruiken zonder
 * de escaping per pagina te dupliceren.
 */

/** Escapet tracker-/gebruikerscontent voordat die in HTML wordt geïnterpoleerd (XSS-gate). */
internal fun String.e(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

/** URL-encodeert een pad-segment (issue-keys kunnen spaties/rare tekens bevatten). */
internal fun String.path(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")
