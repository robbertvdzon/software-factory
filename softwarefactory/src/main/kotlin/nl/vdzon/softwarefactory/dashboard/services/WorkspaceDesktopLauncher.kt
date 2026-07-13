package nl.vdzon.softwarefactory.dashboard.services

import org.springframework.stereotype.Service
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Opent een repo-map in IntelliJ IDEA op de host-machine, via macOS' `open -a`.
 *
 * LET OP: dit is een local-mode-dev-feature en werkt UITSLUITEND wanneer de factory direct op de
 * Mac-host draait — in een container/CI bestaat het `open`-commando niet en is er geen IDE.
 * Daarom apart gehouden van [DashboardQueryService]: al het ProcessBuilder-/host-specifieke
 * gedrag zit in deze ene kleine class.
 */
@Service
class WorkspaceDesktopLauncher {

    fun openInIntellij(repoRoot: Path) {
        val process = ProcessBuilder("open", "-a", "IntelliJ IDEA", repoRoot.toString()).start()
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("IntelliJ openen duurde langer dan 10 seconden")
        }
        check(process.exitValue() == 0) {
            val output = process.errorStream.bufferedReader().readText()
                .ifBlank { process.inputStream.bufferedReader().readText() }
                .ifBlank { "exit code ${process.exitValue()}" }
            "IntelliJ openen faalde: $output"
        }
    }
}
