package nl.vdzon.softwarefactory.config.services

import nl.vdzon.softwarefactory.config.FactorySecrets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.name
import kotlin.io.path.readLines

/**
 * Loads factory configuration from a layered set of `.env` files plus the process environment.
 *
 * Three files are read (all optional, lowest precedence first):
 *  1. **properties.default.env** — committed; documents every tuning property with its default value.
 *  2. **properties.env**         — gitignored; local overrides of the defaults above.
 *  3. **secrets.env**            — gitignored; the actual secrets (tokens, connection strings, login).
 *
 * On top of the files, **environment variables always win**: if an env var is set (non-blank) it
 * overrides whatever the files say. This lets every secret and property be supplied as an env var,
 * which is the highest priority source.
 */
class SecretsEnvLoader(
    private val secretsFile: Path = defaultSecretsFile(),
    private val propertiesFile: Path = secretsFile.resolveSibling("properties.env"),
    private val propertiesDefaultFile: Path = secretsFile.resolveSibling("properties.default.env"),
    private val environment: Map<String, String> = System.getenv(),
) {
    /** All file layers merged (defaults < properties < secrets), blanks dropped. */
    private val fileValues: Map<String, String> by lazy {
        val merged = LinkedHashMap<String, String>()
        for (file in listOf(propertiesDefaultFile, propertiesFile, secretsFile)) {
            merged.putAll(parseEnvFile(file))
        }
        merged.filterValues { it.isNotBlank() }
    }

    /**
     * The effective configuration handed to the agent containers and the orchestrator settings:
     * every file value plus the full process environment, with environment variables taking
     * precedence over the files.
     */
    fun resolvedValues(): Map<String, String> = fileValues + environment

    fun load(): FactorySecrets {
        val missingKeys = FactorySecrets.REQUIRED_KEYS.filter { resolve(it).isNullOrBlank() }

        if (missingKeys.isNotEmpty()) {
            throw MissingRequiredSecretsException(
                "Missing required factory configuration: ${missingKeys.joinToString(", ")}. " +
                    "Set them in $secretsFile or as system environment variables.",
            )
        }

        return FactorySecrets(
            youTrackBaseUrl = resolveRequired("SF_YOUTRACK_BASE_URL"),
            youTrackPublicUrl = resolveOptional("SF_YOUTRACK_PUBLIC_URL")?.takeIf { it.isNotBlank() }
                ?: resolveRequired("SF_YOUTRACK_BASE_URL"),
            youTrackToken = resolveRequired("SF_YOUTRACK_TOKEN"),
            youTrackProjects = resolveProjects(resolveOptional("SF_YOUTRACK_PROJECTS")),
            githubToken = resolveRequired("SF_GITHUB_TOKEN"),
            factoryDatabaseUrl = resolveRequired("SF_DATABASE_URL"),
            factoryDatabaseSchema = resolveDatabaseSchema(resolveRequired("SF_DATABASE_SCHEMA")),
            kubeconfig = resolveOptional("SF_KUBECONFIG"),
            aiCredentialsDir = resolveOptional("SF_AI_CREDENTIALS_DIR"),
            aiOauthToken = resolveOptional("SF_AI_OAUTH_TOKEN"),
            codexCredentialsDir = resolveOptional("SF_CODEX_CREDENTIALS_DIR"),
            telegramBotToken = resolveOptional("SF_TELEGRAM_BOT_TOKEN"),
            telegramChatId = resolveOptional("SF_TELEGRAM_CHAT_ID"),
            dashboardBaseUrl = resolveOptional("SF_DASHBOARD_BASE_URL"),
            loadedFrom = loadedFromDescription(),
        )
    }

    private fun parseEnvFile(file: Path): Map<String, String> =
        if (Files.exists(file)) {
            file.readLines().mapIndexedNotNull { index, line -> parseLine(file, index + 1, line) }.toMap()
        } else {
            emptyMap()
        }

    private fun parseLine(file: Path, lineNumber: Int, rawLine: String): Pair<String, String>? {
        val line = rawLine.trim()
        if (line.isBlank() || line.startsWith("#")) {
            return null
        }

        val normalizedLine = line.removePrefix("export ").trim()
        val separatorIndex = normalizedLine.indexOf('=')
        require(separatorIndex > 0) {
            "Invalid ${file.name} line $lineNumber: expected KEY=value."
        }

        val key = normalizedLine.substring(0, separatorIndex).trim()
        require(KEY_PATTERN.matches(key)) {
            "Invalid ${file.name} line $lineNumber: '$key' is not a valid environment key."
        }

        val value = normalizedLine.substring(separatorIndex + 1).trim().stripSurroundingQuotes()
        return key to value
    }

    private fun resolveRequired(key: String): String =
        requireNotNull(resolve(key)) { "Required configuration key '$key' was not resolved." }

    private fun resolveOptional(key: String): String? = resolve(key)

    /** Environment variables win over the files. */
    private fun resolve(key: String): String? =
        environment[key]?.takeIf { it.isNotBlank() }
            ?: fileValues[key]?.takeIf { it.isNotBlank() }

    private fun resolveDatabaseSchema(value: String): String {
        require(DATABASE_SCHEMA_PATTERN.matches(value)) {
            "SF_DATABASE_SCHEMA must be a valid Postgres identifier."
        }
        require(value != RESERVED_FACTORY_SCHEMA) {
            "SF_DATABASE_SCHEMA must not be '$RESERVED_FACTORY_SCHEMA'; that schema belongs to another system."
        }
        return value
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

    private fun loadedFromDescription(): String =
        if (fileValues.isEmpty() && !Files.exists(secretsFile)) {
            "system environment"
        } else {
            "$secretsFile with environment fallback"
        }

    private fun String.stripSurroundingQuotes(): String {
        val doubleQuoted = startsWith("\"") && endsWith("\"")
        val singleQuoted = startsWith("'") && endsWith("'")
        return if (doubleQuoted || singleQuoted) {
            substring(1, length - 1)
        } else {
            this
        }
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
