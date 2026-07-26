package nl.vdzon.softwarefactory.dashboard.bridge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.contract.BridgeError
import nl.vdzon.softwarefactory.contract.BridgeResponse
import nl.vdzon.softwarefactory.contract.BridgeParams
import nl.vdzon.softwarefactory.dashboard.api.AuthService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Frontend-API: één REST-endpoint per bridge-operatie (§5), plus `/api/v1/status` en
 * `/api/v1/events`. Elke aanroep gaat via [BridgeHub.sendRequest]; geen factory verbonden =>
 * HTTP 503 met code `FACTORY_OFFLINE` (de frontend toont dat als status-banner).
 */
@RestController
class BridgeApiController(
    private val authService: AuthService,
    private val hub: BridgeHub,
) {
    private val objectMapper = jacksonObjectMapper()

    private val eventEmitters = CopyOnWriteArrayList<SseEmitter>()

    init {
        hub.addEventListener { event ->
            eventEmitters.forEach { emitter ->
                runCatching { emitter.send(SseEmitter.event().name(event.event).data(event.body ?: objectMapper.createObjectNode())) }
                    .onFailure { eventEmitters.remove(emitter) }
            }
        }
    }

    @GetMapping("/api/v1/status")
    fun status(@RequestHeader("Authorization", required = false) authorization: String?): Map<String, Any?> {
        authService.requireAuthorization(authorization)
        return mapOf(
            "connected" to hub.isConnected(),
            "since" to hub.connectedSince()?.let { DateTimeFormatter.ISO_INSTANT.format(it) },
            "factoryVersion" to hub.factoryVersion(),
        )
    }

    @GetMapping("/api/v1/dashboard")
    fun dashboard(@RequestHeader("Authorization", required = false) authorization: String?): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("dashboard.get"))
    }

    @GetMapping("/api/v1/stories")
    fun stories(@RequestHeader("Authorization", required = false) authorization: String?): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("stories.list"))
    }

    @GetMapping("/api/v1/stories/{storyKey}")
    fun storyDetail(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("story.detail", paramsOf("storyKey" to storyKey)))
    }

    @GetMapping("/api/v1/stories/{storyKey}/screenshots")
    fun storyScreenshots(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("story.screenshots", paramsOf("storyKey" to storyKey)))
    }

    @GetMapping("/api/v1/stories/{storyKey}/screenshots/{attachmentId}/image")
    fun screenshotImage(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
        @PathVariable attachmentId: String,
    ): ResponseEntity<ByteArray> {
        authService.requireAuthorization(authorization)
        val response = hub.dispatch("screenshot.get", paramsOf("storyKey" to storyKey, "attachmentId" to attachmentId))
        if (!response.ok) {
            val status = statusFor(response.error?.code)
            return ResponseEntity.status(status).body(ByteArray(0))
        }
        val body = response.body ?: return ResponseEntity.notFound().build()
        val bytes = Base64.getDecoder().decode(body.path("base64").asText(""))
        val mimeType = body.path("mimeType").asText(null)?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        return ResponseEntity.ok()
            .header("Cache-Control", "private, max-age=60")
            .contentType(MediaType.parseMediaType(mimeType))
            .body(bytes)
    }

    @GetMapping("/api/v1/my-actions")
    fun myActions(@RequestHeader("Authorization", required = false) authorization: String?): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("myActions.list"))
    }

    @GetMapping("/api/v1/my-actions/count")
    fun myActionsCount(@RequestHeader("Authorization", required = false) authorization: String?): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("myActions.count"))
    }

    @GetMapping("/api/v1/agents")
    fun agents(@RequestHeader("Authorization", required = false) authorization: String?): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("agents.list"))
    }

    @GetMapping("/api/v1/agents/{agentRunId}/events")
    fun agentLog(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable agentRunId: String,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("agent.log", paramsOf("agentRunId" to agentRunId)))
    }

    @GetMapping("/api/v1/assistant/status")
    fun assistantStatus(@RequestHeader("Authorization", required = false) authorization: String?): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("assistant.status"))
    }

    @GetMapping("/api/v1/projects")
    fun projects(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestParam("refresh", required = false) refresh: Boolean?,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("projects.list", refresh?.let { BridgeParams.boolean("force", it) }))
    }

    /** Lazy: het Projects-scherm haalt dit pas op zodra de branch-timeline-sectie van dit project wordt uitgeklapt. */
    @GetMapping("/api/v1/projects/{name}/branch-timeline")
    fun branchTimeline(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable name: String,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("projects.branchTimeline", paramsOf("name" to name)))
    }

    /**
     * Fallback voor de Buildstraat-pagina zodra een story-branch al gemerged is (dan levert
     * [branchTimeline] hierboven geen rij meer voor die branch/PR): build-/deploystatus van precies
     * het merge-commit van deze PR, i.p.v. de dan allang doorgeschoven main-tip.
     */
    @GetMapping("/api/v1/projects/{name}/branch-timeline/pr/{prNumber}")
    fun branchTimelineForMergedPr(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable name: String,
        @PathVariable prNumber: Int,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("projects.branchTimelineForMergedPr", paramsOf("name" to name, "prNumber" to prNumber.toString())))
    }

    /**
     * Builds-tab: commit-historie van [branch] van [name], [perPage] commits vanaf [page] (1-based).
     * Branch-keuzes worden door de UI zelf uit [branchTimeline] hierboven afgeleid (main + open PR's) —
     * geen apart branch-lijst-endpoint nodig.
     */
    @GetMapping("/api/v1/projects/{name}/build-history")
    fun buildHistory(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable name: String,
        @RequestParam("branch") branch: String,
        @RequestParam("page", required = false) page: Int?,
        @RequestParam("perPage", required = false) perPage: Int?,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(
            hub.dispatch(
                "projects.buildHistory",
                paramsOf(
                    "name" to name,
                    "branch" to branch,
                    "page" to (page ?: 1).toString(),
                    "perPage" to (perPage ?: 4).toString(),
                ),
            ),
        )
    }

    /**
     * Builds-tab: elke minuut ververste in-memory snapshot van de laatste 4 commits per project
     * (op de default branch, zie `RecentCommitsPoller`) — voedt de auto-select bij het openen van
     * de tab (welk project/branch het meest recent iets gecommit kreeg).
     */
    @GetMapping("/api/v1/projects/recent-commits")
    fun recentCommits(
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("projects.recentCommits"))
    }

    @GetMapping("/api/v1/settings")
    fun settings(@RequestHeader("Authorization", required = false) authorization: String?): ResponseEntity<Any> {
        val email = authService.requireAuthorization(authorization)
        return respond(hub.dispatch("settings.get", paramsOf("username" to email)))
    }

    @GetMapping("/api/v1/downloads")
    fun downloads(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestParam("refresh", required = false) refresh: Boolean?,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("downloads.list", refresh?.let { BridgeParams.boolean("force", it) }))
    }

    @GetMapping("/api/v1/builds")
    fun builds(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestParam("refresh", required = false) refresh: Boolean?,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("builds.list", refresh?.let { BridgeParams.boolean("force", it) }))
    }

    @GetMapping("/api/v1/repositories/{owner}/{repo}/workflows")
    fun repositoryWorkflows(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable owner: String,
        @PathVariable repo: String,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("builds.runs", paramsOf("owner" to owner, "repo" to repo)))
    }

    @GetMapping("/api/v1/repositories/{owner}/{repo}/runs")
    fun repositoryRuns(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable owner: String,
        @PathVariable repo: String,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("builds.runs", paramsOf("owner" to owner, "repo" to repo)))
    }

    // ── acties (§5) ─────────────────────────────────────────────────────────────

    @PostMapping("/api/v1/stories")
    fun createStory(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestBody body: CreateStoryRequest,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        val params = objectMapper.createObjectNode()
            .put("title", body.title)
            .put("start", body.start)
            .put("questionsAllowed", body.questionsAllowed)
            .put("approvalMode", body.approvalMode)
            .put("notifyMode", body.notifyMode)
        // SF-818 — projectKey is optioneel: het "Nieuwe story"-dialoog stuurt 'm niet meer mee.
        body.projectKey?.let { params.put("projectKey", it) }
        body.description?.let { params.put("description", it) }
        body.repo?.let { params.put("repo", it) }
        body.aiSupplier?.let { params.put("aiSupplier", it) }
        body.aiModel?.let { params.put("aiModel", it) }
        return respond(hub.dispatch("story.create", params))
    }

    @PostMapping("/api/v1/stories/{storyKey}/story-phase")
    fun setStoryPhase(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
        @RequestBody body: PhaseRequest,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        val params = paramsOf("storyKey" to storyKey, "phase" to body.phase)
        body.comment?.let { params.put("comment", it) }
        return respond(hub.dispatch("story.setStoryPhase", params))
    }

    @PostMapping("/api/v1/subtasks/{subtaskKey}/phase")
    fun setSubtaskPhase(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable subtaskKey: String,
        @RequestBody body: PhaseRequest,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        val params = paramsOf("subtaskKey" to subtaskKey, "phase" to body.phase)
        body.comment?.let { params.put("comment", it) }
        return respond(hub.dispatch("subtask.setPhase", params))
    }

    @PostMapping("/api/v1/stories/{storyKey}/questions-allowed")
    fun setQuestionsAllowed(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
        @RequestBody body: QuestionsAllowedRequest,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        val params = objectMapper.createObjectNode().put("storyKey", storyKey).put("enabled", body.enabled)
        return respond(hub.dispatch("story.setQuestionsAllowed", params))
    }

    @PostMapping("/api/v1/stories/{storyKey}/approval-mode")
    fun setApprovalMode(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
        @RequestBody body: ModeRequest,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        val params = objectMapper.createObjectNode().put("storyKey", storyKey).put("mode", body.mode)
        return respond(hub.dispatch("story.setApprovalMode", params))
    }

    @PostMapping("/api/v1/stories/{storyKey}/notify-mode")
    fun setNotifyMode(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
        @RequestBody body: ModeRequest,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        val params = objectMapper.createObjectNode().put("storyKey", storyKey).put("mode", body.mode)
        return respond(hub.dispatch("story.setNotifyMode", params))
    }

    /** Partial update: alleen de meegegeven (niet-null) velden worden gewijzigd (analoog aan auto-approve/silent). */
    @PostMapping("/api/v1/stories/{storyKey}/edit")
    fun editStory(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
        @RequestBody body: EditStoryRequest,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        val params = objectMapper.createObjectNode().put("storyKey", storyKey)
        body.description?.let { params.put("description", it) }
        body.aiSupplier?.let { params.put("aiSupplier", it) }
        body.aiModel?.let { params.put("aiModel", it) }
        return respond(hub.dispatch("story.edit", params))
    }

    /** `command`: pause/resume/kill/re-implement/clear-error/retry-current-step/delete/merge/approve/reject. */
    @PostMapping("/api/v1/stories/{storyKey}/command/{command}")
    fun command(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
        @PathVariable command: String,
        @RequestBody(required = false) body: CommandRequest?,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        val params = paramsOf("storyKey" to storyKey, "command" to command)
        body?.reason?.let { params.put("reason", it) }
        return respond(hub.dispatch("story.command", params))
    }

    /** DESTRUCTIEF — de frontend vraagt bevestiging vóór deze aanroep. */
    @PostMapping("/api/v1/stories/{storyKey}/purge")
    fun purgeStory(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("story.purge", paramsOf("storyKey" to storyKey)))
    }

    @PostMapping("/api/v1/stories/{storyKey}/start-refining")
    fun startRefining(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("story.startRefining", paramsOf("storyKey" to storyKey)))
    }

    /** "Queue story": wacht op de per-repo-wachtrij i.p.v. meteen te starten (zie `start-refining` voor de override). */
    @PostMapping("/api/v1/stories/{storyKey}/queue")
    fun queueStory(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("story.queue", paramsOf("storyKey" to storyKey)))
    }

    @PostMapping("/api/v1/stories/{storyKey}/start-developing")
    fun startDeveloping(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("story.startDeveloping", paramsOf("storyKey" to storyKey)))
    }

    @PostMapping("/api/v1/stories/{storyKey}/open-workspace")
    fun openWorkspace(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("workspace.openInIde", paramsOf("storyKey" to storyKey)))
    }

    @GetMapping("/api/v1/audit-reports")
    fun auditReports(@RequestHeader("Authorization", required = false) authorization: String?): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("audit.reports"))
    }

    @GetMapping("/api/v1/audit-memory")
    fun auditMemory(@RequestHeader("Authorization", required = false) authorization: String?): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("audit.memory"))
    }

    @GetMapping("/api/v1/audits/overview")
    fun auditOverview(@RequestHeader("Authorization", required = false) authorization: String?): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("audit.overview"))
    }

    @PostMapping("/api/v1/audits/run-now")
    fun auditRunNow(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestBody body: AuditRunNowRequest,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("audit.runNow", paramsOf("project" to body.project, "auditType" to body.auditType)))
    }

    @PostMapping("/api/v1/audit-memory/update")
    fun auditMemoryUpdate(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestBody body: AuditMemoryNoteRequest,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(
            hub.dispatch(
                "audit.memory.update",
                paramsOf("project" to body.project, "auditType" to body.auditType, "key" to body.key, "content" to body.content),
            ),
        )
    }

    @PostMapping("/api/v1/audit-memory/delete")
    fun auditMemoryDelete(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestBody body: AuditMemoryNoteKeyRequest,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(
            hub.dispatch("audit.memory.delete", paramsOf("project" to body.project, "auditType" to body.auditType, "key" to body.key)),
        )
    }

    @PostMapping("/api/v1/projects/{name}/force-deploy")
    fun forceDeploy(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable name: String,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("project.forceDeploy", paramsOf("name" to name)))
    }

    /** DESTRUCTIEF (herstart de factory-JVM) — de frontend vraagt bevestiging vóór deze aanroep. */
    @PostMapping("/api/v1/factory/restart")
    fun factoryRestart(@RequestHeader("Authorization", required = false) authorization: String?): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("factory.restart"))
    }

    /** DESTRUCTIEF (stopt de factory-JVM) — de frontend vraagt bevestiging vóór deze aanroep. */
    @PostMapping("/api/v1/factory/stop")
    fun factoryStop(@RequestHeader("Authorization", required = false) authorization: String?): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("factory.stop"))
    }

    @GetMapping("/api/v1/events")
    fun events(@RequestHeader("Authorization", required = false) authorization: String?): SseEmitter {
        authService.requireAuthorization(authorization)
        val emitter = SseEmitter(EVENTS_TIMEOUT_MS)
        emitter.onCompletion { eventEmitters.remove(emitter) }
        emitter.onTimeout { eventEmitters.remove(emitter) }
        emitter.onError { eventEmitters.remove(emitter) }
        eventEmitters.add(emitter)
        runCatching { emitter.send(SseEmitter.event().comment("connected")) }.onFailure { eventEmitters.remove(emitter) }
        return emitter
    }

    private fun paramsOf(vararg entries: Pair<String, String>): com.fasterxml.jackson.databind.node.ObjectNode =
        BridgeParams.strings(*entries)

    private fun respond(response: BridgeResponse): ResponseEntity<Any> {
        if (response.ok) {
            return ResponseEntity.ok(response.body ?: objectMapper.createObjectNode())
        }
        return ResponseEntity.status(statusFor(response.error?.code)).body(response.error)
    }

    private fun statusFor(code: String?): HttpStatus =
        when (code) {
            "FACTORY_OFFLINE" -> HttpStatus.SERVICE_UNAVAILABLE
            "NOT_FOUND" -> HttpStatus.NOT_FOUND
            "TOO_LARGE" -> HttpStatus.PAYLOAD_TOO_LARGE
            "INVALID_PARAMS" -> HttpStatus.BAD_REQUEST
            else -> HttpStatus.BAD_GATEWAY
        }

    private companion object {
        const val EVENTS_TIMEOUT_MS = 30L * 60L * 1000L
    }
}

