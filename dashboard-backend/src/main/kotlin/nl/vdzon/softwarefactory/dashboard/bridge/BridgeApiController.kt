package nl.vdzon.softwarefactory.dashboard.bridge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.contract.BridgeError
import nl.vdzon.softwarefactory.contract.BridgeResponse
import nl.vdzon.softwarefactory.dashboard.api.AuthService
import nl.vdzon.softwarefactory.dashboard.config.DashboardSecrets
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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
    private val secrets: DashboardSecrets,
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

    @GetMapping("/api/v1/merged")
    fun merged(@RequestHeader("Authorization", required = false) authorization: String?): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("merged.list"))
    }

    @GetMapping("/api/v1/projects")
    fun projects(@RequestHeader("Authorization", required = false) authorization: String?): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("projects.list"))
    }

    @GetMapping("/api/v1/nightly")
    fun nightly(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestParam("run", required = false) run: String?,
    ): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("nightly.get", run?.let { paramsOf("run" to it) }))
    }

    @GetMapping("/api/v1/settings")
    fun settings(@RequestHeader("Authorization", required = false) authorization: String?): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("settings.get", paramsOf("username" to secrets.dashboardUsername)))
    }

    @GetMapping("/api/v1/downloads")
    fun downloads(@RequestHeader("Authorization", required = false) authorization: String?): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("downloads.list"))
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

    private fun paramsOf(vararg entries: Pair<String, String>): JsonNode =
        objectMapper.createObjectNode().apply { entries.forEach { (key, value) -> put(key, value) } }

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
            else -> HttpStatus.BAD_GATEWAY
        }

    private companion object {
        const val EVENTS_TIMEOUT_MS = 30L * 60L * 1000L
    }
}

/** Vertaalt een offline hub naar dezelfde `ok=false`/`FACTORY_OFFLINE`-vorm als een echte response. */
private fun BridgeHub.dispatch(operation: String, params: JsonNode? = null): BridgeResponse =
    try {
        sendRequest(operation, params)
    } catch (offline: FactoryOfflineException) {
        BridgeResponse(id = "", ok = false, error = BridgeError("FACTORY_OFFLINE", offline.message ?: "Geen factory verbonden"))
    }
