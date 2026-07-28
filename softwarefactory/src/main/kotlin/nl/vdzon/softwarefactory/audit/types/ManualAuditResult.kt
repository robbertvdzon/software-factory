package nl.vdzon.softwarefactory.audit.types

/**
 * Uitkomst van een "Run now"-verzoek (zie
 * [nl.vdzon.softwarefactory.audit.services.AuditScheduler.startManualAudit]). Meer dan een boolean,
 * omdat het dashboard drie situaties uit elkaar moet kunnen houden: nu gestart, achter een lopende
 * run in de wachtrij, of niets gedaan (en waarom).
 */
enum class ManualAuditResult {
    /** Er liep niets: een nieuwe MANUAL-run is aangemaakt, de audit start op de eerstvolgende tick. */
    STARTED,

    /** Toegevoegd aan de lopende run; start zodra dit project geen andere audit meer heeft draaien. */
    QUEUED,

    /** Deze audit wacht of draait al in de lopende run — niets toegevoegd. */
    ALREADY_QUEUED,

    /** Geen audit met deze (project, auditType) geconfigureerd onder `.factory/nightly` — niets toegevoegd. */
    UNKNOWN_AUDIT,
    ;

    /** Verzoek geaccepteerd: de audit draait nu of staat in de wachtrij. */
    val accepted: Boolean get() = this == STARTED || this == QUEUED
}