data class CreateStoryRequest(
    val projectKey: String? = null,
    val title: String,
    val description: String? = null,
    val repo: String? = null,
    val aiSupplier: String? = null,
    val aiModel: String? = null,
    val start: Boolean = false,
    val questionsAllowed: Boolean = true,
    val approvalMode: String = "automatisch",
    val notifyMode: String = "als-klaar",
)

data class EditStoryRequest(val description: String? = null, val aiSupplier: String? = null, val aiModel: String? = null)
data class PhaseRequest(val phase: String, val comment: String? = null)
data class QuestionsAllowedRequest(val enabled: Boolean)
data class ModeRequest(val mode: String)
data class CommandRequest(val reason: String? = null)
data class AuditMemoryNoteRequest(val project: String, val auditType: String, val key: String, val content: String)
data class AuditMemoryNoteKeyRequest(val project: String, val auditType: String, val key: String)
data class AuditRunNowRequest(val project: String, val auditType: String)

/** Vertaalt een offline hub naar dezelfde `ok=false`/`FACTORY_OFFLINE`-vorm als een echte response. */
private fun BridgeHub.dispatch(operation: String, params: JsonNode? = null): BridgeResponse =
    try {
        sendRequest(operation, params)
    } catch (offline: FactoryOfflineException) {
        BridgeResponse(id = "", ok = false, error = BridgeError("FACTORY_OFFLINE", offline.message ?: "Geen factory verbonden"))
    }
