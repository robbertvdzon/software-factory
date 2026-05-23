package nl.vdzon.softwarefactory.git

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
    ): ProcessResult {
        val builder = ProcessBuilder(command)
        cwd?.let { builder.directory(it.toFile()) }
        builder.environment().putAll(env)

        val process = builder.start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return ProcessResult(124, "", "Command timed out after ${timeoutSeconds}s")
        }
        return ProcessResult(
            exitCode = process.exitValue(),
            stdout = process.inputStream.bufferedReader().readText(),
            stderr = process.errorStream.bufferedReader().readText(),
        )
    }
}
