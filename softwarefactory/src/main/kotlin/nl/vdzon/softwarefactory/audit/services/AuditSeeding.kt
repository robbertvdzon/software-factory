package nl.vdzon.softwarefactory.audit.services

import nl.vdzon.softwarefactory.audit.repositories.AuditRunJobRecord
import nl.vdzon.softwarefactory.audit.repositories.AuditRunKind
import java.time.OffsetDateTime

/**
 * Pure keuze-regels rond het seeden van een project binnen één audit-run — afgesplitst van
 * [AuditScheduler] (die de DB/gateway erbij haalt) zodat ze zonder DB te testen zijn, zelfde
 * plan/uitvoer-scheiding als [AuditPlanner].
 *
 * Het hele bestaan van deze regels komt van "Run now": zo'n handmatige job hangt aan dezelfde run
 * als de geplande audits, en mag de geplande ronde van dat project niet verstoren.
 */
object AuditSeeding {

    /**
     * Is dit project al geseed in de huidige run? Alleen geplande jobs tellen mee — een handmatige
     * "Run now" op project X mag de geplande audits van X voor die dag niet overslaan.
     *
     * Uitzondering: hangen álle enabled audits van X al (handmatig) in deze run, dan valt er niets
     * meer te seeden en telt het project alsnog als geseed. Zonder die uitzondering bleef X eeuwig
     * "nog te seeden" en zou de run nooit eindigen.
     */
    fun isSeeded(projectJobs: List<AuditRunJobRecord>, enabledAudits: List<AuditJob>): Boolean {
        return when {
            projectJobs.isEmpty() -> false
            projectJobs.any { it.kind == AuditRunKind.SCHEDULED } -> true
            else -> {
                val inRun = projectJobs.map { it.auditType }.toSet()
                enabledAudits.all { it.name in inRun }
            }
        }
    }

    /**
     * De [count] audits van dit project die als eerste aan de beurt zijn: oudste rapport eerst (nooit
     * gedraaid = allereerst), en audits die al in deze run hangen (bv. via "Run now") overgeslagen
     * zodat ze niet dubbel worden ingeplanned.
     */
    fun toSeed(
        enabledAudits: List<AuditJob>,
        projectJobs: List<AuditRunJobRecord>,
        count: Int,
        lastGeneratedAt: (AuditJob) -> OffsetDateTime?,
    ): List<AuditJob> {
        val inRun = projectJobs.map { it.auditType }.toSet()
        return enabledAudits
            .filter { it.name !in inRun }
            .sortedBy { lastGeneratedAt(it) ?: OffsetDateTime.MIN }
            .take(count)
    }
}
