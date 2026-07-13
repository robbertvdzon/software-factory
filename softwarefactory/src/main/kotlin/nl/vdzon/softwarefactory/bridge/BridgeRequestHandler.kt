package nl.vdzon.softwarefactory.bridge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.contract.BridgeError
import nl.vdzon.softwarefactory.contract.BridgeRequest
import nl.vdzon.softwarefactory.contract.BridgeResponse
import nl.vdzon.softwarefactory.core.FactoryCommand
import nl.vdzon.softwarefactory.core.TesterScreenshots
import nl.vdzon.softwarefactory.nightly.NightlyScheduler
import nl.vdzon.softwarefactory.telegram.TelegramAssistantService
import nl.vdzon.softwarefactory.dashboard.models.WorkflowRunInfo
import nl.vdzon.softwarefactory.dashboard.services.FactoryDashboardService
import nl.vdzon.softwarefactory.dashboard.services.FactoryOperationsService
import nl.vdzon.softwarefactory.dashboard.services.FactoryProcessService
import nl.vdzon.softwarefactory.tracker.TrackerApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.Base64

/**
 * Vertaalt een binnengekomen [BridgeRequest] naar een aanroep op de bestaande
 * [FactoryDashboardService]/[FactoryOperationsService] (zie docs/ontwerp-bridge-dashboard.md §5,
 * operatie-catalogus) en verpakt het resultaat weer in een [BridgeResponse]. Uitsluitend vertalen
 * en delegeren — géén nieuwe businesslogica hier (behalve `downloads.list`, zie
 * [FactoryDashboardService.downloads]/[FactoryDashboardService.builds]).
 */
