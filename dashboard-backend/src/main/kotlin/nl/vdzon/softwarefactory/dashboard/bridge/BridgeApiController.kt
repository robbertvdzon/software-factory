package nl.vdzon.softwarefactory.dashboard.bridge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.contract.BridgeResponse
import nl.vdzon.softwarefactory.dashboard.api.AuthService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.format.DateTimeFormatter
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

    @GetMapping("/api/v1/stories")
    fun stories(@RequestHeader("Authorization", required = false) authorization: String?): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("stories.list"))
    }

    @GetMapping("/api/v1/my-actions/count")
    fun myActionsCount(@RequestHeader("Authorization", required = false) authorization: String?): ResponseEntity<Any> {
        authService.requireAuthorization(authorization)
        return respond(hub.dispatch("myActions.count"))
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

    private fun respond(response: BridgeResponse): ResponseEntity<Any> {
        if (response.ok) {
            return ResponseEntity.ok(response.body ?: objectMapper.createObjectNode())
        }
        val status = if (response.error?.code == "FACTORY_OFFLINE") HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.BAD_GATEWAY
        return ResponseEntity.status(status).body(response.error)
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
        BridgeResponse(
            id = "",
            ok = false,
            error = nl.vdzon.softwarefactory.contract.BridgeError("FACTORY_OFFLINE", offline.message ?: "Geen factory verbonden"),
        )
    }
