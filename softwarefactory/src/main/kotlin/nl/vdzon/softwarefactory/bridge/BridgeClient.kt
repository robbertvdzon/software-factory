package nl.vdzon.softwarefactory.bridge

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.websocket.ContainerProvider
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.contract.BridgeEvent
import nl.vdzon.softwarefactory.contract.BridgeFrameReader
import nl.vdzon.softwarefactory.contract.BridgeHello
import nl.vdzon.softwarefactory.contract.BridgeRequest
import nl.vdzon.softwarefactory.dashboard.DashboardChangeSource
import nl.vdzon.softwarefactory.dashboard.FactoryVersionQuery
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.PongMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.WebSocketClient
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Verbindt de factory uitgaand met elke bridge-URL uit `SF_BRIDGE_URLS` (zie
 * docs/ontwerp-bridge-dashboard.md §5/§7). Eén [BridgeConnection] per URL, onafhankelijke
 * reconnect-backoff. Soft-fail: een kapotte bridge mag de factory-kernloop nooit hinderen
 * (alle fouten hier worden gelogd, nooit gegooid richting de caller).
 */
@Component
class BridgeClient(
    private val secrets: FactorySecrets,
    private val requestHandler: BridgeRequestHandler,
    private val eventBus: DashboardChangeSource,
    private val versionService: FactoryVersionQuery,
    private val webSocketClient: WebSocketClient = StandardWebSocketClient(
        // Tomcats default (8KB) is te klein voor grote responses (bv. `dashboard.get` met veel
        // stories) — de backend sloot de verbinding dan met "message too big" (code 1009).
        ContainerProvider.getWebSocketContainer().apply {
            defaultMaxTextMessageBufferSize = MAX_TEXT_MESSAGE_BUFFER_BYTES
        },
    ),
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    private val baseBackoffMs: Long = 1000,
    private val maxBackoffMs: Long = 60_000,
    private val heartbeatIntervalMs: Long = 30_000,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val connections = ConcurrentHashMap<String, BridgeConnection>()

    // Elk binnenkomend bridge-request werd voorheen synchroon op de websocket-callback-thread
    // afgehandeld (handleTextMessage), dus één trage tracker-call blokkeerde alle andere,
    // gelijktijdig binnenkomende requests op dezelfde verbinding — tot en met een timeout/
    // connection-abort aan de frontend-kant. Verwerk requests daarom op een aparte pool zodat
    // ze elkaar niet blokkeren.
    private val requestExecutor = Executors.newFixedThreadPool(REQUEST_WORKER_COUNT)

    @PostConstruct
    fun start() {
        if (secrets.bridgeUrls.isEmpty()) {
            logger.info("Bridge uitgeschakeld: SF_BRIDGE_URLS is leeg.")
            return
        }
        eventBus.addListener { broadcastChanged() }
        secrets.bridgeUrls.forEach { url ->
            val connection = BridgeConnection(url)
            connections[url] = connection
            connection.connect()
        }
    }

    @PreDestroy
    fun stop() {
        connections.values.forEach { it.shutdown() }
        scheduler.shutdownNow()
        requestExecutor.shutdownNow()
    }

    /** Stuurt een "changed"-event over alle open bridge-verbindingen (de SSE-vervanger). */
    fun broadcastChanged() {
        connections.values.forEach { it.sendEvent(BridgeEvent(event = "changed")) }
    }

    /** Actieve (open) verbindingen, voor diagnostiek/tests. */
    fun activeConnectionCount(): Int = connections.values.count { it.isOpen() }

    /**
     * Eén websocket-verbinding naar één bridge-URL: hello/token, ping/pong-heartbeat,
     * exponentiële reconnect-backoff (onafhankelijk per URL), en het afhandelen van binnenkomende
     * [BridgeRequest]-frames via [requestHandler].
     */
    private inner class BridgeConnection(private val url: String) {
        private val objectMapper: ObjectMapper = jacksonObjectMapper()
        private val session = AtomicReference<WebSocketSession?>(null)
        private val backoffMs = AtomicLong(baseBackoffMs)
        private val missedPongs = AtomicInteger(0)
        // Sends kunnen nu van meerdere requestExecutor-threads tegelijk komen (naast hello/
        // heartbeat/event-sends) — een StandardWebSocketClient-sessie staat geen gelijktijdige
        // writes toe, dus alle sends naar deze sessie lopen via deze lock.
        private val writeLock = Any()
        @Volatile
        private var heartbeatTask: java.util.concurrent.ScheduledFuture<*>? = null

        fun isOpen(): Boolean = session.get()?.isOpen == true

        fun connect() {
            runCatching {
                webSocketClient.execute(Handler(), WebSocketHttpHeaders(), URI.create(url))
            }.onSuccess { future ->
                future.exceptionally { exception ->
                    logger.warn("Bridge-verbinding naar {} kon niet starten: {}", url, exception.message)
                    scheduleReconnect()
                    null
                }
            }.onFailure { exception ->
                logger.warn("Bridge-verbinding naar {} kon niet starten: {}", url, exception.message)
                scheduleReconnect()
            }
        }

        fun sendEvent(event: BridgeEvent) {
            val activeSession = session.get() ?: return
            runCatching {
                synchronized(writeLock) {
                    if (activeSession.isOpen) {
                        activeSession.sendMessage(TextMessage(objectMapper.writeValueAsString(event)))
                    }
                }
            }.onFailure { logger.warn("Bridge-event naar {} kon niet verstuurd worden: {}", url, it.message) }
        }

        fun shutdown() {
            heartbeatTask?.cancel(true)
            runCatching { session.get()?.close() }
        }

        private fun scheduleReconnect() {
            val delay = backoffMs.getAndUpdate { current -> (current * 2).coerceAtMost(maxBackoffMs) }
            scheduler.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
        }

        private fun startHeartbeat(activeSession: WebSocketSession) {
            missedPongs.set(0)
            heartbeatTask = scheduler.scheduleAtFixedRate({
                if (!activeSession.isOpen) return@scheduleAtFixedRate
                if (missedPongs.getAndIncrement() >= HEARTBEAT_MISS_LIMIT) {
                    logger.warn("Bridge {}: {} gemiste pongs, verbinding wordt gesloten.", url, missedPongs.get())
                    runCatching { activeSession.close(CloseStatus.GOING_AWAY) }
                    return@scheduleAtFixedRate
                }
                runCatching { synchronized(writeLock) { activeSession.sendMessage(org.springframework.web.socket.PingMessage()) } }
            }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS)
        }

        private inner class Handler : TextWebSocketHandler() {
            override fun afterConnectionEstablished(webSocketSession: WebSocketSession) {
                session.set(webSocketSession)
                backoffMs.set(baseBackoffMs)
                val hello = BridgeHello(token = secrets.bridgeToken.orEmpty(), factoryVersion = versionService.commitShort())
                runCatching { synchronized(writeLock) { webSocketSession.sendMessage(TextMessage(objectMapper.writeValueAsString(hello))) } }
                    .onFailure { logger.warn("Bridge {}: hello-frame kon niet verstuurd worden: {}", url, it.message) }
                startHeartbeat(webSocketSession)
                logger.info("Bridge verbonden: {}", url)
            }

            override fun handleTextMessage(webSocketSession: WebSocketSession, message: TextMessage) {
                val raw = message.payload
                if (BridgeFrameReader.typeOf(raw) != "request") return
                val request = runCatching { objectMapper.readValue<BridgeRequest>(raw) }.getOrNull() ?: return
                // Op de requestExecutor verwerken, niet op deze websocket-callback-thread: anders
                // blokkeert één trage request (bv. een live tracker-call) alle andere, gelijktijdig
                // binnenkomende requests op dezelfde bridge-verbinding.
                requestExecutor.submit {
                    val response = requestHandler.handle(request)
                    runCatching {
                        synchronized(writeLock) {
                            webSocketSession.sendMessage(TextMessage(objectMapper.writeValueAsString(response)))
                        }
                    }.onFailure { logger.warn("Bridge {}: response kon niet verstuurd worden: {}", url, it.message) }
                }
            }

            override fun handlePongMessage(webSocketSession: WebSocketSession, message: PongMessage) {
                missedPongs.set(0)
            }

            override fun afterConnectionClosed(webSocketSession: WebSocketSession, status: CloseStatus) {
                session.set(null)
                heartbeatTask?.cancel(true)
                logger.info("Bridge {} gesloten ({}), reconnect volgt.", url, status)
                scheduleReconnect()
            }

            override fun handleTransportError(webSocketSession: WebSocketSession, exception: Throwable) {
                logger.warn("Bridge {}: transport-fout: {}", url, exception.message)
            }
        }
    }

    private companion object {
        /** Twee gemiste pongs (elk [BridgeClient.heartbeatIntervalMs]) → verbinding als dood beschouwen. */
        const val HEARTBEAT_MISS_LIMIT = 2
        const val MAX_TEXT_MESSAGE_BUFFER_BYTES = 2 * 1024 * 1024
        /** Aantal bridge-requests dat gelijktijdig verwerkt kan worden (zie [BridgeClient.requestExecutor]). */
        const val REQUEST_WORKER_COUNT = 8
    }
}
