package nl.vdzon.softwarefactory.dashboard.bridge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PreDestroy
import nl.vdzon.softwarefactory.contract.BridgeError
import nl.vdzon.softwarefactory.contract.BridgeEvent
import nl.vdzon.softwarefactory.contract.BridgeFrameReader
import nl.vdzon.softwarefactory.contract.BridgeHello
import nl.vdzon.softwarefactory.contract.BridgeRequest
import nl.vdzon.softwarefactory.contract.BridgeResponse
import nl.vdzon.softwarefactory.dashboard.config.DashboardSecrets
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class FactoryOfflineException : RuntimeException("Geen factory verbonden")

/**
 * De bridge-hub: het websocket-endpoint (`/bridge`) waarop de factory uitgaand verbindt (zie
 * docs/ontwerp-bridge-dashboard.md §5/§7). Hooguit één factory-verbinding tegelijk — een nieuwe
 * vervangt de oude. Vertaalt REST-aanroepen ([sendRequest]) naar request-frames en matcht het
 * antwoord via de correlation-id; forwardt "event"-frames naar de SSE-luisteraars van de frontend.
 */
@Component
class BridgeHub(
    private val secrets: DashboardSecrets,
    private val requestTimeout: Duration = Duration(30, TimeUnit.SECONDS),
    private val helloTimeout: Duration = Duration(HELLO_TIMEOUT_SECONDS, TimeUnit.SECONDS),
) : TextWebSocketHandler() {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    @Volatile private var session: WebSocketSession? = null
    @Volatile private var connectedSince: Instant? = null
    @Volatile private var factoryVersion: String? = null
    private val pending = ConcurrentHashMap<String, CompletableFuture<BridgeResponse>>()
    private val eventListeners = CopyOnWriteArrayList<(BridgeEvent) -> Unit>()

    /**
     * Sessies die zich met een geldig token via een hello hebben geauthenticeerd. Alleen frames van
     * deze sessies worden verwerkt; alles daarbuiten wordt geweigerd en de socket gaat dicht.
     */
    private val authenticatedSessions = ConcurrentHashMap.newKeySet<WebSocketSession>()

    /** Per nog niet geauthenticeerde sessie de geplande [helloTimeout]-taak, zodat we die kunnen annuleren. */
    private val helloTimers = ConcurrentHashMap<WebSocketSession, ScheduledFuture<*>>()

    /** Eigen daemon-scheduler voor de hello-timeouts (huispatroon: heartbeat in `BridgeClient`). */
    private val helloTimeoutScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "bridge-hello-timeout").apply { isDaemon = true }
        }

    /**
     * Serialiseert schrijven naar de websocket-sessie. Meerdere REST-requests komen gelijktijdig
     * binnen op aparte Tomcat-threads (bv. de 20s-statuspoll naast een schermfetch); zonder deze
     * lock gooit Tomcat's websocket-implementatie `IllegalStateException: TEXT_PARTIAL_WRITING`
     * zodra twee threads tegelijk op dezelfde sessie schrijven.
     */
    private val writeLock = Any()

    fun isConnected(): Boolean = session?.isOpen == true
    fun connectedSince(): Instant? = connectedSince
    fun factoryVersion(): String? = factoryVersion

    /** Registreert een luisteraar voor "event"-frames van de factory (de SSE-vervanger). */
    fun addEventListener(listener: (BridgeEvent) -> Unit) {
        eventListeners.add(listener)
    }

    /**
     * Stuurt [operation]/[params] als request naar de verbonden factory en wacht max
     * [requestTimeout] op het antwoord. Gooit [FactoryOfflineException] zonder verbonden factory;
     * geeft een `ok=false`-[BridgeResponse] terug bij een timeout (geen exception — de aanroeper
     * behandelt dat hetzelfde als elke andere operatie-fout).
     */
    fun sendRequest(operation: String, params: JsonNode? = null): BridgeResponse {
        val activeSession = session?.takeIf { it.isOpen } ?: throw FactoryOfflineException()
        val id = UUID.randomUUID().toString()
        val future = CompletableFuture<BridgeResponse>()
        pending[id] = future
        try {
            val request = BridgeRequest(id = id, operation = operation, params = params)
            synchronized(writeLock) {
                activeSession.sendMessage(TextMessage(mapper.writeValueAsString(request)))
            }
            return future.get(requestTimeout.amount, requestTimeout.unit)
        } catch (timeout: TimeoutException) {
            return BridgeResponse(
                id = id,
                ok = false,
                error = BridgeError(code = "TIMEOUT", message = "De factory antwoordde niet binnen de time-out."),
            )
        } finally {
            pending.remove(id)
        }
    }

    override fun afterConnectionEstablished(webSocketSession: WebSocketSession) {
        // Wacht op de hello-frame vóór deze verbinding als "de" factory-verbinding geldt. Blijft die
        // hello uit, dan sluit de timeout-taak de socket; zo blijven onbekende clients niet hangen.
        val timer = helloTimeoutScheduler.schedule(
            { closeIfStillUnauthenticated(webSocketSession) },
            helloTimeout.amount,
            helloTimeout.unit,
        )
        helloTimers[webSocketSession] = timer
    }

    private fun closeIfStillUnauthenticated(webSocketSession: WebSocketSession) {
        helloTimers.remove(webSocketSession)
        if (webSocketSession in authenticatedSessions) return
        logger.warn("Bridge-verbinding gesloten: geen geldige hello binnen de time-out.")
        runCatching { webSocketSession.close(CloseStatus.POLICY_VIOLATION) }
    }

    override fun handleTextMessage(webSocketSession: WebSocketSession, message: TextMessage) {
        val raw = message.payload
        when (BridgeFrameReader.typeOf(raw)) {
            "hello" -> handleHello(webSocketSession, raw)
            "response" -> handleResponse(webSocketSession, raw)
            "event" -> handleEvent(webSocketSession, raw)
            else -> logger.warn("Onbekend bridge-frame-type genegeerd.")
        }
    }

    /**
     * Weigert een frame van een sessie die zich niet geauthenticeerd heeft: niets verwerken, geen
     * frame-inhoud loggen en de verbinding sluiten.
     */
    private fun rejectUnauthenticated(webSocketSession: WebSocketSession, frameType: String) {
        logger.warn("Bridge-frame van een niet-geauthenticeerde sessie geweigerd: type={}", frameType)
        runCatching { webSocketSession.close(CloseStatus.POLICY_VIOLATION) }
    }

    /** Vergeet een sessie: uit de geauthenticeerde verzameling en haar hello-timer geannuleerd. */
    private fun forget(webSocketSession: WebSocketSession) {
        authenticatedSessions.remove(webSocketSession)
        helloTimers.remove(webSocketSession)?.cancel(false)
    }

    private fun handleHello(webSocketSession: WebSocketSession, raw: String) {
        val hello = runCatching { mapper.readValue<BridgeHello>(raw) }.getOrNull()
        if (hello == null || secrets.bridgeToken.isBlank() || !constantTimeEquals(hello.token, secrets.bridgeToken)) {
            logger.warn("Bridge-hello geweigerd (ongeldig token).")
            forget(webSocketSession)
            runCatching { webSocketSession.close(CloseStatus.POLICY_VIOLATION) }
            return
        }
        authenticatedSessions.add(webSocketSession)
        helloTimers.remove(webSocketSession)?.cancel(false)
        // Nieuwe verbinding vervangt de oude (hooguit één factory per backend-instantie).
        session?.takeIf { it.isOpen && it != webSocketSession }?.let { old ->
            forget(old)
            runCatching { old.close(CloseStatus.NORMAL.withReason("replaced")) }
        }
        session = webSocketSession
        connectedSince = Instant.now()
        factoryVersion = hello.factoryVersion
        logger.info("Factory verbonden via bridge: version={}", hello.factoryVersion)
    }

    private fun handleResponse(webSocketSession: WebSocketSession, raw: String) {
        if (webSocketSession !in authenticatedSessions) {
            rejectUnauthenticated(webSocketSession, "response")
            return
        }
        val response = runCatching { mapper.readValue<BridgeResponse>(raw) }.getOrNull() ?: return
        pending.remove(response.id)?.complete(response)
    }

    private fun handleEvent(webSocketSession: WebSocketSession, raw: String) {
        if (webSocketSession !in authenticatedSessions) {
            rejectUnauthenticated(webSocketSession, "event")
            return
        }
        val event = runCatching { mapper.readValue<BridgeEvent>(raw) }.getOrNull() ?: return
        eventListeners.forEach { listener -> runCatching { listener(event) } }
    }

    override fun afterConnectionClosed(webSocketSession: WebSocketSession, status: CloseStatus) {
        forget(webSocketSession)
        if (session == webSocketSession) {
            session = null
            connectedSince = null
            factoryVersion = null
            val offline = BridgeResponse(id = "", ok = false, error = BridgeError("FACTORY_OFFLINE", "Bridge-verbinding gesloten."))
            pending.keys.toList().forEach { id -> pending.remove(id)?.complete(offline.copy(id = id)) }
        }
    }

    /**
     * Vergelijkt twee strings in constante tijd om timing-side-channels te voorkomen (een
     * aanvaller mag het bridge-token niet byte-voor-byte kunnen raden aan de hand van de
     * responstijd). [MessageDigest.isEqual] is op moderne JDK's timing-safe.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))

    /** Stopt de timeout-scheduler netjes als de bean wordt opgeruimd. */
    @PreDestroy
    fun shutdown() {
        helloTimeoutScheduler.shutdownNow()
    }

    /** Kleine wrapper zodat de time-out zowel als constructor-argument als test-override kan. */
    data class Duration(val amount: Long, val unit: TimeUnit)

    companion object {
        /**
         * Hoe lang een verse websocket-verbinding de tijd krijgt om zich met een hello te
         * authenticeren. Ruim bemeten: `BridgeClient` stuurt de hello synchroon direct na het
         * openen van de socket.
         */
        private const val HELLO_TIMEOUT_SECONDS = 10L
    }
}
