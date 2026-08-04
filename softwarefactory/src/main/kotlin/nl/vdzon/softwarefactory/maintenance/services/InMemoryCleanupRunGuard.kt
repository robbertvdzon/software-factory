package nl.vdzon.softwarefactory.maintenance.services

import nl.vdzon.softwarefactory.maintenance.CleanupRunGuard
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * De enige implementatie van [CleanupRunGuard]: een set met de soorten die nu draaien, in het
 * geheugen van de factory-JVM. Zie de poort voor waarom dit geen database-lock is.
 */
@Component
class InMemoryCleanupRunGuard : CleanupRunGuard {
    private val running = ConcurrentHashMap.newKeySet<String>()

    override fun tryStart(kind: String): Boolean = running.add(kind)

    override fun finish(kind: String) {
        running.remove(kind)
    }

    /** Vaste (alfabetische) volgorde: het scherm vergelijkt deze lijst tussen pollrondes. */
    override fun runningKinds(): List<String> = running.sorted()
}
