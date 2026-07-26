package nl.vdzon.softwarefactory.audit

import nl.vdzon.softwarefactory.audit.models.AuditDispatchHandle
import nl.vdzon.softwarefactory.audit.models.AuditOutcome
import nl.vdzon.softwarefactory.audit.services.AuditJob

/**
 * Poort waarmee de [nl.vdzon.softwarefactory.audit.services.AuditScheduler] (in de `audit`-module)
 * de rest van de factory bereikt zonder er direct van af te hangen — zelfde patroon als
 * [nl.vdzon.softwarefactory.nightly.NightlyGateway]. De implementatie (`AuditGatewayAdapter`, in
 * `nl.vdzon.softwarefactory.dashboard.services`) delegeert naar de agent-runtime, de tracker en
 * het knowledge-domein.
 */
interface AuditGateway {

    /** Alle audits van alle projecten (uit `.factory/nightly/<audit>/job.yaml`). */
    fun allJobs(): List<AuditJob>

    /**
     * Dispatcht de auditor-agent voor deze (project, auditType) — geen tracker-story, alleen een
     * Docker-agent-run met read-only repo-toegang. Krijgt automatisch de laatste rapporten +
     * memory-tips als context mee (zie `AuditGatewayAdapter`).
     */
    fun startAudit(project: String, auditType: String): AuditDispatchHandle

    /**
     * Poll de uitkomst van een eerder gestarte run. Zolang de container nog draait: `RUNNING`
     * (geen side effects). Zodra de container gestopt is: leest het resultaat-bestand, upsert
     * memory-tips, stelt (indien voorgesteld) de vervolg-story voor met fase `start-next`, en
     * persisteert het rapport — dit gebeurt precies één keer (de aanroeper mag hierna niet meer
     * pollen zodra deze functie niet meer `RUNNING` teruggeeft).
     */
    fun auditOutcome(handle: AuditDispatchHandle): AuditOutcome
}
