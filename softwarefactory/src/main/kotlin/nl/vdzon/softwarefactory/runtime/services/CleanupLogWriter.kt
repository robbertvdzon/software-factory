package nl.vdzon.softwarefactory.runtime.services

import nl.vdzon.softwarefactory.maintenance.CleanupRunGuard
import nl.vdzon.softwarefactory.maintenance.types.CleanupRunStatus
import nl.vdzon.softwarefactory.maintenance.repositories.CleanupTriggers
import nl.vdzon.softwarefactory.maintenance.repositories.MaintenanceCleanupRunRepository
import nl.vdzon.softwarefactory.maintenance.repositories.NewMaintenanceCleanupRun
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime

/**
 * Voert een opruimronde van een factory-brede opruimer uit en schrijft die weg in de gedeelde
 * opruim-log (`maintenance_cleanup_runs`, zie V31/V32), zodat het Opruimen-scherm niet alleen de
 * nachtelijke GitHub-cleanup toont maar élk mechanisme.
 *
 * Drie regels, allemaal bewust:
 *
 *  * **Geplande rondes alleen bij verwijderingen of een fout.** De payload-purge hangt aan de
 *    completion-recovery (elke ~2 s) en de retentie-pollers draaien elk uur; zonder deze regel zou
 *    het scherm binnen een dag volstaan met lege rijen. Een *handmatige* ronde levert daarentegen
 *    altijd een rij op — anders zie je na een klik op "Nu draaien" niets terug (SF-1929). De
 *    nachtelijke GitHub-cleanup houdt zijn eigen SF-1913-gedrag (élke ronde een rij, ook bij 0).
 *  * **Eén ronde per soort tegelijk.** [runGuarded] deelt de [CleanupRunGuard] met de handmatige
 *    route, zodat een geplande tick wordt overgeslagen zolang een handmatige ronde loopt (en andersom).
 *  * **Fail-soft.** Een mislukte insert mag de opruiming zelf nooit laten falen; de ronde is dan al
 *    gedaan en het log is alleen zichtbaarheid.
 *
 * Bewust een gewone (`open`) component en geen poort-interface: de schrijvers zitten allemaal in
 * `runtime`, dat via de modulith-`allowedDependencies` rechtstreeks bij `maintenance` en
 * `maintenance :: repositories` mag. `open` is er puur voor de test-fakes (deze repo heeft geen
 * mock-framework).
 */
@Component
open class CleanupLogWriter(
    private val repository: MaintenanceCleanupRunRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val guard: CleanupRunGuard = CleanupRunGuard.inMemory(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Klokwaarde voor het `startedAt`-stempel; de schrijver noteert die vóór de ronde begint. */
    open fun now(): OffsetDateTime = OffsetDateTime.now(clock)

    /**
     * Het geplande pad: enabled-check, dubbel-draaien-bescherming en logregel om [round] heen.
     * Geeft terug wat er gebeurd is, zodat dezelfde regels ook gelden voor een handmatig verzoek.
     */
    open fun runGuarded(
        kind: String,
        trigger: String,
        enabled: Boolean,
        round: () -> Int,
    ): CleanupRunStatus = when {
        !enabled -> CleanupRunStatus.DISABLED
        guard.withKind(kind) { runLocked(kind, trigger, round) } == null -> {
            logger.info("Opruimronde '{}' overgeslagen: er draait er al een.", kind)
            CleanupRunStatus.ALREADY_RUNNING
        }
        else -> CleanupRunStatus.STARTED
    }

    /** Eén ronde mét logregel, terwijl de aanroeper de bewaking al vasthoudt (de handmatige route). */
    open fun runLocked(kind: String, trigger: String, round: () -> Int) {
        val startedAt = now()
        runCatching(round)
            .onSuccess { record(kind, startedAt, it, trigger = trigger) }
            .onFailure {
                logger.warn("Opruimronde '{}' faalde.", kind, it)
                record(kind, startedAt, 0, describe(it), trigger)
            }
    }

    open fun record(
        kind: String,
        startedAt: OffsetDateTime,
        itemsDeleted: Int,
        error: String? = null,
        trigger: String = CleanupTriggers.SCHEDULED,
    ) {
        if (trigger != CleanupTriggers.MANUAL && itemsDeleted <= 0 && error == null) return
        runCatching {
            repository.add(
                NewMaintenanceCleanupRun(
                    kind = kind,
                    startedAt = startedAt,
                    finishedAt = now(),
                    itemsDeleted = maxOf(itemsDeleted, 0),
                    error = error,
                    trigger = trigger,
                ),
            )
        }.onFailure { logger.warn("Vastleggen van de opruimronde '{}' faalde.", kind, it) }
    }

    /** Vertaalt een fout naar de korte tekst die in de log-rij belandt. */
    open fun describe(failure: Throwable): String =
        failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
}
