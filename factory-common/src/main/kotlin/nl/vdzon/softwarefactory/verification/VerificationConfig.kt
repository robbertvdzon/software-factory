package nl.vdzon.softwarefactory.verification

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path

data class VerificationConfig(
    val version: Int,
    val commands: List<VerificationCommand>,
)

data class VerificationCommand(
    val id: String,
    val argv: List<String>,
    val workingDirectory: String,
    val timeoutSeconds: Long,
)

class VerificationConfigException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** Fail-closed parser voor de target-repoconfig; alleen schema-versie 1 wordt ondersteund. */
object VerificationConfigParser {
    const val CONFIG_PATH = ".factory/verification.yaml"
    const val CURRENT_VERSION = 1
    private const val MAX_COMMANDS = 32
    private const val MAX_ARGV_ITEMS = 128
    private const val MAX_TIMEOUT_SECONDS = 7200L
    private val commandIdPattern = Regex("^[a-z0-9][a-z0-9-]{0,63}$")

    fun parse(repoRoot: Path): VerificationConfig = parseFile(repoRoot.resolve(CONFIG_PATH), repoRoot)

    fun parseFile(path: Path, repoRoot: Path = path.parent?.parent ?: Path.of(".")): VerificationConfig {
        if (!Files.isRegularFile(path)) {
            throw VerificationConfigException("Verplichte verification-config ontbreekt: $CONFIG_PATH")
        }
        val raw = try {
            Files.newBufferedReader(path).use { Yaml(SafeConstructor(LoaderOptions())).load<Any?>(it) }
        } catch (exception: Exception) {
            throw VerificationConfigException("Ongeldige YAML in $CONFIG_PATH: ${exception.message}", exception)
        }
        val root = raw as? Map<*, *>
            ?: throw VerificationConfigException("$CONFIG_PATH moet een YAML-object zijn")
        val version = (root["version"] as? Number)?.toInt()
            ?: throw VerificationConfigException("$CONFIG_PATH mist numerieke version")
        if (version != CURRENT_VERSION) {
            throw VerificationConfigException("Onbekende verification-configversie $version; ondersteund: $CURRENT_VERSION")
        }
        val commandNodes = root["commands"] as? List<*>
            ?: throw VerificationConfigException("$CONFIG_PATH mist commands-lijst")
        if (commandNodes.isEmpty() || commandNodes.size > MAX_COMMANDS) {
            throw VerificationConfigException("commands moet 1..$MAX_COMMANDS items bevatten")
        }
        val commands = commandNodes.mapIndexed { index, node -> parseCommand(index, node, repoRoot) }
        val duplicates = commands.groupingBy(VerificationCommand::id).eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw VerificationConfigException("Dubbele verification command-id(s): ${duplicates.sorted().joinToString()}")
        }
        return VerificationConfig(version, commands)
    }

    private fun parseCommand(index: Int, raw: Any?, repoRoot: Path): VerificationCommand {
        val node = raw as? Map<*, *>
            ?: throw VerificationConfigException("commands[$index] moet een object zijn")
        val id = (node["id"] as? String)?.trim().orEmpty()
        if (!commandIdPattern.matches(id)) {
            throw VerificationConfigException("commands[$index].id is ongeldig: '$id'")
        }
        val argv = (node["argv"] as? List<*>)?.map { it as? String }
            ?: throw VerificationConfigException("commands[$index].argv moet een lijst strings zijn")
        if (argv.isEmpty() || argv.size > MAX_ARGV_ITEMS || argv.any { it.isNullOrBlank() }) {
            throw VerificationConfigException("commands[$index].argv moet 1..$MAX_ARGV_ITEMS niet-lege strings bevatten")
        }
        val workingDirectory = (node["workingDirectory"] as? String)?.trim().orEmpty()
        validateWorkingDirectory(index, workingDirectory, repoRoot)
        val timeout = (node["timeoutSeconds"] as? Number)?.toLong()
            ?: throw VerificationConfigException("commands[$index].timeoutSeconds moet numeriek zijn")
        if (timeout !in 1..MAX_TIMEOUT_SECONDS) {
            throw VerificationConfigException("commands[$index].timeoutSeconds moet 1..$MAX_TIMEOUT_SECONDS zijn")
        }
        return VerificationCommand(id, argv.filterNotNull(), workingDirectory, timeout)
    }

    private fun validateWorkingDirectory(index: Int, value: String, repoRoot: Path) {
        if (value.isBlank() || Path.of(value).isAbsolute) {
            throw VerificationConfigException("commands[$index].workingDirectory moet een relatief pad zijn")
        }
        val normalizedRoot = repoRoot.toAbsolutePath().normalize()
        val resolved = normalizedRoot.resolve(value).normalize()
        if (!resolved.startsWith(normalizedRoot) || !Files.isDirectory(resolved)) {
            throw VerificationConfigException("commands[$index].workingDirectory bestaat niet binnen de repository: '$value'")
        }
        val realRoot = normalizedRoot.toRealPath()
        val realResolved = resolved.toRealPath()
        if (!realResolved.startsWith(realRoot)) {
            throw VerificationConfigException("commands[$index].workingDirectory volgt een symlink buiten de repository: '$value'")
        }
    }
}

/** Kleine CI-/rolloutvalidator; exit non-zero via exception bij de eerste ongeldige repository. */
object VerificationConfigValidatorCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Gebruik: VerificationConfigValidatorCli <repo-root> [...]" }
        args.forEach { rawRoot ->
            val root = Path.of(rawRoot).toAbsolutePath().normalize()
            val config = VerificationConfigParser.parse(root)
            println("valid repo=$root version=${config.version} commands=${config.commands.joinToString { it.id }}")
        }
    }
}
