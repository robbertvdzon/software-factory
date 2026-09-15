package nl.vdzon.softwarefactory.dashboard.services

import jakarta.annotation.PostConstruct
import nl.vdzon.softwarefactory.dashboard.models.FactoryVersionInfo
import nl.vdzon.softwarefactory.dashboard.FactoryVersionQuery
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/**
 * Legt **bij opstart** vast op welke git-commit de draaiende factory staat, plus het starttijdstip.
 * Bewust één keer bij start (niet live): zo toont de UI de versie die écht draait.
 *
 * In een image is er geen `.git`; dan komen commit en branch uit de omgevingsvariabelen
 * `SF_BUILD_COMMIT`, `SF_BUILD_BRANCH`, `SF_BUILD_COMMIT_SUBJECT` en `SF_BUILD_COMMIT_DATE`, die
 * het Dockerfile als build-args meebakt. Een lokale checkout (ontwikkelen) leest gewoon git.
 */
@Service
class FactoryVersionService(
    private val environment: Map<String, String> = System.getenv(),
) : FactoryVersionQuery {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val root: Path = projectRoot()
    private val versionInfo: FactoryVersionInfo by lazy { capture() }

    override fun info(): FactoryVersionInfo = versionInfo

    /** Korte git-sha, zoals de statusregel van het dashboard die toont. */
    override fun commitShort(): String = versionInfo.commitShort

    @PostConstruct
    fun captureOnStartup() {
        // Forceert de lazy capture meteen bij opstart, zodat startedAt klopt en het in de log staat.
        val v = versionInfo
        logger.info(
            "Draaiende versie: branch={} commit={} \"{}\" ({}){}",
            v.branch, v.commitShort, v.commitSubject, v.commitDate, if (v.dirty) " [met ongecommitte changes]" else "",
        )
    }

    private fun capture(): FactoryVersionInfo =
        if (Files.exists(root.resolve(".git"))) captureFromGit() else captureFromBuildInfo()

    private fun captureFromGit(): FactoryVersionInfo {
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

    private fun captureFromBuildInfo(): FactoryVersionInfo =
        FactoryVersionInfo(
            startedAt = OffsetDateTime.now(),
            branch = environment["SF_BUILD_BRANCH"]?.takeIf { it.isNotBlank() } ?: "onbekend",
            commitShort = environment["SF_BUILD_COMMIT"]?.takeIf { it.isNotBlank() }?.take(SHORT_SHA_LENGTH) ?: "onbekend",
            commitSubject = environment["SF_BUILD_COMMIT_SUBJECT"].orEmpty(),
            commitDate = environment["SF_BUILD_COMMIT_DATE"].orEmpty(),
            dirty = false,
        )

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

    /** Repo-root, ook als Maven de app met de modulemap als cwd start. */
    private fun projectRoot(): Path {
        val cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val parent = cwd.parent
        return if (cwd.fileName?.toString() == "softwarefactory" && parent != null && Files.exists(parent.resolve("pom.xml"))) {
            parent
        } else {
            cwd
        }
    }

    private companion object {
        const val SHORT_SHA_LENGTH = 7
    }
}
