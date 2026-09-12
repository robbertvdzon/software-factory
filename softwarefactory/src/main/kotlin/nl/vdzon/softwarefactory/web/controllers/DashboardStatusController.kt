package nl.vdzon.softwarefactory.web.controllers

import nl.vdzon.softwarefactory.dashboard.DashboardChangeSource
import nl.vdzon.softwarefactory.dashboard.FactoryVersionQuery
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Status en live-verversing van het dashboard: `/api/v1/status` (versie en starttijd) en
 * `/api/v1/events`, een SSE-kanaal dat een `changed`-event stuurt zodra de factory-state
 * mogelijk is veranderd. De frontend ververst dan alleen haar data.
 */
@RestController
class DashboardStatusController(
    private val versionService: FactoryVersionQuery,
    changeSource: DashboardChangeSource,
) {
    private val eventEmitters = CopyOnWriteArrayList<SseEmitter>()

    init {
        changeSource.addListener { broadcast { emitter -> emitter.send(SseEmitter.event().name("changed").data("1")) } }
    }

    @GetMapping("/api/v1/status")
    fun status(): Map<String, Any?> {
        val info = versionService.info()
        return mapOf(
            "connected" to true,
            "since" to DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(info.startedAt),
            "factoryVersion" to info.commitShort,
        )
    }

    @GetMapping("/api/v1/events")
    fun events(): SseEmitter {
        val emitter = SseEmitter(EVENTS_TIMEOUT_MS)
        emitter.onCompletion { eventEmitters.remove(emitter) }
        emitter.onTimeout { eventEmitters.remove(emitter) }
        emitter.onError { eventEmitters.remove(emitter) }
        eventEmitters.add(emitter)
        runCatching { emitter.send(SseEmitter.event().comment("connected")) }.onFailure { eventEmitters.remove(emitter) }
        return emitter
    }

    /**
     * Houdt de SSE-verbindingen levend. De emitter zelf geeft pas na [EVENTS_TIMEOUT_MS] (30 min)
     * op, maar er zit een Cloudflare-proxy voor die een stream zónder verkeer al veel eerder
     * dichtgooit — en op een rustige factory is er minutenlang geen enkel event. Een commentaarregel
     * is genoeg: die houdt de verbinding warm zonder dat de client 'm als event ziet.
     */
    @Scheduled(fixedDelayString = "\${sf.dashboard.sse-heartbeat-ms:20000}")
    fun sendHeartbeat() {
        broadcast { emitter -> emitter.send(SseEmitter.event().comment("ping")) }
    }

    /** Aantal openstaande `/api/v1/events`-verbindingen; alleen om de heartbeat te kunnen testen. */
    internal fun openEventConnections(): Int = eventEmitters.size

    private fun broadcast(send: (SseEmitter) -> Unit) {
        eventEmitters.forEach { emitter ->
            runCatching { send(emitter) }.onFailure { eventEmitters.remove(emitter) }
        }
    }

    private companion object {
        const val EVENTS_TIMEOUT_MS = 30L * 60L * 1000L
    }
}
