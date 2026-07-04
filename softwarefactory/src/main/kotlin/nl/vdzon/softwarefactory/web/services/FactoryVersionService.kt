package nl.vdzon.softwarefactory.web.services

import jakarta.annotation.PostConstruct
import nl.vdzon.softwarefactory.web.models.FactoryVersionInfo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/**
 * Legt **bij opstart** vast op welke git-commit de draaiende factory staat, plus het starttijdstip.
 * Bewust één keer bij start (niet live): zo toont de UI de versie die écht draait, en niet een latere
 * `git pull` die pas na een herstart actief wordt. Hiermee kun je aan de commit-message zien of een
 * bepaalde story al in de draaiende versie zit.
 */
@Service
class FactoryVersionService {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val root: Path = projectRoot()
    private val versionInfo: FactoryVersionInfo by lazy { capture() }

    fun info(): FactoryVersionInfo = versionInfo

    /** Korte git-sha, voor de bridge-hello (die alleen een `FactoryVersionInfo`-veld nodig heeft). */
    fun commitShort(): String = versionInfo.commitShort

    @PostConstruct
    fun captureOnStartup() {
        // Forceert de lazy capture meteen bij opstart, zodat startedAt klopt en het in de log staat.
        val v = versionInfo
        logger.info(
            "Draaiende versie: branch={} commit={} \"{}\" ({}){}",
            v.branch, v.commitShort, v.commitSubject, v.commitDate, if (v.dirty) " [met ongecommitte changes]" else "",
        )
    }

    private fun capture(): FactoryVersionInfo {
        // %h=short sha, %s=subject, %ci=commit-datum; velden gescheiden door unit-separator (0x1f).
        val raw = git("log", "-1", "--format=%h%x1f%s%x1f%ci")
        val parts = raw?.split('\u001F').orEmpty()
        return FactoryVersionInfo(
            startedAt = OffsetDateTime.now(),
            branch = git("rev-parse", "--abbrev-ref", "HEAD")?.takeIf { it.isNotBlank() } ?: "onbekend",
            commitShort = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "onbekend",
            commitSubject = parts.getOrNull(1).orEmpty(),
            commitDate = parts.getOrNull(2).orEmpty(),
            dirty = git("status", "--porcelain").orEmpty().isNotBlank(),
        )
    }

    /** Draait een git-commando in de repo-root; geeft de getrimde stdout terug, of null bij een fout. */
    private fun git(vararg args: String): String? =
        runCatching {
            val process = ProcessBuilder(listOf("git") + args)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else if (process.exitValue() == 0) {
                output
            } else {
                null
            }
        }.getOrNull()

    /** Repo-root, ook als de cwd de module-map is (mvn -pl). Lokaal i.v.m. module-grenzen. */
    private fun projectRoot(): Path {
        val cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val parent = cwd.parent
        return if (cwd.fileName?.toString() == "softwarefactory" && parent != null && Files.exists(parent.resolve("agentworker"))) {
            parent
        } else {
            cwd
        }
    }
}
