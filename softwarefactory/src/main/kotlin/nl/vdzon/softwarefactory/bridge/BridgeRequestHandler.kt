package nl.vdzon.softwarefactory.bridge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.contract.BridgeError
import nl.vdzon.softwarefactory.contract.BridgeRequest
import nl.vdzon.softwarefactory.contract.BridgeResponse
import nl.vdzon.softwarefactory.core.TesterScreenshots
import nl.vdzon.softwarefactory.web.services.FactoryDashboardService
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.Base64

/**
 * Vertaalt een binnengekomen [BridgeRequest] naar een aanroep op de bestaande
 * [FactoryDashboardService] (zie docs/ontwerp-bridge-dashboard.md §5, operatie-catalogus) en
 * verpakt het resultaat weer in een [BridgeResponse]. Uitsluitend vertalen en delegeren — géén
 * nieuwe businesslogica hier (behalve `downloads.list`, zie [FactoryDashboardService.downloads]).
 */
@Component
class BridgeRequestHandler(
    private val dashboardService: FactoryDashboardService,
    private val issueTrackerClient: YouTrackApi,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun handle(request: BridgeRequest): BridgeResponse =
        try {
            val body = dispatch(request.operation, request.params)
            BridgeResponse(id = request.id, ok = true, body = objectMapper.valueToTree(body))
        } catch (unknown: UnknownOperationException) {
            BridgeResponse(
                id = request.id,
                ok = false,
                error = BridgeError(code = "UNKNOWN_OPERATION", message = unknown.message.orEmpty()),
            )
        } catch (tooLarge: ScreenshotTooLargeException) {
            BridgeResponse(id = request.id, ok = false, error = BridgeError(code = "TOO_LARGE", message = tooLarge.message.orEmpty()))
        } catch (notFound: NotFoundException) {
            BridgeResponse(id = request.id, ok = false, error = BridgeError(code = "NOT_FOUND", message = notFound.message.orEmpty()))
        } catch (exception: Exception) {
            logger.warn("Bridge-operatie '{}' faalde: {}", request.operation, exception.message)
            BridgeResponse(
                id = request.id,
                ok = false,
                error = BridgeError(code = "INTERNAL_ERROR", message = exception.message ?: "Onbekende fout"),
            )
        }

    private fun dispatch(operation: String, params: JsonNode?): Any =
        when (operation) {
            "dashboard.get" -> dashboardService.dashboard()
            "stories.list" -> dashboardService.stories()
            "story.detail" -> dashboardService.storyDetail(params.require("storyKey"))
            "story.screenshots" -> screenshotMetadata(params.require("storyKey"))
            "screenshot.get" -> screenshotBody(params.require("storyKey"), params.require("attachmentId"))
            "myActions.list" -> dashboardService.myActions()
            "myActions.count" -> MyActionsCountBody(dashboardService.myActionsCount())
            "agents.list" -> dashboardService.agents()
            "merged.list" -> dashboardService.merged()
            "projects.list" -> dashboardService.projectsOverview()
            "nightly.get" -> dashboardService.nightlyJobs(params.optional("run"))
            "settings.get" -> dashboardService.settings(params.require("username"), params.optional("nightlySaveResult"))
            "downloads.list" -> dashboardService.downloads()
            else -> throw UnknownOperationException("Onbekende operatie: $operation")
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
            throw ScreenshotTooLargeException("Screenshot ${attachment.name} is groter dan de limiet van ${MAX_SCREENSHOT_BYTES / (1024 * 1024)}MB.")
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

    private fun JsonNode?.require(field: String): String =
        this?.path(field)?.takeIf { it.isTextual }?.asText()
            ?: throw IllegalArgumentException("Ontbrekend of ongeldig veld '$field' in params.")

    private fun JsonNode?.optional(field: String): String? =
        this?.path(field)?.takeIf { it.isTextual }?.asText()

    private data class MyActionsCountBody(val count: Int)
    private data class ScreenshotInfo(val id: String, val name: String, val size: Long?, val createdAt: Long?, val mimeType: String?)
    private data class ScreenshotListBody(val screenshots: List<ScreenshotInfo>)
    private data class ScreenshotBody(val id: String, val name: String, val mimeType: String?, val base64: String)

    private class UnknownOperationException(message: String) : Exception(message)
    private class NotFoundException(message: String) : Exception(message)
    private class ScreenshotTooLargeException(message: String) : Exception(message)

    private companion object {
        /** Zelfde orde-grootte als een gemiddelde tester-screenshot; voorkomt gigantische socket-frames. */
        const val MAX_SCREENSHOT_BYTES = 8L * 1024 * 1024
    }
}
