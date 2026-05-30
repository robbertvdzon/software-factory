package nl.vdzon.softwarefactory.config.services

import nl.vdzon.softwarefactory.config.FactorySecrets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.name
import kotlin.io.path.readLines

class SecretsEnvLoader(
    private val secretsFile: Path = defaultSecretsFile(),
    private val environment: Map<String, String> = System.getenv(),
) {
    fun resolvedValues(): Map<String, String> {
        val fileValues = if (Files.exists(secretsFile)) parseSecretsFile() else emptyMap()
        return environment + fileValues.filterValues { it.isNotBlank() }
    }

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
            youTrackBaseUrl = resolveRequired("SF_YOUTRACK_BASE_URL", fileValues),
            youTrackToken = resolveRequired("SF_YOUTRACK_TOKEN", fileValues),
            youTrackProjects = resolveProjects(resolveOptional("SF_YOUTRACK_PROJECTS", fileValues)),
            githubToken = resolveRequired("SF_GITHUB_TOKEN", fileValues),
            factoryDatabaseUrl = resolveRequired("SF_DATABASE_URL", fileValues),
            factoryDatabaseSchema = resolveDatabaseSchema(resolveRequired("SF_DATABASE_SCHEMA", fileValues)),
            kubeconfig = resolveOptional("SF_KUBECONFIG", fileValues),
            aiCredentialsDir = resolveOptional("SF_AI_CREDENTIALS_DIR", fileValues),
            aiOauthToken = resolveOptional("SF_AI_OAUTH_TOKEN", fileValues),
            copilotCredentialsDir = resolveOptional("SF_COPILOT_CREDENTIALS_DIR", fileValues),
            loadedFrom = loadedFromDescription(fileValues),
            autoSyncAfterAgent = resolveBoolean("SF_AUTO_SYNC_AFTER_AGENT", fileValues, default = true),
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

    private fun resolveDatabaseSchema(value: String): String {
        require(DATABASE_SCHEMA_PATTERN.matches(value)) {
            "SF_DATABASE_SCHEMA must be a valid Postgres identifier."
        }
        require(value != RESERVED_FACTORY_SCHEMA) {
            "SF_DATABASE_SCHEMA must not be '$RESERVED_FACTORY_SCHEMA'; that schema belongs to another system."
        }
        return value
    }

    private fun resolveBoolean(key: String, fileValues: Map<String, String>, default: Boolean): Boolean {
        val value = resolve(key, fileValues) ?: return default
        return value.toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("$key must be either 'true' or 'false'.")
    }

    private fun resolveProjects(value: String?): List<String> =
        value.orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { project ->
                require(PROJECT_KEY_PATTERN.matches(project)) {
                    "SF_YOUTRACK_PROJECTS contains invalid project key '$project'."
                }
                project
            }

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
        private val DATABASE_SCHEMA_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val PROJECT_KEY_PATTERN = Regex("[A-Za-z][A-Za-z0-9_\\-]*")
        private const val RESERVED_FACTORY_SCHEMA = "factory"

        fun defaultSecretsFile(
            environment: Map<String, String> = System.getenv(),
            workingDirectory: Path = Path("").toAbsolutePath().normalize(),
        ): Path {
            environment["SF_SECRETS_FILE"]?.takeIf { it.isNotBlank() }?.let { return Path(it) }

            val candidates = listOf(
                workingDirectory.resolve("secrets.env"),
                workingDirectory.resolve("../secrets.env").normalize(),
            )
            return candidates.firstOrNull { Files.exists(it) } ?: candidates.first()
        }
    }
}
