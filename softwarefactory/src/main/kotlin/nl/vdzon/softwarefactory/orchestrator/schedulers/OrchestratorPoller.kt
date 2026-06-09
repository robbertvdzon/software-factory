package nl.vdzon.softwarefactory.orchestrator.schedulers

import jakarta.annotation.PreDestroy
import nl.vdzon.softwarefactory.orchestrator.ChangeNotifier
import nl.vdzon.softwarefactory.orchestrator.IssueProcessResult
import nl.vdzon.softwarefactory.orchestrator.OrchestratorPollResult
import nl.vdzon.softwarefactory.orchestrator.models.OrchestratorSettings
import nl.vdzon.softwarefactory.orchestrator.services.OrchestratorService
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Adaptief pollen: zolang er actief werk loopt (een agent draait of er gebeurde een transitie)
 * pollen we op het snelle [OrchestratorSettings.pollInterval]; is alles idle, dan zakken we terug
 * naar [OrchestratorSettings.pollIntervalIdle]. Na elke poll triggert [ChangeNotifier] de UI om
 * te verversen.
 *
 * De poller plant zichzelf in (i.p.v. een vaste `@Scheduled`-delay) zodat het volgende interval
 * van de uitkomst van de vorige poll kan afhangen.
 */
@Component
class OrchestratorPoller(
    private val orchestratorService: OrchestratorService,
    private val settings: OrchestratorSettings,
    private val changeNotifier: ChangeNotifier,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "orchestrator-poller").apply { isDaemon = true }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        scheduleNext(0)
    }

    @PreDestroy
    fun stop() {
        scheduler.shutdownNow()
    }

    private fun scheduleNext(delayMs: Long) {
        runCatching { scheduler.schedule({ runOnce() }, delayMs, TimeUnit.MILLISECONDS) }
            .onFailure { logger.warn("Kon volgende orchestrator-poll niet inplannen.", it) }
    }

    private fun runOnce() {
        var active = false
        try {
            logger.info("Start poll")
            val startTime = System.currentTimeMillis()
            val result = orchestratorService.pollOnce()
            active = result.hasActiveWork()
            val endTime = System.currentTimeMillis()
            logger.info(
                "Orchestrator poll processed {} AI issue(s) in {} msec (active={}).",
                result.issueResults.size,
                endTime - startTime,
                active,
            )
            runCatching { changeNotifier.notifyChanged() }
                .onFailure { logger.debug("ChangeNotifier faalde (genegeerd).", it) }
        } catch (exception: Exception) {
            logger.warn("Orchestrator poll failed.", exception)
        } finally {
            val nextDelay = if (active) settings.pollInterval else settings.pollIntervalIdle
            scheduleNext(nextDelay.toMillis())
        }
    }

    /**
     * "Actief" = er draait een agent (`agent-running`) of er was een echte transitie deze poll.
     * Skips op "wacht op gebruiker/goedkeuring", "paused", "error" etc. tellen als idle: daar
     * komt de volgende verandering van een mens-actie (die zelf al een refresh triggert).
     */
    private fun OrchestratorPollResult.hasActiveWork(): Boolean =
        issueResults.any { result ->
            when (result) {
                is IssueProcessResult.Dispatched,
                is IssueProcessResult.Recovered,
                is IssueProcessResult.Chained,
                is IssueProcessResult.Merged,
                is IssueProcessResult.PrCommentTriggered,
                is IssueProcessResult.Errored,
                -> true
                is IssueProcessResult.Skipped -> result.reason == "agent-running"
            }
        }
}
