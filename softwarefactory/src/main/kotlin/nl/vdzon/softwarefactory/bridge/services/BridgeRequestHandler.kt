package nl.vdzon.softwarefactory.bridge.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.contract.BridgeError
import nl.vdzon.softwarefactory.contract.BridgeRequest
import nl.vdzon.softwarefactory.contract.BridgeResponse
import nl.vdzon.softwarefactory.contract.ProductFactoryAttachmentNames
import nl.vdzon.softwarefactory.core.contracts.ApprovalMode
import nl.vdzon.softwarefactory.core.contracts.NotificationEvent
import nl.vdzon.softwarefactory.core.contracts.FactoryCommand
import nl.vdzon.softwarefactory.core.contracts.FactoryOperations
import nl.vdzon.softwarefactory.core.contracts.TesterScreenshots
import nl.vdzon.softwarefactory.core.contracts.TelegramAssistantApi
import nl.vdzon.softwarefactory.dashboard.models.AuditProjectSettingsSaveInput
import nl.vdzon.softwarefactory.dashboard.models.CleanupRunNowResult
import nl.vdzon.softwarefactory.dashboard.models.WorkflowRunInfo
import nl.vdzon.softwarefactory.dashboard.models.CreateStoryCommand
import nl.vdzon.softwarefactory.dashboard.models.ProductFactoryStoryFilter
import nl.vdzon.softwarefactory.dashboard.DashboardCommands
import nl.vdzon.softwarefactory.dashboard.DashboardQueries
import nl.vdzon.softwarefactory.dashboard.FactoryProcessControl
import nl.vdzon.softwarefactory.tracker.AttachmentPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.Base64
import java.util.HexFormat
import java.security.MessageDigest

/**
 * Vertaalt een binnengekomen [BridgeRequest] naar een aanroep op de bestaande
 * [DashboardQueryService]/[FactoryOperationsService] (zie docs/ontwerp-bridge-dashboard.md §5,
 * operatie-catalogus) en verpakt het resultaat weer in een [BridgeResponse]. Uitsluitend vertalen
 * en delegeren — géén nieuwe businesslogica hier (behalve `downloads.list`, zie
 * [DashboardQueryService.downloads]/[DashboardQueryService.builds]).
 */
