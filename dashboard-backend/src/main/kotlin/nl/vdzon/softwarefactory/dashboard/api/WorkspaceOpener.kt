package nl.vdzon.softwarefactory.dashboard.api

import nl.vdzon.softwarefactory.dashboard.config.DashboardSecrets
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Opent de story-workspace in IntelliJ via het macOS-`open`-commando. Dat werkt per definitie
 * alleen op de machine waar ook de factory-workspaces staan; in de k8s-deploy zou de
 * ProcessBuilder gegarandeerd falen. Daarom zit dit achter een expliciete local-mode-vlag
 * (env `SF_DASHBOARD_LOCAL_MODE=true`, default uit): zonder vlag een nette 409 met uitleg
 * i.p.v. een half-gefaalde procesaanroep.
 */
@Component
class WorkspaceOpener(
    private val secrets: DashboardSecrets,
    // Injecteerbaar zodat tests geen echt IntelliJ-proces hoeven te starten.
    private val startProcess: (List<String>) -> Process = { command -> ProcessBuilder(command).start() },
) {
    fun openInIntellij(storyKey: String, workspacePath: String?): String {
        if (!secrets.localMode) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Workspace openen kan alleen bij een lokale dashboard-run " +
                    "(start met SF_DASHBOARD_LOCAL_MODE=true); deze server draait zonder local mode.",
            )
        }
        val workspaceRoot = workspacePath?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it).toAbsolutePath().normalize() }
            ?: throw IllegalArgumentException("Geen workspace-pad gevonden voor $storyKey")
        val repoRoot = workspaceRoot.resolve("repo").toAbsolutePath().normalize()
        require(repoRoot.startsWith(workspaceRoot)) {
            "Ongeldig repo-pad voor $storyKey: $repoRoot"
        }
        require(Files.isDirectory(repoRoot)) {
            "Repo folder bestaat niet voor $storyKey: $repoRoot"
        }
        val process = startProcess(listOf("open", "-a", "IntelliJ IDEA", repoRoot.toString()))
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("IntelliJ openen duurde langer dan 10 seconden")
        }
        if (process.exitValue() != 0) {
            val message = process.errorStream.bufferedReader().readText()
                .ifBlank { process.inputStream.bufferedReader().readText() }
                .ifBlank { "exit code ${process.exitValue()}" }
            error("IntelliJ openen faalde: $message")
        }
        return repoRoot.toString()
    }
}
