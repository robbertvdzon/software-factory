package nl.vdzon.softwarefactory.git.services

import nl.vdzon.softwarefactory.support.CallMetrics
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.concurrent.TimeUnit

interface ProcessRunner {
    fun run(
        command: List<String>,
        cwd: Path? = null,
        env: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 60,
    ): ProcessResult
}

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val output: String = listOf(stdout, stderr).joinToString("\n").trim()
}

@Component
class LocalProcessRunner : ProcessRunner {
    override fun run(
        command: List<String>,
        cwd: Path?,
        env: Map<String, String>,
        timeoutSeconds: Long,
    ): ProcessResult =
        CallMetrics.measure(command.firstOrNull() ?: "cmd", command.take(2).joinToString(" ")) {
            val builder = ProcessBuilder(command)
            cwd?.let { builder.directory(it.toFile()) }
            builder.environment().putAll(env)

            val process = builder.start()
            // stdout/stderr MOETEN doorlopend afgetapt worden terwijl het proces draait, niet pas ná
            // waitFor: de OS-pipebuffer per stream is beperkt (~64KB). Een kind dat meer dan dat
            // schrijft vóórdat de parent begint te lezen, blokkeert op zijn eigen write() — en de
            // parent zit dan in waitFor() te wachten op een proces dat op zijn beurt op de parent wacht.
            // Klassieke ProcessBuilder-deadlock, hier ontdekt via `oc delete project`: die drukt bij
            // veel resources (bv. personal-feed z'n preview-namespaces, met meer componenten dan
            // robberts-assistent z'n) genoeg voortgangsregels af om de buffer te vullen, waarna de
            // 120s-timeout altijd werd geraakt — nooit een reëel trage delete, gewoon een vastgelopen
            // stream (preview-cleanup faalde hierdoor structureel voor personal-feed, met onzichtbaar
            // achtergebleven namespaces tot gevolg).
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val stdoutDrain = drainAsync(process.inputStream, stdout)
            val stderrDrain = drainAsync(process.errorStream, stderr)

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            // destroyForcibly() sluit de streams -> de drain-threads lopen vanzelf af op EOF; de join
            // hieronder is puur een korte, begrensde vangnet zodat we nooit oneindig blijven wachten.
            stdoutDrain.join(TimeUnit.SECONDS.toMillis(5))
            stderrDrain.join(TimeUnit.SECONDS.toMillis(5))

            if (!finished) {
                ProcessResult(124, stdout.toString(), "Command timed out after ${timeoutSeconds}s")
            } else {
                ProcessResult(
                    exitCode = process.exitValue(),
                    stdout = stdout.toString(),
                    stderr = stderr.toString(),
                )
            }
        }

    private fun drainAsync(stream: java.io.InputStream, into: StringBuilder): Thread =
        Thread {
            runCatching { stream.bufferedReader().forEachLine { into.appendLine(it) } }
        }.apply {
            isDaemon = true
            start()
        }
}