@Component
class BridgeRequestHandler(
    private val dashboardService: FactoryDashboardService,
    private val operations: FactoryOperationsService,
    private val nightlyScheduler: NightlyScheduler,
    private val processService: FactoryProcessService,
    private val issueTrackerClient: TrackerApi,
    private val assistantService: TelegramAssistantService,
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

    private fun dispatch(operation: String, params: JsonNode?): Any =
        when (operation) {
            // reads
            "dashboard.get" -> dashboardService.dashboard()
            "stories.list" -> dashboardService.stories()
            "story.detail" -> dashboardService.storyDetail(params.require("storyKey"))
            "story.screenshots" -> screenshotMetadata(params.require("storyKey"))
            "screenshot.get" -> screenshotBody(params.require("storyKey"), params.require("attachmentId"))
            "myActions.list" -> dashboardService.myActions()
            "myActions.count" -> MyActionsCountBody(dashboardService.myActionsCount())
            "agents.list" -> dashboardService.agents()
            "merged.list" -> dashboardService.merged()
            "projects.list" -> dashboardService.projectsOverview(force = params.optionalBool("force") ?: false)
            "nightly.get" -> dashboardService.nightlyJobs(params.optional("run"))
            "settings.get" -> dashboardService.settings(params.require("username"), params.optional("nightlySaveResult"))
            "downloads.list" -> dashboardService.downloads(force = params.optionalBool("force") ?: false)
            "builds.list" -> dashboardService.builds(force = params.optionalBool("force") ?: false)
            "builds.runs" -> BuildsRunsBody(dashboardService.buildsFor(params.require("owner"), params.require("repo")))
            "assistant.status" -> assistantService.status()
            // acties
            "story.create" -> dashboardService.createStory(
                // SF-818 — projectKey is optioneel: het dialoog stuurt 'm niet meer mee en de service
                // valt terug op het enige geconfigureerde project.
                projectKey = params.optional("projectKey"),
                title = params.require("title"),
                description = params.optional("description"),
                repo = params.optional("repo"),
                aiSupplier = params.optional("aiSupplier"),
                aiModel = params.optional("aiModel"),
                start = params.optionalBool("start") ?: false,
                autoApprove = params.optionalBool("autoApprove") ?: false,
                silent = params.optionalBool("silent") ?: false,
            )
            "story.setStoryPhase" -> {
                operations.setStoryPhase(params.require("storyKey"), params.require("phase"), params.optional("comment"))
                Ack
            }
            "subtask.setPhase" -> {
                operations.setSubtaskPhase(params.require("subtaskKey"), params.require("phase"), params.optional("comment"))
                Ack
            }
            "story.setAutoApprove" -> {
                dashboardService.setAutoApproveFlag(params.require("storyKey"), params.requireBool("enabled"))
                Ack
            }
            "story.setSilent" -> {
                dashboardService.setSilentFlag(params.require("storyKey"), params.requireBool("enabled"))
                Ack
            }
            "story.command" -> {
                val command = FactoryCommand.entries.firstOrNull { it.token == params.require("command") }
                    ?: throw IllegalArgumentException("Onbekend command: ${params.optional("command")}")
                operations.queueCommand(params.require("storyKey"), command, params.optional("reason"))
                Ack
            }
            "story.purge" -> {
                dashboardService.purgeStory(params.require("storyKey"))
                Ack
            }
            "story.startRefining" -> {
                dashboardService.startRefining(params.require("storyKey"))
                Ack
            }
            "story.startDeveloping" -> {
                dashboardService.startDeveloping(params.require("storyKey"))
                Ack
            }
            "nightly.runNow" -> RunNowBody(nightlyScheduler.startManualRun())
            "nightly.stop" -> StopBody(nightlyScheduler.stopActiveRun())
            "nightly.createStory" -> dashboardService.createNightlyStory(params.require("project"), params.require("jobName"))
            "nightly.saveSettings" -> {
                dashboardService.saveNightlySettings(
                    enabled = params.requireBool("enabled"),
                    startTime = params.require("startTime"),
                    summaryTime = params.require("summaryTime"),
                )
                Ack
            }
            "project.forceDeploy" -> {
                dashboardService.forceProjectDeploy(params.require("name"))
                Ack
            }
            "workspace.openInIde" -> OpenWorkspaceBody(dashboardService.openWorkspaceInIntellij(params.require("storyKey")))
            "factory.restart" -> {
                processService.requestRestart()
                Ack
            }
            "factory.stop" -> {
                processService.requestStop()
                Ack
            }
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

    private fun JsonNode?.requireBool(field: String): Boolean =
        this?.path(field)?.takeIf { it.isBoolean }?.asBoolean()
            ?: throw IllegalArgumentException("Ontbrekend of ongeldig veld '$field' in params.")

    private fun JsonNode?.optionalBool(field: String): Boolean? {
        val value = this?.get(field) ?: return null
        if (value.isBoolean) return value.asBoolean()
        throw IllegalArgumentException("Ongeldig veld '$field' in params: JSON-boolean verwacht.")
    }

    private object Ack {
        @Suppress("unused")
        val ok = true
    }

    private data class MyActionsCountBody(val count: Int)
    private data class ScreenshotInfo(val id: String, val name: String, val size: Long?, val createdAt: Long?, val mimeType: String?)
    private data class ScreenshotListBody(val screenshots: List<ScreenshotInfo>)
    private data class ScreenshotBody(val id: String, val name: String, val mimeType: String?, val base64: String)
    private data class RunNowBody(val started: Boolean)
    private data class StopBody(val stopped: Boolean)
    private data class OpenWorkspaceBody(val path: String)
    private data class BuildsRunsBody(val runs: List<WorkflowRunInfo>)

    private class UnknownOperationException(message: String) : Exception(message)
    private class NotFoundException(message: String) : Exception(message)
    private class ScreenshotTooLargeException(message: String) : Exception(message)

    private companion object {
        /** Zelfde orde-grootte als een gemiddelde tester-screenshot; voorkomt gigantische socket-frames. */
        const val MAX_SCREENSHOT_BYTES = 8L * 1024 * 1024
    }
}
