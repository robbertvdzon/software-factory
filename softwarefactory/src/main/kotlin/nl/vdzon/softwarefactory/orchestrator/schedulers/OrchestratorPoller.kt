package nl.vdzon.softwarefactory.orchestrator.schedulers

import jakarta.annotation.PreDestroy
import nl.vdzon.softwarefactory.core.contracts.ChangeNotifier
import nl.vdzon.softwarefactory.core.contracts.FactoryStateChangedEvent
import nl.vdzon.softwarefactory.core.contracts.OrchestratorSettings
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.support.CallMetrics
import nl.vdzon.softwarefactory.telegram.TelegramNotifier
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Pollen met wekbare sleep op een vast backup-interval ([OrchestratorSettings.pollInterval]). Na
 * elke poll triggert [ChangeNotifier] de UI om te verversen.
 *
 * Daarnaast luistert de poller op [FactoryStateChangedEvent]: zodra een agent klaar is en de
 * story/subtask heeft bijgewerkt (of een schrijf-operatie in de tracker-client heeft
 * plaatsgevonden), wordt de wachtende sleep meteen gewekt zodat de keten zonder vertraging
 * doorzet — het vaste poll-interval is dan alleen nog het vangnet.
 */
@Component
class OrchestratorPoller(
    private val orchestratorService: OrchestratorApi,
    private val settings: OrchestratorSettings,
    private val changeNotifier: ChangeNotifier,
    // Optioneel: stuurt Telegram-meldingen bij vragen/klaar/fouten. Null in contexten zonder de bean (tests).
    private val telegramNotificationService: TelegramNotifier? = null,
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
            runOnce()
            if (!running) break
            sleepUntilDeadlineOrWake(settings.pollInterval.toMillis())
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

    private fun runOnce() {
        CallMetrics.begin()
        val startTime = System.currentTimeMillis()
        try {
            logger.info("Start poll")
            val result = orchestratorService.pollOnce()
            val elapsed = System.currentTimeMillis() - startTime
            logger.info(
                "Orchestrator poll processed {} AI issue(s) in {} msec. REST: {}",
                result.issueResults.size,
                elapsed,
                CallMetrics.report(CallMetrics.end()),
            )
            runCatching { changeNotifier.notifyChanged() }
                .onFailure { logger.debug("ChangeNotifier faalde (genegeerd).", it) }
            // Uitgaande Telegram-meldingen op de poll-cadans (no-op wanneer de integratie uit staat).
            runCatching { telegramNotificationService?.notifyPending() }
                .onFailure { logger.debug("Telegram-notify faalde (genegeerd).", it) }
        } catch (exception: Exception) {
            CallMetrics.end()
            logger.warn("Orchestrator poll failed.", exception)
        }
    }
}
