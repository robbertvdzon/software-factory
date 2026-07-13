package nl.vdzon.softwarefactory.agent.ai.shared

import java.nio.file.Path

/** Supplier-neutrale, begrensde subprocessmechanics; suppliers blijven eigenaar van argv en env-policy. */
object CliProcessRunner {
    fun run(
        command: List<String>,
        cwd: Path,
        env: Map<String, String>,
        removedEnvironmentKeys: Set<String> = emptySet(),
        onLine: (String) -> Unit,
    ): Int {
        val process = ProcessBuilder(command)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .also { builder ->
                builder.environment().putAll(env)
                removedEnvironmentKeys.forEach(builder.environment()::remove)
            }
            .start()
        process.outputStream.close()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
        return process.waitFor()
    }
}
