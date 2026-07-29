package nl.vdzon.softwarefactory.runtime.services

import nl.vdzon.softwarefactory.runtime.repositories.AgentEventRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Retentie voor `agent_events` — de agent-logregels achter het Agent-log-scherm.
 *
 * Deze tabel had als enige geen enkele opruiming en groeide daardoor onbeperkt: op 2026-07-29 was
 * hij 436 MB (233.000 rijen vanaf mei), goed voor meer dan de helft van de hele database. De
 * payloads zijn alleen interessant om een recente run na te lezen; oudere regels kosten alleen
 * ruimte. Zelfde opzet als [nl.vdzon.softwarefactory.runtime.workspaces.WorkCleanupPoller] en de
 * payload-purge van `agent_run_completions` (`SF_COMPLETION_RETENTION_DAYS`).
 *
 * Verwijdert in batches: de eerste ronde op een lang ongemoeide tabel zou anders één transactie van
 * honderdduizenden rijen zijn terwijl de factory doorpolt. Loopt een ronde tegen [maxBatchesPerRun]
 * aan, dan gaat de volgende tick gewoon verder.
 */
@Component
class AgentEventRetentionPoller(
    private val repository: AgentEventRepository,
    private val settings: AgentEventRetentionSettings,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${softwarefactory.agent-event-retention-poll-ms:3600000}",
        initialDelayString = "\${softwarefactory.agent-event-retention-initial-delay-ms:120000}",
    )
    fun poll() {
        if (!settings.enabled) return
        runCatching { cleanupOnce() }
            .onFailure { logger.warn("Agent-event-retentie faalde.", it) }
    }

    /** Eén opruimronde; geeft het aantal verwijderde events terug. Public zodat tests 'm kunnen aanroepen. */
    fun cleanupOnce(): Int {
        val cutoff = OffsetDateTime.now(clock).minus(Duration.ofDays(settings.retentionDays))
        var total = 0
        for (batch in 0 until settings.maxBatchesPerRun) {
            val deleted = repository.deleteOlderThan(cutoff, settings.batchSize)
            total += deleted
            // Minder dan een volle batch = niets meer over de cutoff heen; stoppen.
            if (deleted < settings.batchSize) break
        }
        if (total > 0) {
            logger.info("Agent-event-retentie: {} events ouder dan {} dagen verwijderd.", total, settings.retentionDays)
        }
        return total
    }
}
