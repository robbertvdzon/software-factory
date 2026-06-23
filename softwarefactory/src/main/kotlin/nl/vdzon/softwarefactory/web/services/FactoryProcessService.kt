package nl.vdzon.softwarefactory.web.services

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

/**
 * Herstart/stop van het factory-proces zelf — bedoeld voor de bash-loop die de factory draait
 * (`git pull && mvn spring-boot:run` in een lus; zie `factory-loop.sh`). De loop herstart de app na
 * elke exit; alleen als er een stop-signaalbestand ligt stopt de loop ook zichzelf.
 *
 *  - **Restart**: de JVM stopt netjes met code 0 → de loop start 'm opnieuw (met een verse `git pull`).
 *  - **Stop**:    eerst het stop-signaalbestand schrijven, dan stoppen → de loop ziet het bestand en stopt.
 *
 * Het signaalbestand staat in `work/.factory-stop` (gitignored) en wordt bij elke start gewist, zodat
 * een achtergebleven signaal nooit een verse start saboteert.
 */
@Service
class FactoryProcessService {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** `<repo-root>/work/.factory-stop` — exact het pad dat de loop checkt. */
    private val stopSignalFile: Path = projectRoot().resolve("work").resolve(STOP_SIGNAL_FILENAME)

    /** Vangnet: een achtergebleven stop-signaal bij opstart wissen (zie klassedoc). */
    @PostConstruct
    fun clearStopSignalOnStartup() {
        runCatching { Files.deleteIfExists(stopSignalFile) }
            .onSuccess { deleted -> if (deleted) logger.info("Oud stop-signaal {} gewist bij opstart.", stopSignalFile) }
            .onFailure { logger.warn("Kon stop-signaal {} niet wissen bij opstart.", stopSignalFile, it) }
    }

    /** Stopt de JVM (code 0). De loop herstart de factory met de nieuwste code. */
    fun requestRestart() {
        logger.info("Herstart aangevraagd via dashboard — JVM stopt, de loop start opnieuw.")
        scheduleExit()
    }

    /** Schrijft het stop-signaal en stopt de JVM (code 0). De loop ziet het bestand en stopt zelf ook. */
    fun requestStop() {
        runCatching {
            Files.createDirectories(stopSignalFile.parent)
            Files.writeString(stopSignalFile, "stop requested via dashboard\n")
        }.onFailure { logger.error("Kon stop-signaal {} niet schrijven; de loop stopt mogelijk niet.", stopSignalFile, it) }
        logger.info("Stop aangevraagd via dashboard — stop-signaal geschreven, JVM stopt, de loop stopt ook.")
        scheduleExit()
    }

    /**
     * Stopt het proces hard. Geen graceful Spring-shutdown: die wachtte ~30s op o.a. de Telegram
     * long-poll (25s) en de lifecycle-timeout. We doen alleen een korte vertraging zodat de HTTP-respons
     * (de "herstarten…"-pagina) nog vertrekt, en daarna `Runtime.halt(0)` — directe exit zonder
     * shutdown-hooks. De loop ziet exit 0 en start opnieuw (DB-connecties worden simpelweg gedropt; een
     * eventuele open transactie rolt Postgres terug).
     */
    private fun scheduleExit() {
        Thread {
            runCatching { Thread.sleep(EXIT_DELAY_MS) }
            Runtime.getRuntime().halt(0)
        }.apply {
            name = "factory-self-exit"
            isDaemon = false
        }.start()
    }

    /**
     * Repo-root, ook als de app via `mvn -pl softwarefactory spring-boot:run` start (dan is de cwd de
     * module-map). Spiegelt `AgentWorkspaceFactory.projectRoot()` bewust lokaal, om geen module-grens
     * (web → runtime) te kruisen.
     */
    private fun projectRoot(): Path {
        val cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val parent = cwd.parent
        return if (cwd.fileName?.toString() == "softwarefactory" && parent != null && Files.exists(parent.resolve("agentworker"))) {
            parent
        } else {
            cwd
        }
    }

    private companion object {
        const val STOP_SIGNAL_FILENAME = ".factory-stop"
        const val EXIT_DELAY_MS = 600L
    }
}
