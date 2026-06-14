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
    val autoSyncAfterAgent: Boolean = true,
    // Publieke YouTrack-URL voor links in de UI (bv. via Cloudflare). Valt terug op youTrackBaseUrl
    // wanneer niet gezet. De API-calls blijven altijd youTrackBaseUrl gebruiken.
    val youTrackPublicUrl: String = youTrackBaseUrl,
) {
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
        "autoSyncAfterAgent" to autoSyncAfterAgent.toString(),
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
