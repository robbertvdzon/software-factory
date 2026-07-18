package nl.vdzon.softwarefactory.agentworker.verification

import nl.vdzon.softwarefactory.contract.AgentResultVerificationCommand
import nl.vdzon.softwarefactory.contract.AgentResultVerificationEvidence
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.support.SupportApi
import nl.vdzon.softwarefactory.verification.VerificationCommand
import nl.vdzon.softwarefactory.verification.VerificationConfigParser
import nl.vdzon.softwarefactory.verification.CheckoutIdentity
import nl.vdzon.softwarefactory.verification.CheckoutIdentityResolver
import java.io.IOException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class VerificationProcessResult(
    val status: String,
    val exitCode: Int?,
    val output: String,
)

fun interface VerificationProcessRunner {
    fun run(argv: List<String>, cwd: Path, timeoutSeconds: Long): VerificationProcessResult
}

class LocalVerificationProcessRunner : VerificationProcessRunner {
    override fun run(argv: List<String>, cwd: Path, timeoutSeconds: Long): VerificationProcessResult {
        if (!executableExists(argv.first(), cwd)) {
            return VerificationProcessResult("tool-missing", null, "Executable not found: ${argv.first()}")
        }
        val process = try {
            ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true).start()
        } catch (exception: IOException) {
            val status = if (exception.message.orEmpty().contains("No such file", ignoreCase = true)) "tool-missing" else "execution-error"
            return VerificationProcessResult(status, null, exception.message.orEmpty())
        }
        val output = StringBuffer()
        val reader = Executors.newSingleThreadExecutor()
        val readFuture = reader.submit {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (output.length < MAX_SUMMARY_CHARS) {
                        if (output.isNotEmpty()) output.append('\n')
                        output.append(line.take(MAX_SUMMARY_CHARS - output.length))
                    }
                }
            }
        }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        val outputRead = runCatching { readFuture.get(5, TimeUnit.SECONDS) }.isSuccess
        reader.shutdownNow()
        return if (!finished) {
            VerificationProcessResult("timeout", null, "Command timed out after ${timeoutSeconds}s\n$output")
        } else if (!outputRead) {
            VerificationProcessResult("execution-error", null, "Command output could not be read safely.\n$output")
        } else {
            val exitCode = process.exitValue()
            VerificationProcessResult(if (exitCode == 0) "passed" else "failed", exitCode, output.toString())
        }
    }

    private fun executableExists(executable: String, cwd: Path): Boolean {
        val direct = Path.of(executable)
        if (direct.isAbsolute || executable.contains('/') || executable.contains('\\')) {
            val resolved = if (direct.isAbsolute) direct else cwd.resolve(direct).normalize()
            return Files.isRegularFile(resolved) && Files.isExecutable(resolved)
        }
        return System.getenv("PATH").orEmpty()
            .split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .map(Path::of)
            .map { it.resolve(executable) }
            .any { Files.isRegularFile(it) && Files.isExecutable(it) }
    }

    private companion object {
        const val MAX_SUMMARY_CHARS = 4000
    }
}

data class TesterVerificationResult(
    val evidence: AgentResultVerificationEvidence?,
    val accepted: Boolean,
    val diagnosis: String,
)

