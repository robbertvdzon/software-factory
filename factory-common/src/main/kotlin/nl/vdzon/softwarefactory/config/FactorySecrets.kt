package nl.vdzon.softwarefactory.config

class FactorySecrets(
    // Projectkeys die de tracker (Postgres) scant; leeg = alle gevonden project_key's uit de issues-tabel.
    val trackerProjects: List<String>,
    val githubToken: String,
    val factoryDatabaseUrl: String,
    val factoryDatabaseSchema: String,
    val kubeconfig: String?,
    // Apart, minimaal gescopeerd kubeconfig (alleen get/list/delete op namespaces/projects) voor
    // PreviewEnvironmentCleaner — bewust los van [kubeconfig] hierboven, dat ook voor reguliere
    // preview- en deploymentstatus wordt gebruikt. Valt terug op [kubeconfig] als niet gezet, zodat een
    // omgeving zonder deze apart-gescopeerde identity gewoon blijft werken (met het oude gedrag).
    val previewCleanupKubeconfig: String? = null,
    // Apart, minimaal gescopeerd PAT (read:packages + delete:packages) voor MaintenanceCleanupScheduler
    // (nachtelijke ghcr.io-package-cleanup) — bewust los van [githubToken], waarmee de factory
    // branches, PR's en merges beheert en dat dus niet ook delete:packages-rechten hoort te
    // dragen. Ontbreekt dit veld, dan slaat de scheduler alleen de package-cleanup over (release-
    // cleanup blijft werken op [githubToken], want dat valt onder de gewone repo-scope).
    val githubPackagesToken: String? = null,
    val loadedFrom: String,
    // Telegram-integratie (optioneel). Beide leeg => uitgeschakeld: geen meldingen, geen poller.
    val telegramBotToken: String? = null,
    val telegramChatId: String? = null,
    // Publieke basis-URL van het dashboard voor klikbare links in meldingen.
    val dashboardBaseUrl: String? = null,
    // Map waar issue-attachments (tester-screenshots, Product Factory-invoer) als losse bestanden
    // komen te staan; op de cluster een PVC-mount.
    val trackerAttachmentsDir: String = "attachments",
    // Dashboard-login via Google-SSO: OAuth-web-client-ID (audience van het ID-token), de
    // allowlist van geverifieerde e-mailadressen (lowercase) en het HMAC-geheim waarmee de
    // uitgegeven sessietokens worden ondertekend. Alle drie leeg => niemand kan inloggen; er
    // wordt nooit een token afgeleid van een leeg geheim.
    val googleClientId: String? = null,
    val allowedEmails: Set<String> = emptySet(),
    val dashboardRememberSecret: String? = null,
    // Apart machine-token voor Product Factory: geeft uitsluitend toegang tot
    // /api/integrations/* en is nadrukkelijk geen dashboardsessie. Leeg => integratie dicht.
    val productFactoryToken: String? = null,
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
        "previewCleanupKubeconfig" to (previewCleanupKubeconfig ?: "<not set, falls back to kubeconfig>"),
        "githubPackagesToken" to if (githubPackagesToken.isNullOrBlank()) "<not set>" else "<redacted>",
        "telegramBotToken" to if (telegramBotToken.isNullOrBlank()) "<not set>" else "<redacted>",
        "telegramChatId" to (telegramChatId?.takeIf { it.isNotBlank() } ?: "<not set>"),
        "dashboardBaseUrl" to (dashboardBaseUrl?.takeIf { it.isNotBlank() } ?: "<not set>"),
        "trackerAttachmentsDir" to trackerAttachmentsDir,
        "googleClientId" to (googleClientId?.takeIf { it.isNotBlank() } ?: "<not set>"),
        "allowedEmails" to allowedEmails.joinToString(",").ifBlank { "<not set>" },
        "dashboardRememberSecret" to if (dashboardRememberSecret.isNullOrBlank()) "<not set>" else "<redacted>",
        "productFactoryToken" to if (productFactoryToken.isNullOrBlank()) "<not set>" else "<redacted>",
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
