package nl.vdzon.softwarefactory.bridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.contract.BridgeFrameReader
import nl.vdzon.softwarefactory.contract.BridgeHello
import nl.vdzon.softwarefactory.contract.BridgeParams
import nl.vdzon.softwarefactory.contract.BridgeRequest
import nl.vdzon.softwarefactory.contract.BridgeResponse
import nl.vdzon.softwarefactory.dashboard.services.DashboardEventBus
import nl.vdzon.softwarefactory.dashboard.services.FactoryVersionService
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Configuration
import org.springframework.boot.web.server.WebServer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * [BridgeClient] tegen een echte (embedded) websocket-server: dekt het "fase B"-hard-criterium
 * uit docs/ontwerp-bridge-dashboard.md §8 ("factory-tests met fake hub groen, reconnect getest").
 * De fake hub sluit de EERSTE verbinding meteen na de hello-frame in de reconnect-test — de
 * tweede moet vanzelf (backoff) tot stand komen. Kleine backoff-waardes houden de test snel.
 */
class BridgeClientTest {

    private var server: ConfigurableApplicationContext? = null
    private var client: BridgeClient? = null

    @AfterEach
    fun tearDown() {
        client?.stop()
        server?.close()
    }

    @Test
    fun `reconnect na een verbroken verbinding`() {
        val hub = RecordingHandler(closeFirstConnection = true)
        val port = startFakeHub(hub)
        val bridgeClient = newClient(port)
        client = bridgeClient

        bridgeClient.start()

        await().atMost(Duration.ofSeconds(10)).until { hub.connectionCount() >= 2 }
        await().atMost(Duration.ofSeconds(10)).until { bridgeClient.activeConnectionCount() == 1 }
        assertTrue(hub.helloTokens().all { it == "shared-secret" })
    }

    @Test
    fun `beantwoordt een request-frame van de hub`() {
        val hub = RecordingHandler(closeFirstConnection = false)
        val port = startFakeHub(hub)
        val bridgeClient = newClient(port)
        client = bridgeClient

        bridgeClient.start()
        await().atMost(Duration.ofSeconds(10)).until { hub.connectionCount() >= 1 }

        hub.sendRequest(BridgeRequest(id = "r-1", operation = "myActions.count"))

        await().atMost(Duration.ofSeconds(10)).until { hub.lastResponse()?.id == "r-1" }
        assertTrue(hub.lastResponse()?.ok == true)
    }

    @Test
    fun `lokale bridge laat alleen force true de projectcache omzeilen`() {
        val hub = RecordingHandler(closeFirstConnection = false)
        val port = startFakeHub(hub)
        val fixture = BridgeTestFixtures.minimalRequestHandlerWithFakes()
        val bridgeClient = newClient(port, fixture.handler)
        client = bridgeClient
        bridgeClient.start()
        await().atMost(Duration.ofSeconds(10)).until { hub.connectionCount() >= 1 }

        hub.sendRequest(BridgeRequest(id = "missing", operation = "projects.list"))
        await().atMost(Duration.ofSeconds(10)).until { hub.response("missing")?.ok == true }
        assertEquals(1, fixture.tracker.findWorkIssuesCalls)

        hub.sendRequest(
            BridgeRequest(id = "false", operation = "projects.list", params = BridgeParams.boolean("force", false)),
        )
        await().atMost(Duration.ofSeconds(10)).until { hub.response("false")?.ok == true }
        assertEquals(1, fixture.tracker.findWorkIssuesCalls)

        hub.sendRequest(
            BridgeRequest(id = "true", operation = "projects.list", params = BridgeParams.boolean("force", true)),
        )
        await().atMost(Duration.ofSeconds(10)).until { hub.response("true")?.ok == true }
        assertEquals(2, fixture.tracker.findWorkIssuesCalls)
    }

    private fun newClient(
        port: Int,
        requestHandler: BridgeRequestHandler = BridgeTestFixtures.minimalRequestHandler(),
    ): BridgeClient {
        val secrets = FactorySecrets(
            trackerProjects = emptyList(),
            githubToken = "fake",
            factoryDatabaseUrl = "jdbc:fake",
            factoryDatabaseSchema = "fake",
            kubeconfig = null,
            aiCredentialsDir = null,
            aiOauthToken = null,
            loadedFrom = "fake",
            bridgeUrls = listOf("ws://localhost:$port/bridge"),
            bridgeToken = "shared-secret",
        )
        return BridgeClient(
            secrets = secrets,
            requestHandler = requestHandler,
            eventBus = DashboardEventBus(),
            versionService = FactoryVersionService(),
            webSocketClient = StandardWebSocketClient(),
            scheduler = Executors.newSingleThreadScheduledExecutor(),
            baseBackoffMs = 20,
            maxBackoffMs = 100,
            heartbeatIntervalMs = 5_000,
        )
    }

    private fun startFakeHub(handler: TextWebSocketHandler): Int {
        FakeHubConfig.handlerOverride = handler
        // Command-line-args via `.run(...)` i.p.v. `.properties(...)` (defaultProperties, laagste
        // prioriteit) — zie BridgeHubTest voor de achtergrond: een yml met een expliciete
        // server.port-placeholder zou defaultProperties anders altijd verslaan.
        val context = SpringApplicationBuilder(FakeHubConfig::class.java)
            .run("--server.port=0", "--spring.main.banner-mode=off")
        server = context
        return (context as org.springframework.boot.web.context.WebServerApplicationContext).webServer.port
    }

    /**
     * Minimale Spring Boot-context (echte autoconfiguratie, dus een echte websocket-servlet) —
     * bewust GEEN `@SpringBootApplication`/component-scan: dat zou de hele factory-app (incl. de
     * echte `BridgeClient`-bean, die hier geen `FactorySecrets` heeft) mee opstarten.
     */
    @Configuration
    @EnableAutoConfiguration(
        exclude = [
            org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration::class,
            org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration::class,
        ],
    )
    @EnableWebSocket
    private class FakeHubConfig : WebSocketConfigurer {
        override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
            registry.addHandler(handlerOverride!!, "/bridge")
        }

        companion object {
            var handlerOverride: TextWebSocketHandler? = null
        }
    }

    /** Registreert hello-tokens/verbindingen en kan optioneel de eerste sessie meteen sluiten. */
    private class RecordingHandler(private val closeFirstConnection: Boolean) : TextWebSocketHandler() {
        private val mapper = jacksonObjectMapper()
        private val sessions = CopyOnWriteArrayList<WebSocketSession>()
        private val tokens = CopyOnWriteArrayList<String>()
        private val responses = CopyOnWriteArrayList<BridgeResponse>()
        private var connections = 0

        fun connectionCount(): Int = connections
        fun helloTokens(): List<String> = tokens.toList()
        fun lastResponse(): BridgeResponse? = responses.lastOrNull()
        fun response(id: String): BridgeResponse? = responses.lastOrNull { it.id == id }

        fun sendRequest(request: BridgeRequest) {
            sessions.lastOrNull()?.sendMessage(TextMessage(mapper.writeValueAsString(request)))
        }

        override fun afterConnectionEstablished(session: WebSocketSession) {
            connections++
            sessions.add(session)
            if (closeFirstConnection && connections == 1) {
                session.close(CloseStatus.GOING_AWAY)
            }
        }

        override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
            val raw = message.payload
            when (BridgeFrameReader.typeOf(raw)) {
                "hello" -> tokens.add(mapper.readValue<BridgeHello>(raw).token)
                "response" -> responses.add(mapper.readValue<BridgeResponse>(raw))
            }
        }

        override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
            sessions.remove(session)
        }
    }
}
