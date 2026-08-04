package nl.vdzon.softwarefactory.runtime.models

import nl.vdzon.softwarefactory.maintenance.types.CleanupRunStatus

/**
 * Wat het verzoek opleverde: [status] is de samenvatting voor de melding in het scherm, [perKind]
 * vertelt per soort wat er gebeurde (relevant bij "alles draaien": wat is gestart, wat overgeslagen).
 */
data class CleanupRunNowOutcome(
    val status: CleanupRunStatus,
    val perKind: Map<String, CleanupRunStatus>,
)
