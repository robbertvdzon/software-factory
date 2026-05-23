package nl.vdzon.softwarefactory.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.name
import kotlin.io.path.readLines

class SecretsEnvLoader(
    private val secretsFile: Path = defaultSecretsFile(),
    private val environment: Map<String, String> = System.getenv(),
) {
    fun load(): FactorySecrets {
        val fileValues = if (Files.exists(secretsFile)) parseSecretsFile() else emptyMap()
        val missingKeys = FactorySecrets.REQUIRED_KEYS.filter { resolve(it, fileValues).isNullOrBlank() }

        if (missingKeys.isNotEmpty()) {
            throw MissingRequiredSecretsException(
                "Missing required factory configuration: ${missingKeys.joinToString(", ")}. " +
                    "Set them in $secretsFile or as system environment variables.",
            )
        }

        return FactorySecrets(
            jiraBaseUrl = resolveRequired("JIRA_BASE_URL", fileValues),
            jiraEmail = resolveRequired("JIRA_EMAIL", fileValues),
            jiraApiKey = resolveRequired("JIRA_API_KEY", fileValues),
            githubToken = resolveRequired("GITHUB_TOKEN", fileValues),
            factoryDatabaseUrl = resolveRequired("FACTORY_DATABASE_URL", fileValues),
            kubeconfig = resolveOptional("KUBECONFIG", fileValues),
            aiCredentialsDir = resolveOptional("AI_CREDENTIALS_DIR", fileValues),
            aiOauthToken = resolveOptional("AI_OAUTH_TOKEN", fileValues),
            loadedFrom = loadedFromDescription(fileValues),
        )
    }

    private fun parseSecretsFile(): Map<String, String> =
        secretsFile.readLines()
            .mapIndexedNotNull { index, line -> parseLine(index + 1, line) }
            .toMap()

    private fun parseLine(lineNumber: Int, rawLine: String): Pair<String, String>? {
        val line = rawLine.trim()
        if (line.isBlank() || line.startsWith("#")) {
            return null
        }

        val normalizedLine = line.removePrefix("export ").trim()
        val separatorIndex = normalizedLine.indexOf('=')
        if (separatorIndex <= 0) {
            throw IllegalArgumentException(
                "Invalid ${secretsFile.name} line $lineNumber: expected KEY=value.",
            )
        }

        val key = normalizedLine.substring(0, separatorIndex).trim()
        require(KEY_PATTERN.matches(key)) {
            "Invalid ${secretsFile.name} line $lineNumber: '$key' is not a valid environment key."
        }

        val value = normalizedLine.substring(separatorIndex + 1).trim().stripSurroundingQuotes()
        return key to value
    }

    private fun resolveRequired(key: String, fileValues: Map<String, String>): String =
        requireNotNull(resolve(key, fileValues)) { "Required configuration key '$key' was not resolved." }

    private fun resolveOptional(key: String, fileValues: Map<String, String>): String? =
        resolve(key, fileValues)

    private fun resolve(key: String, fileValues: Map<String, String>): String? =
        fileValues[key]?.takeIf { it.isNotBlank() }
            ?: environment[key]?.takeIf { it.isNotBlank() }

    private fun loadedFromDescription(fileValues: Map<String, String>): String =
        if (fileValues.isEmpty() && !Files.exists(secretsFile)) {
            "system environment"
        } else {
            "$secretsFile with environment fallback"
        }

    private fun String.stripSurroundingQuotes(): String =
        if ((startsWith("\"") && endsWith("\"")) || (startsWith("'") && endsWith("'"))) {
            substring(1, length - 1)
        } else {
            this
        }

    companion object {
        private val KEY_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*")

        fun defaultSecretsFile(): Path {
            val override = System.getenv("SOFTWARE_FACTORY_SECRETS_FILE")?.takeIf { it.isNotBlank() }
            return if (override != null) {
                Path(override)
            } else {
                Path("secrets.env")
            }
        }
    }
}
