package nl.vdzon.softwarefactory.config

class FactorySecrets(
    // Projectkeys die de tracker (Postgres) scant; leeg = alle gevonden project_key's uit de issues-tabel.
    val trackerProjects: List<String>,
    val githubToken: String,
    val factoryDatabaseUrl: String,
    val factoryDatabaseSchema: String,
    val kubeconfig: String?,
    val aiCredentialsDir: String?,
    val aiOauthToken: String?,
    val codexCredentialsDir: String? = null,
    val loadedFrom: String,
    // Telegram-integratie (optioneel). Beide leeg => uitgeschakeld: geen meldingen, geen poller.
    val telegramBotToken: String? = null,
    val telegramChatId: String? = null,
    // Publieke basis-URL van het dashboard voor klikbare links in meldingen.
    val dashboardBaseUrl: String? = null,
    // Bridge naar dashboard-backend(s) (zie docs/ontwerp-bridge-dashboard.md): komma-gescheiden
    // lijst van websocket-URL's (leeg = feature uit) + het gedeelde token uit de hello-frame.
    val bridgeUrls: List<String> = emptyList(),
    val bridgeToken: String? = null,
    // Map op de laptop-schijf waar issue-attachments (tester-screenshots) als losse bestanden
    // komen te staan.
    val trackerAttachmentsDir: String = "attachments",
) {
    /** Telegram is actief zodra zowel een bot-token als een chat-id is geconfigureerd. */
    val telegramEnabled: Boolean
        get() = !telegramBotToken.isNullOrBlank() && !telegramChatId.isNullOrBlank()

    fun redactedSummary(): Map<String, String> = mapOf(
        "loadedFrom" to loadedFrom,
        "trackerProjects" to trackerProjects.joinToString(",").ifBlank { "<auto-discover>" },
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
        "trackerAttachmentsDir" to trackerAttachmentsDir,
    )

    override fun toString(): String = "FactorySecrets(${redactedSummary()})"

    private fun redactDatabaseUrl(value: String): String =
        value.replace(Regex("(jdbc:)?postgresql://[^\\s,}]+"), "postgresql://<redacted>")

    companion object {
        val REQUIRED_KEYS: List<String> = listOf(
            "SF_GITHUB_TOKEN",
            "SF_DATABASE_URL",
            "SF_DATABASE_SCHEMA",
        )
    }
}
