package nl.vdzon.softwarefactory.agentworker.verification

import nl.vdzon.softwarefactory.contract.AgentResultVerificationCommand
import nl.vdzon.softwarefactory.contract.AgentResultVerificationEvidence
import nl.vdzon.softwarefactory.support.SupportApi
import nl.vdzon.softwarefactory.verification.VerificationConfigParser
import nl.vdzon.softwarefactory.verification.CheckoutIdentity
import nl.vdzon.softwarefactory.verification.CheckoutIdentityResolver
import java.io.IOException
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
        val process = try {
            ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true).start()
        } catch (exception: IOException) {
            val status = if (exception.message.orEmpty().contains("No such file", ignoreCase = true)) "tool-missing" else "execution-error"
            return VerificationProcessResult(status, null, exception.message.orEmpty())
        }
        val output = StringBuilder()
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
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        runCatching { readFuture.get(5, TimeUnit.SECONDS) }
        reader.shutdownNow()
        return if (!finished) {
            VerificationProcessResult("timeout", null, "Command timed out after ${timeoutSeconds}s\n$output")
        } else {
            val exitCode = process.exitValue()
            VerificationProcessResult(if (exitCode == 0) "passed" else "failed", exitCode, output.toString())
        }
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
) {
    fun verify(repoRoot: Path): TesterVerificationResult {
        val config = runCatching { VerificationConfigParser.parse(repoRoot) }
            .getOrElse { return TesterVerificationResult(null, false, it.message ?: "verification-config ongeldig") }
        val identityBefore = identityProvider(repoRoot)
            ?: return TesterVerificationResult(null, false, "Kon geteste HEAD/worktree-tree niet bepalen")

        val evidence = config.commands.map { command ->
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
        val rejected = evidence.firstOrNull { it.status != "passed" || it.exitCode != 0 }
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

}
