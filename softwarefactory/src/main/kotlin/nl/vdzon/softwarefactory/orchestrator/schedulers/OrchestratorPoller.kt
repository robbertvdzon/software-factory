package nl.vdzon.softwarefactory.orchestrator.schedulers

import jakarta.annotation.PreDestroy
import nl.vdzon.softwarefactory.orchestrator.ChangeNotifier
import nl.vdzon.softwarefactory.orchestrator.FactoryStateChangedEvent
import nl.vdzon.softwarefactory.orchestrator.IssueProcessResult
import nl.vdzon.softwarefactory.orchestrator.OrchestratorPollResult
import nl.vdzon.softwarefactory.orchestrator.OrchestratorSettings
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.support.CallMetrics
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Adaptief pollen met wekbare sleep.
 *
 * Cadans: snel ([OrchestratorSettings.pollInterval]) zolang er actief werk loopt (een agent draait
 * of er was een transitie), anders traag ([OrchestratorSettings.pollIntervalIdle]). Na elke poll
 * triggert [ChangeNotifier] de UI om te verversen.
 *
 * Daarnaast luistert de poller op [FactoryStateChangedEvent]: zodra een agent klaar is en de
 * story/subtask heeft bijgewerkt, wordt de wachtende sleep meteen gewekt zodat de keten zonder
 * vertraging doorzet — het poll-interval is dan alleen nog het vangnet.
 */
@Component
class OrchestratorPoller(
    private val orchestratorService: OrchestratorApi,
    private val settings: OrchestratorSettings,
    private val changeNotifier: ChangeNotifier,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val worker = Thread(::loop, "orchestrator-poller").apply { isDaemon = true }

    private val lock = ReentrantLock()
    private val wakeCondition = lock.newCondition()
    private var wakePending = false
    @Volatile private var running = true

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        worker.start()
    }

    @PreDestroy
    fun stop() {
        running = false
        wake()
        worker.interrupt()
    }

    /** Maakt een wachtende poll-sleep direct wakker (vanuit het state-changed event of intern). */
    @EventListener
    fun onStateChanged(event: FactoryStateChangedEvent) {
        logger.debug("State-changed event ({}) — poller wordt gewekt.", event.origin)
        wake()
    }

    private fun wake() {
        lock.withLock {
            wakePending = true
            wakeCondition.signalAll()
        }
    }

    private fun loop() {
        while (running) {
            val active = runOnce()
            if (!running) break
            val delay = if (active) settings.pollInterval else settings.pollIntervalIdle
            sleepUntilDeadlineOrWake(delay.toMillis())
        }
    }

    /** Wacht maximaal [delayMs], maar keert direct terug als er ondertussen een wake binnenkwam. */
    private fun sleepUntilDeadlineOrWake(delayMs: Long) {
        lock.withLock {
            if (wakePending) {
                wakePending = false
                return
            }
            try {
                wakeCondition.await(delayMs, TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                wakePending = false
            }
        }
    }

    private fun runOnce(): Boolean {
        var active = false
        CallMetrics.begin()
        val startTime = System.currentTimeMillis()
        try {
            logger.info("Start poll")
            val result = orchestratorService.pollOnce()
            active = result.hasActiveWork()
            val elapsed = System.currentTimeMillis() - startTime
            logger.info(
                "Orchestrator poll processed {} AI issue(s) in {} msec (active={}). REST: {}",
                result.issueResults.size,
                elapsed,
                active,
                CallMetrics.report(CallMetrics.end()),
            )
            runCatching { changeNotifier.notifyChanged() }
                .onFailure { logger.debug("ChangeNotifier faalde (genegeerd).", it) }
        } catch (exception: Exception) {
            CallMetrics.end()
            logger.warn("Orchestrator poll failed.", exception)
        }
        return active
    }

    /**
     * "Actief" = er draait een agent (`agent-running`) of er was een echte transitie deze poll.
     * Skips op "wacht op gebruiker/goedkeuring", "paused", "error" etc. tellen als idle: daar
     * komt de volgende verandering van een mens-actie of een agent-afronding (die zelf al wakker maakt).
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
