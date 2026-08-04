package nl.vdzon.softwarefactory.runtime.services

import nl.vdzon.softwarefactory.maintenance.CleanupRunGuard
import nl.vdzon.softwarefactory.maintenance.types.CleanupRunStatus
import nl.vdzon.softwarefactory.maintenance.MaintenanceCleanupApi
import nl.vdzon.softwarefactory.maintenance.repositories.CleanupKinds
import nl.vdzon.softwarefactory.maintenance.repositories.CleanupTriggers
import nl.vdzon.softwarefactory.runtime.CleanupRunNowApi
import nl.vdzon.softwarefactory.runtime.models.CleanupRunNowOutcome
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * De handmatige route van de "Nu draaien"-knop (SF-1929).
 *
 * Drie eigenschappen, elk met een reden:
 *
 *  * **Geen tweede implementatie.** Elke soort komt uit op dezelfde ronde als zijn scheduler: de
 *    [CleanupRunner]-beans (de pollers zelf) en [MaintenanceCleanupApi] (de GitHub-tick).
 *  * **Synchroon claimen, asynchroon draaien.** De [CleanupRunGuard] wordt in de aanroepende thread
 *    gepakt — anders zou een tweede snelle klik óók "gestart" krijgen — waarna de ronde zelf op een
 *    executor loopt zodat de 30s-timeout van de bridge een lange GitHub-ronde nooit afkapt.
 *  * **Weigeren is geen fout.** Al draaiend/uitgezet/onbekend levert een status op (HTTP 200), net
 *    als bij `audit.runNow`; alleen echte fouten volgen het `BridgeError`-pad.
 */
@Service
class CleanupRunNowService @Autowired constructor(
    runners: List<CleanupRunner>,
    maintenanceCleanupApi: MaintenanceCleanupApi,
    private val guard: CleanupRunGuard,
) : CleanupRunNowApi {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Bewust géén constructorparameter: de Spring-context heeft twee [Executor]-beans
     * (`applicationTaskExecutor` en `taskScheduler`) en zou daarop stukvallen. De secundaire
     * constructor is de test-seam — daar gaat een directe executor in, zodat de ronde klaar is
     * zodra `runNow` terug is.
     */
    private var executor: Executor = defaultExecutor()

    constructor(
        runners: List<CleanupRunner>,
        maintenanceCleanupApi: MaintenanceCleanupApi,
        guard: CleanupRunGuard,
        executor: Executor,
    ) : this(runners, maintenanceCleanupApi, guard) {
        this.executor = executor
    }

    /** De GitHub-cleanup als [CleanupRunner], zodat de vijf soorten hier één pad delen. */
    private val gitHubRunner = object : CleanupRunner {
        override val cleanupKind: String = CleanupKinds.GITHUB_RELEASES
        override fun cleanupEnabled(): Boolean = true
        override fun runCleanupRoundLocked(trigger: String) = maintenanceCleanupApi.runCleanupRoundLocked(trigger)
    }

    private val runnersByKind: Map<String, CleanupRunner> =
        (runners + gitHubRunner).associateBy { it.cleanupKind }

    override fun runNow(kind: String): CleanupRunNowOutcome =
        if (kind == CleanupKinds.ALL_KINDS) runAll() else runOne(kind)

    private fun runAll(): CleanupRunNowOutcome {
        val perKind = CleanupKinds.ALL.associateWith { start(it) }
        return CleanupRunNowOutcome(summarize(perKind.values), perKind)
    }

    private fun runOne(kind: String): CleanupRunNowOutcome {
        val status = start(kind)
        return CleanupRunNowOutcome(status, mapOf(kind to status))
    }

    /** Claimt de bewaking en zet de ronde weg; geeft meteen terug wat er met dit verzoek gebeurde. */
    private fun start(kind: String): CleanupRunStatus {
        val runner = runnersByKind[kind] ?: return CleanupRunStatus.UNKNOWN_KIND
        return when {
            !runner.cleanupEnabled() -> CleanupRunStatus.DISABLED
            !guard.tryStart(kind) -> CleanupRunStatus.ALREADY_RUNNING
            else -> {
                submit(runner)
                CleanupRunStatus.STARTED
            }
        }
    }

    private fun submit(runner: CleanupRunner) {
        val kind = runner.cleanupKind
        runCatching {
            executor.execute {
                try {
                    // Fail-soft: de logregel met de fout wordt in de ronde zelf geschreven
                    // (CleanupLogWriter.runLocked); hier telt alleen dat de bewaking vrijkomt.
                    runCatching { runner.runCleanupRoundLocked(CleanupTriggers.MANUAL) }
                        .onFailure { logger.warn("Handmatige opruimronde '{}' faalde.", kind, it) }
                } finally {
                    guard.finish(kind)
                }
            }
        }.onFailure {
            // Kon de ronde niet eens ingepland worden, dan mag de bewaking niet blijven hangen.
            guard.finish(kind)
            logger.warn("Handmatige opruimronde '{}' kon niet worden gestart.", kind, it)
        }
    }

    private companion object {
        /** Twee daemon-threads: genoeg om "alles draaien" op te vangen, en nooit een reden om te blijven hangen. */
        fun defaultExecutor(): Executor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "cleanup-run-now").apply { isDaemon = true }
        }

        /** Bij "alles draaien" telt of er íets gestart is; anders is de meest voorkomende weigering leidend. */
        fun summarize(statuses: Collection<CleanupRunStatus>): CleanupRunStatus =
            statuses.firstOrNull { it == CleanupRunStatus.STARTED }
                ?: statuses.firstOrNull { it == CleanupRunStatus.ALREADY_RUNNING }
                ?: statuses.firstOrNull()
                ?: CleanupRunStatus.UNKNOWN_KIND
    }
}