@Component
class BridgeRequestHandler(
    private val dashboardService: DashboardQueries,
    private val dashboardCommands: DashboardCommands,
    private val operations: FactoryOperations,
    private val processService: FactoryProcessControl,
    private val issueTrackerClient: AttachmentPort,
    private val assistantService: TelegramAssistantApi,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val storyAttachmentBridge = StoryAttachmentBridge(dashboardService, issueTrackerClient)

    fun handle(request: BridgeRequest): BridgeResponse =
        try {
            val body = router.dispatch(request.operation, request.params)
            BridgeResponse(id = request.id, ok = true, body = objectMapper.valueToTree(body))
        } catch (unknown: UnknownOperationException) {
            BridgeResponse(
                id = request.id,
                ok = false,
                error = BridgeError(code = "UNKNOWN_OPERATION", message = unknown.message.orEmpty()),
            )
        } catch (tooLarge: BridgePayloadTooLargeException) {
            BridgeResponse(id = request.id, ok = false, error = BridgeError(code = "TOO_LARGE", message = tooLarge.message.orEmpty()))
        } catch (conflict: AttachmentConflictException) {
            BridgeResponse(id = request.id, ok = false, error = BridgeError(code = "CONFLICT", message = conflict.message.orEmpty()))
        } catch (notFound: NotFoundException) {
            BridgeResponse(id = request.id, ok = false, error = BridgeError(code = "NOT_FOUND", message = notFound.message.orEmpty()))
        } catch (invalid: IllegalArgumentException) {
            BridgeResponse(id = request.id, ok = false, error = BridgeError(code = "INVALID_PARAMS", message = invalid.message.orEmpty()))
        } catch (exception: Exception) {
            logger.warn("Bridge-operatie '{}' faalde: {}", request.operation, exception.message)
            BridgeResponse(
                id = request.id,
                ok = false,
                error = BridgeError(code = "INTERNAL_ERROR", message = exception.message ?: "Onbekende fout"),
            )
        }

    private val router = OperationRouter()

    // De routering leeft in een eigen (inner) klasse: één grote when overschreed de
    // LongMethod/complexity-poort van de quality-ratchet, en de opdeling in vier deel-routers
    // hoort als samenhangend geheel bij elkaar — niet los tussen de handler-helpers. De
    // groepering volgt de bestaande indeling (story-reads, overzicht-reads, story-acties,
    // systeem-acties); een onbekende operatie valt door alle vier heen.
    private inner class OperationRouter {
        fun dispatch(operation: String, params: JsonNode?): Any =
            dispatchStoryRead(operation, params)
                ?: dispatchOverviewRead(operation, params)
                ?: dispatchStoryAction(operation, params)
                ?: dispatchSystemAction(operation, params)
                ?: throw UnknownOperationException("Onbekende operatie: $operation")

        private fun dispatchStoryRead(operation: String, params: JsonNode?): Any? =
            when (operation) {
                "dashboard.get" -> dashboardService.dashboard()
                "stories.list" -> dashboardService.stories()
                "story.detail" -> dashboardService.storyDetail(params.require("storyKey"))
                "productFactory.stories" -> dashboardService.productFactoryStories(
                    ProductFactoryStoryFilter(
                        storyKey = params.optional("storyKey"),
                        productId = params.optional("productId"),
                        idempotencyKey = params.optional("idempotencyKey"),
                        packageSha256 = params.optional("packageSha256"),
                        status = params.optional("status"),
                    ),
                )
                "story.screenshots" -> screenshotMetadata(params.require("storyKey"))
                "screenshot.get" -> screenshotBody(params.require("storyKey"), params.require("attachmentId"))
                "productFactoryAttachment.get" -> productFactoryAttachmentBody(
                    params.require("storyKey"),
                    params.require("attachmentId"),
                )
                "myActions.list" -> dashboardService.myActions()
                "myActions.count" -> MyActionsCountBody(dashboardService.myActionsCount())
                "agents.list" -> dashboardService.agents()
                "agent.log" -> dashboardService.agentLog(params.requireLong("agentRunId"))
                else -> null
            }

        private fun dispatchOverviewRead(operation: String, params: JsonNode?): Any? =
            when (operation) {
                "projects.list" -> dashboardService.projectsOverview(force = params.optionalBool("force") ?: false)
                "projects.branchTimeline" -> dashboardService.branchTimelineFor(params.require("name"))
                "projects.branchTimelineForMergedPr" ->
                    dashboardService.branchTimelineForMergedPr(params.require("name"), params.requireLong("prNumber").toInt())
                "projects.buildHistory" -> dashboardService.buildHistoryFor(
                    params.require("name"),
                    params.require("branch"),
                    params.optional("page")?.toIntOrNull() ?: 1,
                    params.optional("perPage")?.toIntOrNull() ?: 4,
                )
                "projects.recentCommits" -> dashboardService.recentCommits()
                "changelog.for" -> dashboardService.changelogFor(params.require("name"))
                "audit.memory" -> dashboardService.auditMemory()
                "audit.questions" -> dashboardService.auditQuestions()
                "audit.questions.count" -> AuditQuestionCountBody(dashboardService.auditQuestions().questions.size)
                "audit.overview" -> dashboardService.auditOverview()
                "audit.reportsList" -> dashboardService.auditReportsFor(params.require("project"), params.require("auditType"))
                "audit.reportDetail" -> dashboardService.auditReportDetail(params.requireLong("reportId"))
                "maintenance.cleanupsList" -> dashboardService.maintenanceCleanups(params.optional("project"), params.optional("kind"))
                "maintenance.cleanupDetail" -> dashboardService.maintenanceCleanupDetail(params.requireLong("id"))
                    ?: throw NotFoundException("Maintenance-cleanup-run ${params.require("id")} niet gevonden.")
                "settings.get" -> dashboardService.settings(params.require("username"))
                "downloads.list" -> dashboardService.downloads(force = params.optionalBool("force") ?: false)
                "builds.list" -> dashboardService.builds(force = params.optionalBool("force") ?: false)
                "builds.runs" -> BuildsRunsBody(dashboardService.buildsFor(params.require("owner"), params.require("repo")))
                "assistant.status" -> assistantService.status()
                else -> null
            }

        private fun dispatchStoryAction(operation: String, params: JsonNode?): Any? =
            when (operation) {
                "story.create" -> dashboardCommands.createStory(CreateStoryCommand(
                    // SF-818 — projectKey is optioneel: het dialoog stuurt 'm niet meer mee en de service
                    // valt terug op het enige geconfigureerde project.
                    projectKey = params.optional("projectKey"),
                    title = params.require("title"),
                    description = params.optional("description"),
                    repo = params.optional("repo"),
                    aiSupplier = params.optional("aiSupplier"),
                    aiModel = params.optional("aiModel"),
                    start = params.optionalBool("start") ?: false,
                    questionsAllowed = params.optionalBool("questionsAllowed") ?: true,
                    hotfix = params.optionalBool("hotfix") ?: false,
                    approvalMode = params.optional("approvalMode") ?: ApprovalMode.AUTOMATIC.trackerValue,
                    notificationEvents = params.optionalStrings("notificationEvents")
                        ?.let(NotificationEvent::parse) ?: NotificationEvent.DEFAULT,
                ))
                "story.attachment.put" -> storyAttachmentBridge.put(
                    storyKey = params.require("storyKey"),
                    name = params.require("name"),
                    mediaType = params.require("mediaType"),
                    expectedSha = params.require("sha256"),
                    encoded = params.require("base64"),
                )
                "story.setStoryPhase" -> {
                    operations.setStoryPhase(params.require("storyKey"), params.require("phase"), params.optional("comment"))
                    Ack
                }
                "subtask.setPhase" -> {
                    operations.setSubtaskPhase(params.require("subtaskKey"), params.require("phase"), params.optional("comment"))
                    Ack
                }
                "story.setQuestionsAllowed" -> {
                    dashboardCommands.setQuestionsAllowedFlag(params.require("storyKey"), params.requireBool("enabled"))
                    Ack
                }
                "story.setApprovalMode" -> {
                    dashboardCommands.setApprovalMode(params.require("storyKey"), params.require("mode"))
                    Ack
                }
                "story.setNotificationEvents" -> {
                    dashboardCommands.setNotificationEvents(
                        params.require("storyKey"),
                        params.requireStrings("notificationEvents").toSet(),
                    )
                    Ack
                }
                "story.edit" -> {
                    dashboardCommands.editStory(
                        params.require("storyKey"),
                        description = params.optional("description"),
                        descriptionSummary = params.optional("descriptionSummary"),
                        aiSupplier = params.optional("aiSupplier"),
                        aiModel = params.optional("aiModel"),
                    )
                    Ack
                }
                "story.command" -> {
                    operations.queueCommand(params.require("storyKey"), parseCommand(params), params.optional("reason"))
                    Ack
                }
                "story.purge" -> {
                    dashboardCommands.purgeStory(params.require("storyKey"))
                    Ack
                }
                "story.startRefining" -> {
                    dashboardCommands.startRefining(params.require("storyKey"))
                    Ack
                }
                "story.queue" -> {
                    dashboardCommands.queueStory(params.require("storyKey"))
                    Ack
                }
                "story.startDeveloping" -> {
                    dashboardCommands.startDeveloping(params.require("storyKey"))
                    Ack
                }
                else -> null
            }

        private fun dispatchSystemAction(operation: String, params: JsonNode?): Any? =
            when (operation) {
                "audit.memory.update" -> {
                    dashboardCommands.updateAuditMemoryNote(
                        params.require("project"),
                        params.require("auditType"),
                        params.require("key"),
                        params.require("content"),
                    )
                    Ack
                }
                "audit.memory.delete" -> {
                    dashboardCommands.deleteAuditMemoryNote(params.require("project"), params.require("auditType"), params.require("key"))
                    Ack
                }
                "audit.runNow" ->
                    dashboardCommands.runAuditNow(params.require("project"), params.require("auditType"))
                        .let { AuditRunNowBody(it.accepted, it.status) }
                "audit.question.answer" ->
                    AuditAnswerBody(
                        dashboardCommands.answerAuditQuestion(params.requireLong("questionId"), params.require("answer")),
                    )
                "audit.settings.save" -> {
                    dashboardCommands.saveAuditSettings(params.requireBool("enabled"), params.auditProjectSettingsList())
                    Ack
                }
                "project.forceDeploy" -> {
                    dashboardCommands.forceProjectDeploy(params.require("name"))
                    Ack
                }
                "maintenance.runNow" -> cleanupRunNowBody(dashboardCommands.runCleanupNow(params.require("kind")))
                "workspace.openInIde" -> OpenWorkspaceBody(dashboardCommands.openWorkspaceInIntellij(params.require("storyKey")))
                "factory.restart" -> {
                    processService.requestRestart()
                    Ack
                }
                "factory.stop" -> {
                    processService.requestStop()
                    Ack
                }
                else -> null
            }

        private fun cleanupRunNowBody(result: CleanupRunNowResult) =
            CleanupRunNowBody(result.accepted, result.status, result.kinds)

        private fun parseCommand(params: JsonNode?): FactoryCommand =
            FactoryCommand.entries.firstOrNull { it.token == params.require("command") }
                ?: throw IllegalArgumentException("Onbekend command: ${params.optional("command")}")
    }

    private fun screenshotMetadata(storyKey: String): ScreenshotListBody {
        val attachments = issueTrackerClient.listIssueAttachments(storyKey)
            .filter { it.name.startsWith(TesterScreenshots.ATTACHMENT_PREFIX) }
            .map { ScreenshotInfo(id = it.id, name = it.name, size = it.size, createdAt = it.created, mimeType = it.mimeType) }
        return ScreenshotListBody(attachments)
    }

    private fun screenshotBody(storyKey: String, attachmentId: String): ScreenshotBody {
        val attachment = issueTrackerClient.listIssueAttachments(storyKey).firstOrNull { it.id == attachmentId }
            ?: throw NotFoundException("Attachment $attachmentId niet gevonden op $storyKey.")
        if ((attachment.size ?: 0L) > MAX_SCREENSHOT_BYTES) {
            throw BridgePayloadTooLargeException("Screenshot ${attachment.name} is groter dan de limiet van ${MAX_SCREENSHOT_BYTES / (1024 * 1024)}MB.")
        }
        val bytes = issueTrackerClient.downloadAttachmentBytes(attachment)
            ?: throw NotFoundException("Kon attachment $attachmentId niet downloaden.")
        return ScreenshotBody(
            id = attachment.id,
            name = attachment.name,
            mimeType = attachment.mimeType,
            base64 = Base64.getEncoder().encodeToString(bytes),
        )
    }

    private fun productFactoryAttachmentBody(storyKey: String, attachmentId: String): AttachmentBody {
        val attachment = issueTrackerClient.listIssueAttachments(storyKey).firstOrNull {
            it.id == attachmentId && ProductFactoryAttachmentNames.parse(it.name) != null
        } ?: throw NotFoundException("Product Factory-attachment $attachmentId niet gevonden op $storyKey.")
        val bytes = issueTrackerClient.downloadAttachmentBytes(attachment)
            ?: throw NotFoundException("Kon Product Factory-attachment $attachmentId niet downloaden.")
        return AttachmentBody(
            id = attachment.id,
            name = ProductFactoryAttachmentNames.parse(attachment.name)?.fileName ?: attachment.name,
            mimeType = attachment.mimeType,
            base64 = Base64.getEncoder().encodeToString(bytes),
        )
    }

    private fun JsonNode?.require(field: String): String =
        this?.path(field)?.takeIf { it.isTextual }?.asText()
            ?: throw IllegalArgumentException("Ontbrekend of ongeldig veld '$field' in params.")

    private fun JsonNode?.optional(field: String): String? =
        this?.path(field)?.takeIf { it.isTextual }?.asText()

    private fun JsonNode?.requireLong(field: String): Long =
        this?.path(field)?.takeIf { it.isTextual }?.asText()?.toLongOrNull()
            ?: throw IllegalArgumentException("Ontbrekend of ongeldig veld '$field' in params.")

    private fun JsonNode?.requireBool(field: String): Boolean =
        this?.path(field)?.takeIf { it.isBoolean }?.asBoolean()
            ?: throw IllegalArgumentException("Ontbrekend of ongeldig veld '$field' in params.")

    private fun JsonNode?.optionalBool(field: String): Boolean? {
        val value = this?.get(field) ?: return null
        if (value.isBoolean) return value.asBoolean()
        throw IllegalArgumentException("Ongeldig veld '$field' in params: JSON-boolean verwacht.")
    }

    /** Batch-rijen uit de "audits per project"-tabel (Settings, één gezamenlijke save-actie). */
    private fun JsonNode?.auditProjectSettingsList(): List<AuditProjectSettingsSaveInput> {
        val node = this?.path("projects")?.takeIf { it.isArray } ?: return emptyList()
        return node.map {
            AuditProjectSettingsSaveInput(
                project = it.path("project").asText(),
                startTime = it.path("startTime").asText(),
                auditCount = it.path("auditCount").asInt(),
            )
        }
    }

    private object Ack {
        @Suppress("unused")
        val ok = true
    }

    private data class MyActionsCountBody(val count: Int)
    private data class ScreenshotInfo(val id: String, val name: String, val size: Long?, val createdAt: Long?, val mimeType: String?)
    private data class ScreenshotListBody(val screenshots: List<ScreenshotInfo>)
    private data class ScreenshotBody(val id: String, val name: String, val mimeType: String?, val base64: String)
    private data class AttachmentBody(val id: String, val name: String, val mimeType: String?, val base64: String)
    private data class OpenWorkspaceBody(val path: String)
    /**
     * [started] betekent "verzoek geaccepteerd" (gestart óf in de wachtrij) en blijft zo staan voor
     * oudere frontends; [status] onderscheidt "gestart", "in de wachtrij" en de weigeringsredenen
     * (zie `AuditRunNowResult`).
     */
    private data class AuditRunNowBody(val started: Boolean, val status: String)

    /**
     * Uitkomst van "Nu draaien" op het Opruimen-scherm: [started] = er draait nu een ronde,
     * [status] is de samenvattende reden en [kinds] geeft per soort wat er gebeurde (voor "alles").
     */
    private data class CleanupRunNowBody(val started: Boolean, val status: String, val kinds: Map<String, String>)

    /** `false` als de vraag niet meer openstond — dubbele submit, of al via Telegram beantwoord. */
    private data class AuditAnswerBody(val answered: Boolean)

    /** Aantal openstaande auditvragen — voedt het badge-bolletje op het Audits-nav-item. */
    private data class AuditQuestionCountBody(val count: Int)
    private data class BuildsRunsBody(val runs: List<WorkflowRunInfo>)

    private class UnknownOperationException(message: String) : Exception(message)

    private companion object {
        /** Zelfde orde-grootte als een gemiddelde tester-screenshot; voorkomt gigantische socket-frames. */
        const val MAX_SCREENSHOT_BYTES = 8L * 1024 * 1024
    }
}

