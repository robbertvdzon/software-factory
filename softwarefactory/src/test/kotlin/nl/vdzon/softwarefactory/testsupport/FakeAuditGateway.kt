package nl.vdzon.softwarefactory.testsupport

import nl.vdzon.softwarefactory.audit.AuditGateway
import nl.vdzon.softwarefactory.audit.models.AuditDispatchHandle
import nl.vdzon.softwarefactory.audit.models.AuditOutcome
import nl.vdzon.softwarefactory.audit.services.AuditJob

/** Minimale [AuditGateway]-stub voor dashboard-servicetests die geen echte audit-dispatch nodig hebben. */
class FakeAuditGateway(private val jobs: List<AuditJob> = emptyList()) : AuditGateway {
    override fun allJobs(): List<AuditJob> = jobs

    override fun startAudit(project: String, auditType: String): AuditDispatchHandle =
        error("FakeAuditGateway.startAudit niet ondersteund in deze test.")

    override fun auditOutcome(handle: AuditDispatchHandle): AuditOutcome =
        error("FakeAuditGateway.auditOutcome niet ondersteund in deze test.")
}
