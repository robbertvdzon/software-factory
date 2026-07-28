package nl.vdzon.softwarefactory.audit.types

/**
 * Uitkomst van een audit-agent-run. [ASKED] is als [DONE] een eindtoestand van de run: de auditor
 * stelde een blokkerende vraag en leverde geen rapport. De job wordt daarop terminaal gezet zodat de
 * audit-run gewoon kan sluiten; het antwoord plant later een nieuwe run in.
 */
enum class AuditOutcomeStatus { RUNNING, DONE, FAILED, ASKED }
