package nl.vdzon.softwarefactory.web.controllers

/** Requestbodies van de dashboard-API (`/api/v1`). De antwoorden zijn de `dashboard :: models`-pagina's zelf. */

data class CreateStoryRequest(
    val projectKey: String? = null,
    val title: String,
    val description: String? = null,
    val repo: String? = null,
    val aiSupplier: String? = null,
    val aiModel: String? = null,
    val start: Boolean = false,
    val questionsAllowed: Boolean = true,
    // SF-1959 — zonder expliciete waarde is een story nooit een hotfix.
    val hotfix: Boolean = false,
    val approvalMode: String = "automatisch",
    val notificationEvents: Set<String> = setOf("DEPLOYED", "QUESTION", "MANUAL_ACTION_REQUIRED", "ERROR"),
)

data class EditStoryRequest(
    val description: String? = null,
    val descriptionSummary: String? = null,
    val aiSupplier: String? = null,
    val aiModel: String? = null,
)
data class PhaseRequest(val phase: String, val comment: String? = null)
data class QuestionsAllowedRequest(val enabled: Boolean)
data class ModeRequest(val mode: String)
data class NotificationEventsRequest(val notificationEvents: Set<String>)
data class CommandRequest(val reason: String? = null)
data class AuditMemoryNoteRequest(val project: String, val auditType: String, val key: String, val content: String)
data class AuditMemoryNoteKeyRequest(val project: String, val auditType: String, val key: String)
data class AuditRunNowRequest(val project: String, val auditType: String)

/** `kind` = een `CleanupKinds`-waarde of de "alles"-waarde `all` (SF-1929). */
data class MaintenanceRunNowRequest(val kind: String)
data class AuditAnswerRequest(val questionId: Long, val answer: String)
data class AuditProjectSettingsSaveRequest(val project: String, val startTime: String, val auditCount: Int)
data class AuditSettingsSaveRequest(val enabled: Boolean, val projects: List<AuditProjectSettingsSaveRequest>)
data class AgentExecutionConfigSaveRequest(
    val role: String,
    val projectKey: String? = null,
    val vendorId: String,
    val model: String,
    val mode: String,
)

/** De volledige projectcatalogus als YAML (zelfde vorm als het vroegere `projects.yaml`). */
data class ProjectCatalogSaveRequest(val yaml: String)

/** Bevestiging van een uitgevoerde actie zonder eigen resultaat. */
data class Ack(val ok: Boolean = true)
