package nl.vdzon.softwarefactory.web.services

import nl.vdzon.softwarefactory.orchestrator.ChangeNotifier
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Houdt de open SSE-verbindingen van browsers bij en stuurt een "changed"-event zodra de
 * factory-state mogelijk is veranderd (vanuit [ChangeNotifier], aangeroepen door de poller en
 * door UI-mutaties). De browser ververst dan alleen z'n data-laag.
 */
@Service
class DashboardEventBus : ChangeNotifier {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val emitters = CopyOnWriteArrayList<SseEmitter>()

    /** Registreert een nieuwe browser-verbinding. */
    fun register(): SseEmitter {
        val emitter = SseEmitter(SSE_TIMEOUT_MS)
        emitter.onCompletion { emitters.remove(emitter) }
        emitter.onTimeout { emitters.remove(emitter) }
        emitter.onError { emitters.remove(emitter) }
        emitters.add(emitter)
        runCatching { emitter.send(SseEmitter.event().comment("connected")) }
            .onFailure { emitters.remove(emitter) }
        return emitter
    }

    override fun notifyChanged() {
        if (emitters.isEmpty()) {
            return
        }
        emitters.forEach { emitter ->
            runCatching { emitter.send(SseEmitter.event().name("changed").data("1")) }
                .onFailure {
                    emitters.remove(emitter)
                    runCatching { emitter.complete() }
                }
        }
    }

    private companion object {
        /** Lange timeout; de browser (EventSource) herverbindt automatisch als 'ie toch sluit. */
        private const val SSE_TIMEOUT_MS = 30L * 60L * 1000L
    }
}
