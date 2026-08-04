package nl.vdzon.softwarefactory.runtime.services

import nl.vdzon.softwarefactory.core.contracts.AgentRunRepository
import nl.vdzon.softwarefactory.maintenance.repositories.CleanupKinds
import nl.vdzon.softwarefactory.maintenance.repositories.CleanupTriggers
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Retentie voor `agent_runs` — de rijen achter het agent-log-scherm.
 *
 * Deze tabel werd als enige nooit opgeruimd: het scherm toonde runs van maanden geleden waarvan de
 * inhoud (`agent_events`) allang door [AgentEventRetentionPoller] was weggehaald. Bewust een aparte
 * poller en geen uitbreiding van de event-retentie: andere default-termijn (90 dagen, zodat de
 * kostenhistorie langer meegaat dan de logregels), een eigen aan/uit-schakelaar, en eigen
 * veiligheidsregels (zie [AgentRunRepository.deleteOlderThan]). De event-retentie is per definitie
 * een no-op zodra een run verdwijnt — die events gaan mee via `ON DELETE CASCADE`.
 *
 * Verwijdert in batches: de eerste ronde op een lang ongemoeide tabel zou anders één transactie van
 * honderdduizenden rijen zijn terwijl de factory doorpolt. Loopt een ronde tegen
 * [AgentRunRetentionSettings.maxBatchesPerRun] aan, dan gaat de volgende tick gewoon verder.
 */
@Component
class AgentRunRetentionPoller(
    private val repository: AgentRunRepository,
    private val settings: AgentRunRetentionSettings,
    private val clock: Clock = Clock.systemUTC(),
    private val cleanupLog: CleanupLogWriter? = null,
) : CleanupRunner {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val cleanupKind: String = CleanupKinds.AGENT_RUNS

    override fun cleanupEnabled(): Boolean = settings.enabled

    override fun runCleanupRoundLocked(trigger: String) {
        val log = cleanupLog ?: return runCleanupWithoutLog()
        log.runLocked(cleanupKind, trigger) { cleanupOnce() }
    }

    /** Alleen in unit-tests: daar wordt de poller zonder opruim-log geconstrueerd. */
    private fun runCleanupWithoutLog() {
        runCatching { cleanupOnce() }.onFailure { logger.warn("Agent-run-retentie faalde.", it) }
    }

    @Scheduled(
        fixedDelayString = "\${softwarefactory.agent-run-retention-poll-ms:3600000}",
        initialDelayString = "\${softwarefactory.agent-run-retention-initial-delay-ms:180000}",
    )
    fun poll() {
        if (!settings.enabled) return
        val log = cleanupLog ?: return runCleanupWithoutLog()
        log.runGuarded(cleanupKind, CleanupTriggers.SCHEDULED, enabled = true) { cleanupOnce() }
    }

    /** Eén opruimronde; geeft het aantal verwijderde runs terug. Public zodat tests 'm kunnen aanroepen. */
    fun cleanupOnce(): Int {
        val cutoff = OffsetDateTime.now(clock).minus(Duration.ofDays(settings.retentionDays))
        var total = 0
        for (batch in 0 until settings.maxBatchesPerRun) {
            val deleted = repository.deleteOlderThan(cutoff, settings.batchSize)
            total += deleted
            // Minder dan een volle batch = niets meer opruimbaars over de cutoff heen; stoppen.
            if (deleted < settings.batchSize) break
        }
        if (total > 0) {
            logger.info("Agent-run-retentie: {} runs ouder dan {} dagen verwijderd.", total, settings.retentionDays)
        }
        return total
    }
}