private class StoryAttachmentBridge(
    private val dashboardService: DashboardQueries,
    private val issueTrackerClient: AttachmentPort,
) {
    fun put(
        storyKey: String,
        name: String,
        mediaType: String,
        expectedSha: String,
        encoded: String,
    ): StoryAttachmentPutBody {
        val normalizedMediaType = mediaType.lowercase()
        val normalizedSha = expectedSha.lowercase()
        validateMetadata(name, normalizedMediaType, normalizedSha)
        val bytes = decode(name, encoded)
        require(sha256(bytes) == normalizedSha) { "Attachment $name heeft niet de opgegeven SHA-256." }
        if (dashboardService.storyDetail(storyKey).issue == null) {
            throw NotFoundException("Story $storyKey niet gevonden.")
        }
        return existing(storyKey, name, normalizedMediaType, normalizedSha, bytes)
            ?: upload(storyKey, name, normalizedMediaType, normalizedSha, bytes)
    }

    private fun validateMetadata(name: String, mediaType: String, expectedSha: String) {
        val invalidName = name.isBlank() || name.contains("..") ||
            name.contains('/') || name.contains('\\') || name.any(Char::isISOControl)
        require(!invalidName) { "Ongeldige attachmentnaam." }
        require(mediaType.isNotBlank()) { "Attachment-MIME-type is verplicht." }
        require(expectedSha.matches(SHA_256)) { "Ongeldige attachment-SHA-256." }
    }

    private fun decode(name: String, encoded: String): ByteArray {
        return decodeBase64(name, encoded)
    }

    private fun decodeBase64(name: String, encoded: String): ByteArray =
        runCatching { Base64.getDecoder().decode(encoded) }
            .getOrElse { throw IllegalArgumentException("Ongeldige Base64 voor attachment $name.") }

    private fun existing(
        storyKey: String,
        name: String,
        mediaType: String,
        expectedSha: String,
        bytes: ByteArray,
    ): StoryAttachmentPutBody? {
        val attachment = issueTrackerClient.listIssueAttachments(storyKey).firstOrNull { it.name == name }
            ?: return null
        val existingBytes = issueTrackerClient.downloadAttachmentBytes(attachment)
            ?: throw AttachmentConflictException("Bestaand attachment $name kan niet worden gecontroleerd.")
        if (sha256(existingBytes) != expectedSha) {
            throw AttachmentConflictException("Attachment $name bestaat al met andere inhoud.")
        }
        return StoryAttachmentPutBody(
            attachment.id,
            attachment.name,
            attachment.mimeType ?: mediaType,
            attachment.size ?: bytes.size.toLong(),
            expectedSha,
            false,
        )
    }

    private fun upload(
        storyKey: String,
        name: String,
        mediaType: String,
        expectedSha: String,
        bytes: ByteArray,
    ): StoryAttachmentPutBody {
        val uploaded = issueTrackerClient.uploadIssueAttachment(storyKey, name, mediaType, bytes)
        return StoryAttachmentPutBody(
            uploaded.id,
            uploaded.name,
            uploaded.mimeType ?: mediaType,
            uploaded.size ?: bytes.size.toLong(),
            expectedSha,
            true,
        )
    }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private companion object {
        val SHA_256 = Regex("[a-f0-9]{64}")
    }
}

private data class StoryAttachmentPutBody(
    val id: String,
    val name: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
    val created: Boolean,
)

private class BridgePayloadTooLargeException(message: String) : Exception(message)
private class AttachmentConflictException(message: String) : Exception(message)
private class NotFoundException(message: String) : Exception(message)

private fun JsonNode?.optionalStrings(field: String): List<String>? {
    val node = this?.get(field) ?: return null
    if (!node.isArray || node.any { !it.isTextual }) {
        throw IllegalArgumentException("Ongeldig veld '$field' in params: JSON-array met strings verwacht.")
    }
    return node.map { it.asText() }
}

private fun JsonNode?.requireStrings(field: String): List<String> =
    optionalStrings(field)
        ?: throw IllegalArgumentException("Ontbrekend of ongeldig veld '$field' in params.")
