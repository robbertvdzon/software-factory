package nl.vdzon.softwarefactory.audit.models

import nl.vdzon.softwarefactory.audit.types.AuditOutcomeStatus
import java.time.OffsetDateTime

/** Uitkomst van één lopende/afgeronde audit-agent-run (gepolld door [nl.vdzon.softwarefactory.audit.services.AuditScheduler]). */
data class AuditOutcome(
    val status: AuditOutcomeStatus,
    val startedAt: OffsetDateTime?,
    val endedAt: OffsetDateTime?,
    val costUsd: Double,
    /** Het rapport (tekst + evt. score) zodra de agent klaar is; null zolang [status] RUNNING is. */
    val report: AuditReportResult? = null,
    val error: String? = null,
    /** Bij [AuditOutcomeStatus.ASKED]: de opgeslagen vraag waar de audit op wacht. */
    val question: AuditQuestionResult? = null,
)

/** De vraag die een audit-run stelde i.p.v. een rapport te leveren — al gepersisteerd in `audit_question`. */
data class AuditQuestionResult(
    val questionId: Long,
    val question: String,
)

/** Wat de auditor-agent aan het eind van zijn run oplevert — al gepersisteerd als `audit_report`-rij. */
data class AuditReportResult(
    val reportId: Long,
    val content: String,
    val score: Double? = null,
    val scoreLabel: String? = null,
    /** Sleutel van de (hoogstens 1) voorgestelde vervolg-story, of null als er niets voorgesteld is. */
    val proposedStoryKey: String? = null,
)

/** Identiteit van een gedispatchte audit-agent-run — nodig om 'm op volgende ticks te pollen. */
data class AuditDispatchHandle(
    val containerName: String,
    val workspacePath: String?,
    val storyRunId: Long,
)
