package nl.vdzon.softwarefactory.audit.services

import nl.vdzon.softwarefactory.core.contracts.AuditQuestionAnswering
import org.springframework.stereotype.Component

/** Vult de [AuditQuestionAnswering]-poort in vanuit de audit-module zelf. */
@Component
class AuditQuestionAnsweringAdapter(
    private val auditScheduler: AuditScheduler,
) : AuditQuestionAnswering {
    override fun answerAuditQuestion(questionId: Long, answer: String): Boolean =
        auditScheduler.answerQuestion(questionId, answer)
}
