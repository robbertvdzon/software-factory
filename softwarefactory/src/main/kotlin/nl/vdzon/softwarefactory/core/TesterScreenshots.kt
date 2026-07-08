package nl.vdzon.softwarefactory.core

/**
 * Naamgevingscontract voor tester-screenshots die als tracker-attachment op de story staan.
 * Gedeeld tussen de upload-kant (AgentRunCompletionService) en de meelees-kanten
 * (Telegram-notificaties, screenshots-pagina) — voorheen twee handgesynchroniseerde kopieën.
 */
object TesterScreenshots {
    /** Bestandsnaam-prefix waaraan een tester-screenshot-attachment te herkennen is. */
    const val ATTACHMENT_PREFIX = "factory-tester-screenshot__"

    /** Toegestane afbeeldingsextensies; al het andere wordt als `.png` behandeld. */
    val EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
}
