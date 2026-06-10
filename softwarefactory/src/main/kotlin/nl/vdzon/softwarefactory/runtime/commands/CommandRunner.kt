package nl.vdzon.softwarefactory.runtime.commands

import nl.vdzon.softwarefactory.support.CallMetrics
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

interface CommandRunner {
    fun run(command: List<String>, timeoutSeconds: Long = 30): CommandResult
}

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

@Component
class ProcessBuilderCommandRunner : CommandRunner {
    override fun run(command: List<String>, timeoutSeconds: Long): CommandResult =
        CallMetrics.measure(command.firstOrNull() ?: "cmd", command.take(2).joinToString(" ")) {
            val process = ProcessBuilder(command).start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                CommandResult(124, "", "Command timed out after ${timeoutSeconds}s")
            } else {
                CommandResult(
                    exitCode = process.exitValue(),
                    stdout = process.inputStream.bufferedReader().readText(),
                    stderr = process.errorStream.bufferedReader().readText(),
                )
            }
        }
}
