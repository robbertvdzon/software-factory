package nl.vdzon.softwarefactory.runtime

object SecretRedactor {
    private val postgresUrlPattern = Regex("(jdbc:)?postgresql://[^\\s,}]+")
    private val keyValueSecretPattern = Regex(
        """(?i)(SF_[A-Z0-9_]*(TOKEN|KEY|SECRET|PASSWORD|DATABASE_URL)[A-Z0-9_]*=)([^\s]+)""",
    )

    fun redact(value: String): String =
        value.replace(postgresUrlPattern, "postgresql://<redacted>")
            .replace(keyValueSecretPattern, "$1<redacted>")
}
