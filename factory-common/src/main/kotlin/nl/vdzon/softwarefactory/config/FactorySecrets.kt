package nl.vdzon.softwarefactory.config

class FactorySecrets(
    val youTrackBaseUrl: String,
    val youTrackToken: String,
    val youTrackProjects: List<String>,
    val githubToken: String,
    val factoryDatabaseUrl: String,
    val factoryDatabaseSchema: String,
    val kubeconfig: String?,
    val aiCredentialsDir: String?,
    val aiOauthToken: String?,
    val codexCredentialsDir: String? = null,
    val loadedFrom: String,
    // Publieke YouTrack-URL voor links in de UI (bv. via Cloudflare). Valt terug op youTrackBaseUrl
    // wanneer niet gezet. De API-calls blijven altijd youTrackBaseUrl gebruiken.
    val youTrackPublicUrl: String = youTrackBaseUrl,
    // Telegram-integratie (optioneel). Beide leeg => uitgeschakeld: geen meldingen, geen poller.
    val telegramBotToken: String? = null,
    val telegramChatId: String? = null,
    // Publieke basis-URL van het dashboard voor klikbare links in meldingen. Leeg => val terug
    // op de YouTrack-issuelink.
    val dashboardBaseUrl: String? = null,
    // Bridge naar dashboard-backend(s) (zie docs/ontwerp-bridge-dashboard.md): komma-gescheiden
    // lijst van websocket-URL's (leeg = feature uit) + het gedeelde token uit de hello-frame.
    val bridgeUrls: List<String> = emptyList(),
    val bridgeToken: String? = null,
    // Welke YouTrackApi-implementatie actief is: "youtrack" (default, huidig gedrag) of
    // "postgres" (eigen tracker-tabellen in de factory-database, vervangt YouTrack). Omkeerbare
    // schakelaar voor de YouTrack-uitfasering — zie youtrack/clients/TrackerClientConfiguration.kt.
    val trackerBackend: String = "youtrack",
    // Alleen relevant als trackerBackend = "postgres": map op de laptop-schijf waar
    // issue-attachments (tester-screenshots) als losse bestanden komen te staan.
    val trackerAttachmentsDir: String = "attachments",
) {
    /** Telegram is actief zodra zowel een bot-token als een chat-id is geconfigureerd. */
    val telegramEnabled: Boolean
        get() = !telegramBotToken.isNullOrBlank() && !telegramChatId.isNullOrBlank()

    fun redactedSummary(): Map<String, String> = mapOf(
        "loadedFrom" to loadedFrom,
        "youTrackBaseUrl" to youTrackBaseUrl,
        "youTrackPublicUrl" to youTrackPublicUrl,
        "youTrackToken" to "<redacted>",
        "youTrackProjects" to youTrackProjects.joinToString(",").ifBlank { "<auto-discover>" },
        "githubToken" to "<redacted>",
        "factoryDatabaseUrl" to redactDatabaseUrl(factoryDatabaseUrl),
        "factoryDatabaseSchema" to factoryDatabaseSchema,
        "kubeconfig" to (kubeconfig ?: "<not set>"),
        "aiCredentialsDir" to (aiCredentialsDir ?: "<not set>"),
        "aiOauthToken" to if (aiOauthToken.isNullOrBlank()) "<not set>" else "<redacted>",
        "codexCredentialsDir" to (codexCredentialsDir ?: "<not set>"),
        "telegramBotToken" to if (telegramBotToken.isNullOrBlank()) "<not set>" else "<redacted>",
        "telegramChatId" to (telegramChatId?.takeIf { it.isNotBlank() } ?: "<not set>"),
        "dashboardBaseUrl" to (dashboardBaseUrl?.takeIf { it.isNotBlank() } ?: "<not set>"),
        "bridgeUrls" to bridgeUrls.joinToString(",").ifBlank { "<not set>" },
        "bridgeToken" to if (bridgeToken.isNullOrBlank()) "<not set>" else "<redacted>",
        "trackerBackend" to trackerBackend,
        "trackerAttachmentsDir" to trackerAttachmentsDir,
    )

    override fun toString(): String = "FactorySecrets(${redactedSummary()})"

    private fun redactDatabaseUrl(value: String): String =
        value.replace(Regex("(jdbc:)?postgresql://[^\\s,}]+"), "postgresql://<redacted>")

    companion object {
        val REQUIRED_KEYS: List<String> = listOf(
            "SF_YOUTRACK_BASE_URL",
            "SF_YOUTRACK_TOKEN",
            "SF_GITHUB_TOKEN",
            "SF_DATABASE_URL",
            "SF_DATABASE_SCHEMA",
        )
    }
}
