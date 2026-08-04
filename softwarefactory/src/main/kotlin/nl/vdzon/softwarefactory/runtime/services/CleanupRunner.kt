package nl.vdzon.softwarefactory.runtime.services

/**
 * Eén factory-brede opruimer, aanroepbaar buiten zijn eigen schema om (SF-1929). Elke implementatie
 * is de bestaande poller zelf: de `@Scheduled`-methode en de "Nu draaien"-knop komen zo op exact
 * dezelfde ronde uit, zonder tweede implementatie.
 *
 * De GitHub-cleanup zit hier bewust niet bij: die woont in de `maintenance`-module en is via
 * [nl.vdzon.softwarefactory.maintenance.MaintenanceCleanupApi] bereikbaar (`maintenance` mag niet
 * van `runtime` afhangen).
 */
interface CleanupRunner {
    /** De `kind`-waarde in de opruim-log (zie `CleanupKinds`). */
    val cleanupKind: String

    /** `false` = het mechanisme staat uit; ook de knop start dan niets. */
    fun cleanupEnabled(): Boolean

    /**
     * Eén ronde inclusief logregel, terwijl de aanroeper de dubbel-draaien-bescherming al vasthoudt.
     * [trigger] is `scheduled` of `manual` (zie `CleanupTriggers`).
     */
    fun runCleanupRoundLocked(trigger: String)
}