/** Voert na de tester-AI-run zelf alle geconfigureerde argv-commands uit. */
class TesterVerificationRunner(
    private val processRunner: VerificationProcessRunner = LocalVerificationProcessRunner(),
    private val clock: Clock = Clock.systemUTC(),
    private val identityProvider: (Path) -> CheckoutIdentity? = CheckoutIdentityResolver()::resolve,
    // null = onbekend (bv. geen baseBranch of git-fout) → nooit skippen, altijd alles draaien.
    // Dat is de veilige kant: een verkeerde gok kost hooguit dezelfde tijd als vandaag, nooit
    // minder zekerheid — de echte CI (verify.yml) draait sowieso onvoorwaardelijk door.
    private val changedPathsProvider: (Path, String) -> Set<String>? = ::gitChangedPaths,
) {
    fun verify(repoRoot: Path, baseBranch: String? = null): TesterVerificationResult {
        val config = runCatching { VerificationConfigParser.parse(repoRoot) }
            .getOrElse { return TesterVerificationResult(null, false, it.message ?: "verification-config ongeldig") }
        val identityBefore = identityProvider(repoRoot)
            ?: return TesterVerificationResult(null, false, "Kon geteste HEAD/worktree-tree niet bepalen")

        // CI-only commands (agentRunnable=false) draaien nooit hier: de agent-container mist er
        // de tooling voor (bv. geen docker-CLI voor een image-build) of het is inherent zwaar
        // infra-werk dat niet per developer/tester-run herhaald hoeft. De echte CI verifieert ze
        // apart en onafhankelijk vóór de merge-gate.
        val runnableCommands = config.commands.filter { it.agentRunnable }
        val changedPaths = baseBranch?.let { changedPathsProvider(repoRoot, it) }
        val evidence = runnableCommands.map { command ->
            if (isOutOfScope(command, changedPaths)) {
                return@map skippedEvidence(command)
            }
            val started = Instant.now(clock)
            val cwd = repoRoot.resolve(command.workingDirectory).normalize()
            val result = processRunner.run(command.argv, cwd, command.timeoutSeconds)
            val ended = Instant.now(clock)
            AgentResultVerificationCommand(
                commandId = command.id,
                startedAt = started.toString(),
                endedAt = ended.toString(),
                durationMs = (ended.toEpochMilli() - started.toEpochMilli()).coerceAtLeast(0),
                exitCode = result.exitCode,
                status = result.status,
                summary = SupportApi.default().redact(result.output)
                    .take(4000)
                    .ifBlank { "Command completed without output." },
            )
        }.toMutableList()

        val identityAfter = identityProvider(repoRoot)
        if (identityAfter != identityBefore) {
            val last = evidence.last()
            evidence[evidence.lastIndex] = last.copy(
                exitCode = null,
                status = "execution-error",
                summary = "Revision veranderde tijdens verificatie: HEAD/tree vóór en na verschillen.",
            )
        }
        val resultEvidence = AgentResultVerificationEvidence(
            config.version,
            identityBefore.headSha,
            identityBefore.treeSha,
            evidence,
        )
        val rejected = evidence.firstOrNull { it.status != "passed" && it.status != "skipped" || (it.status == "passed" && it.exitCode != 0) }
        return if (rejected == null) {
            TesterVerificationResult(resultEvidence, true, "Alle ${evidence.size} verplichte verification-command(s) groen")
        } else {
            TesterVerificationResult(
                resultEvidence,
                false,
                "Verification-command ${rejected.commandId} afgewezen: status=${rejected.status}, exitCode=${rejected.exitCode ?: "n.v.t."}",
            )
        }
    }

    private fun isOutOfScope(command: VerificationCommand, changedPaths: Set<String>?): Boolean {
        if (command.pathPrefixes.isEmpty() || changedPaths == null) {
            return false
        }
        return changedPaths.none { path -> command.pathPrefixes.any { prefix -> path.startsWith(prefix) } }
    }

    private fun skippedEvidence(command: VerificationCommand): AgentResultVerificationCommand {
        val now = Instant.now(clock).toString()
        return AgentResultVerificationCommand(
            commandId = command.id,
            startedAt = now,
            endedAt = now,
            durationMs = 0,
            exitCode = null,
            status = "skipped",
            summary = "Buiten scope van deze story-diff (geen pad onder ${command.pathPrefixes.joinToString()}); niet gedraaid.",
        )
    }
}

/** `git diff --name-only` tegen de gefetchte base-branch; null bij elke onzekerheid (geen ref, git-fout). */
private fun gitChangedPaths(repoRoot: Path, baseBranch: String): Set<String>? {
    val result = GitApi.default().runCommand(
        listOf("git", "diff", "--name-only", "origin/$baseBranch...HEAD"),
        cwd = repoRoot,
        timeoutSeconds = 30,
    )
    if (result.exitCode != 0) {
        return null
    }
    return result.stdout.lineSequence().map(String::trim).filter(String::isNotBlank).toSet()
}
