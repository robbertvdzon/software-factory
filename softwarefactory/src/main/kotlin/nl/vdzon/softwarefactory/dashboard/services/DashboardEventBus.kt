package nl.vdzon.softwarefactory.dashboard.services

import nl.vdzon.softwarefactory.core.contracts.ChangeNotifier
import nl.vdzon.softwarefactory.dashboard.DashboardChangeSource
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Houdt de open SSE-verbindingen van browsers bij en stuurt een "changed"-event zodra de
 * factory-state mogelijk is veranderd (vanuit [ChangeNotifier], aangeroepen door de poller en
 * door UI-mutaties). De browser ververst dan alleen z'n data-laag.
 *
 * De bridge (`bridge.BridgeClient`) abonneert zich hier met [addListener] om hetzelfde signaal
 * over alle verbonden bridge-sockets door te sturen — zo hoeft de bridge niet zelf ook
 * [ChangeNotifier] te implementeren (dat zou een tweede, dubbelzinnige bean van dat type geven).
 */
@Service
class DashboardEventBus : ChangeNotifier, DashboardChangeSource {
    private val emitters = CopyOnWriteArrayList<SseEmitter>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

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

    /** Registreert een extra ontvanger van "changed" (naast de SSE-emitters hierboven). */
    override fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    override fun notifyChanged() {
        listeners.forEach { listener -> runCatching { listener() } }
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
