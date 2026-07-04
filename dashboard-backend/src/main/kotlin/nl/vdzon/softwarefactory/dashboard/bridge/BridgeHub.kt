package nl.vdzon.softwarefactory.dashboard.bridge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
) : TextWebSocketHandler() {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()

    @Volatile private var session: WebSocketSession? = null
    @Volatile private var connectedSince: Instant? = null
    @Volatile private var factoryVersion: String? = null
    private val pending = ConcurrentHashMap<String, CompletableFuture<BridgeResponse>>()
    private val eventListeners = CopyOnWriteArrayList<(BridgeEvent) -> Unit>()

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
            activeSession.sendMessage(TextMessage(mapper.writeValueAsString(request)))
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
        // Wacht op de hello-frame vóór deze verbinding als "de" factory-verbinding geldt.
    }

    override fun handleTextMessage(webSocketSession: WebSocketSession, message: TextMessage) {
        val raw = message.payload
        when (BridgeFrameReader.typeOf(raw)) {
            "hello" -> handleHello(webSocketSession, raw)
            "response" -> handleResponse(raw)
            "event" -> handleEvent(raw)
            else -> logger.warn("Onbekend bridge-frame-type genegeerd.")
        }
    }

    private fun handleHello(webSocketSession: WebSocketSession, raw: String) {
        val hello = runCatching { mapper.readValue<BridgeHello>(raw) }.getOrNull()
        if (hello == null || secrets.bridgeToken.isBlank() || hello.token != secrets.bridgeToken) {
            logger.warn("Bridge-hello geweigerd (ongeldig token).")
            runCatching { webSocketSession.close(CloseStatus.POLICY_VIOLATION) }
            return
        }
        // Nieuwe verbinding vervangt de oude (hooguit één factory per backend-instantie).
        session?.takeIf { it.isOpen && it != webSocketSession }?.let { old ->
            runCatching { old.close(CloseStatus.NORMAL.withReason("replaced")) }
        }
        session = webSocketSession
        connectedSince = Instant.now()
        factoryVersion = hello.factoryVersion
        logger.info("Factory verbonden via bridge: version={}", hello.factoryVersion)
    }

    private fun handleResponse(raw: String) {
        val response = runCatching { mapper.readValue<BridgeResponse>(raw) }.getOrNull() ?: return
        pending.remove(response.id)?.complete(response)
    }

    private fun handleEvent(raw: String) {
        val event = runCatching { mapper.readValue<BridgeEvent>(raw) }.getOrNull() ?: return
        eventListeners.forEach { listener -> runCatching { listener(event) } }
    }

    override fun afterConnectionClosed(webSocketSession: WebSocketSession, status: CloseStatus) {
        if (session == webSocketSession) {
            session = null
            connectedSince = null
            factoryVersion = null
            val offline = BridgeResponse(id = "", ok = false, error = BridgeError("FACTORY_OFFLINE", "Bridge-verbinding gesloten."))
            pending.keys.toList().forEach { id -> pending.remove(id)?.complete(offline.copy(id = id)) }
        }
    }

    /** Kleine wrapper zodat de time-out zowel als constructor-argument als test-override kan. */
    data class Duration(val amount: Long, val unit: TimeUnit)
}
