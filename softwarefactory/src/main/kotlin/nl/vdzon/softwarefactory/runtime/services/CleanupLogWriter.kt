package nl.vdzon.softwarefactory.runtime.services

import nl.vdzon.softwarefactory.maintenance.repositories.MaintenanceCleanupRunRepository
import nl.vdzon.softwarefactory.maintenance.repositories.NewMaintenanceCleanupRun
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime

/**
 * Schrijft een opruimronde van een factory-brede opruimer weg in de gedeelde opruim-log
 * (`maintenance_cleanup_runs`, zie V31), zodat het Opruimen-scherm niet alleen de nachtelijke
 * GitHub-cleanup toont maar élk mechanisme.
 *
 * Twee regels, allebei bewust:
 *
 *  * **Alleen bij verwijderingen of een fout.** De payload-purge hangt aan de completion-recovery
 *    (elke ~2 s) en de retentie-pollers draaien elk uur; zonder deze regel zou het scherm binnen een
 *    dag volstaan met lege rijen. De nachtelijke GitHub-cleanup houdt zijn eigen SF-1913-gedrag
 *    (élke ronde een rij, ook bij 0) — die draait één keer per dag en dáár is "niets opgeruimd"
 *    juist informatie.
 *  * **Fail-soft.** Een mislukte insert mag de opruiming zelf nooit laten falen; de ronde is dan al
 *    gedaan en het log is alleen zichtbaarheid.
 *
 * Bewust een gewone (`open`) component en geen poort-interface: de schrijvers zitten allemaal in
 * `runtime`, dat via de modulith-`allowedDependencies` rechtstreeks bij `maintenance :: repositories`
 * mag. `open` is er puur voor de test-fakes (deze repo heeft geen mock-framework).
 */
@Component
open class CleanupLogWriter(
    private val repository: MaintenanceCleanupRunRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Klokwaarde voor het `startedAt`-stempel; de schrijver noteert die vóór de ronde begint. */
    open fun now(): OffsetDateTime = OffsetDateTime.now(clock)

    open fun record(kind: String, startedAt: OffsetDateTime, itemsDeleted: Int, error: String? = null) {
        if (itemsDeleted <= 0 && error == null) return
        runCatching {
            repository.add(
                NewMaintenanceCleanupRun(
                    kind = kind,
                    startedAt = startedAt,
                    finishedAt = now(),
                    itemsDeleted = maxOf(itemsDeleted, 0),
                    error = error,
                ),
            )
        }.onFailure { logger.warn("Vastleggen van de opruimronde '{}' faalde.", kind, it) }
    }

    /** Vertaalt een fout naar de korte tekst die in de log-rij belandt. */
    open fun describe(failure: Throwable): String =
        failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
}
