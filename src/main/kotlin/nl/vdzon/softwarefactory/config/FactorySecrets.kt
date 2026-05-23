package nl.vdzon.softwarefactory.config

class FactorySecrets(
    val jiraBaseUrl: String,
    val jiraEmail: String,
    val jiraApiKey: String,
    val githubToken: String,
    val factoryDatabaseUrl: String,
    val kubeconfig: String?,
    val aiCredentialsDir: String?,
    val aiOauthToken: String?,
    val loadedFrom: String,
) {
    fun redactedSummary(): Map<String, String> = mapOf(
        "loadedFrom" to loadedFrom,
        "jiraBaseUrl" to jiraBaseUrl,
        "jiraEmail" to jiraEmail,
        "jiraApiKey" to "<redacted>",
        "githubToken" to "<redacted>",
        "factoryDatabaseUrl" to redactDatabaseUrl(factoryDatabaseUrl),
        "kubeconfig" to (kubeconfig ?: "<not set>"),
        "aiCredentialsDir" to (aiCredentialsDir ?: "<not set>"),
        "aiOauthToken" to if (aiOauthToken.isNullOrBlank()) "<not set>" else "<redacted>",
    )

    override fun toString(): String = "FactorySecrets(${redactedSummary()})"

    private fun redactDatabaseUrl(value: String): String =
        value.replace(Regex("postgresql://([^:/@]+):([^@]+)@"), "postgresql://<redacted>:<redacted>@")

    companion object {
        val REQUIRED_KEYS: List<String> = listOf(
            "JIRA_BASE_URL",
            "JIRA_EMAIL",
            "JIRA_API_KEY",
            "GITHUB_TOKEN",
            "FACTORY_DATABASE_URL",
        )
    }
}
