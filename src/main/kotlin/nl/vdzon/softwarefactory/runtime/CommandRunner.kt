package nl.vdzon.softwarefactory.runtime

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
    override fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
        val process = ProcessBuilder(command).start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return CommandResult(124, "", "Command timed out after ${timeoutSeconds}s")
        }
        return CommandResult(
            exitCode = process.exitValue(),
            stdout = process.inputStream.bufferedReader().readText(),
            stderr = process.errorStream.bufferedReader().readText(),
        )
    }
}
