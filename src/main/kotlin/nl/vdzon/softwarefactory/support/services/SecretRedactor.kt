package nl.vdzon.softwarefactory.support.services

object SecretRedactor {
    private val postgresUrlPattern = Regex("(jdbc:)?postgresql://[^\\s,}\"']+")
    private val anthropicTokenPattern = Regex("sk-ant-(api03|oat01)-[A-Za-z0-9_-]+")
    private val githubClassicTokenPattern = Regex("ghp_[A-Za-z0-9]{20,}")
    private val githubFineGrainedTokenPattern = Regex("github_pat_[A-Za-z0-9_]+")
    private val jwtPattern = Regex("eyJ[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    private val keyValueSecretPattern = Regex(
        """(?i)(SF_[A-Z0-9_]*(TOKEN|KEY|SECRET|PASSWORD|DATABASE_URL)[A-Z0-9_]*=)([^\s,"'}]+)""",
    )

    fun redact(value: String): String =
        value.replace(postgresUrlPattern, "postgresql://<redacted>")
            .replace(anthropicTokenPattern, "<redacted-anthropic-token>")
            .replace(githubClassicTokenPattern, "<redacted-github-token>")
            .replace(githubFineGrainedTokenPattern, "<redacted-github-token>")
            .replace(jwtPattern, "<redacted-jwt>")
            .replace(keyValueSecretPattern, "$1<redacted>")
}
