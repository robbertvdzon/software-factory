package nl.vdzon.softwarefactory.maintenance.types

/**
 * Uitkomst van een verzoek om een opruimronde te draaien (SF-1929). Volgt het `audit.runNow`-
 * precedent ([nl.vdzon.softwarefactory.audit.types.ManualAuditResult]): geen foutcode voor een
 * geweigerd verzoek, maar een status die het scherm kan vertalen.
 */
enum class CleanupRunStatus {
    /** De ronde is gestart (handmatig: op de achtergrond). */
    STARTED,

    /** Dezelfde soort draait al — handmatig óf via het schema; er komt geen tweede ronde bij. */
    ALREADY_RUNNING,

    /** Het mechanisme staat uit (`SF_*_ENABLED`); de knop doet bewust niets, en zegt dat ook. */
    DISABLED,

    /** Onbekende `kind`-waarde. */
    UNKNOWN_KIND,
    ;

    /** Wire-vorm richting dashboard/frontend, net als `AuditRunNowResult.status`. */
    val wireValue: String get() = name.lowercase()
}
