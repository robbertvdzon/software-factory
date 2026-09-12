package nl.vdzon.softwarefactory.dashboard.services

import nl.vdzon.softwarefactory.core.contracts.ChangeNotifier
import nl.vdzon.softwarefactory.dashboard.DashboardChangeSource
import org.springframework.stereotype.Service
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Verzamelpunt voor "de factory-state is mogelijk veranderd" (vanuit [ChangeNotifier], aangeroepen
 * door de poller en door UI-mutaties). Luisteraars, zoals het SSE-kanaal van de dashboard-API,
 * abonneren zich met [addListener]; de bus zelf kent geen transport.
 */
@Service
class DashboardEventBus : ChangeNotifier, DashboardChangeSource {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    override fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    override fun notifyChanged() {
        listeners.forEach { listener -> runCatching { listener() } }
    }
}
