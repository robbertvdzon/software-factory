package nl.vdzon.softwarefactory.dashboard.bridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import nl.vdzon.softwarefactory.contract.BridgeHello
import nl.vdzon.softwarefactory.contract.BridgeRequest
import nl.vdzon.softwarefactory.contract.BridgeResponse
import nl.vdzon.softwarefactory.dashboard.config.DashboardSecrets
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.net.URI
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [BridgeHub] tegen een echte (embedded) websocket-server met een fake factory-client (zie
 * docs/ontwerp-bridge-dashboard.md §10 — "backend-tests met een scriptbare fake factory aan de
 * socket"). Dekt: token-check bij hello, request/response-correlatie, time-out, en dat een
 * nieuwe verbinding de oude vervangt.
 */
class BridgeHubTest {

    private var server: ConfigurableApplicationContext? = null
    private val mapper = jacksonObjectMapper()

    @AfterEach
    fun tearDown() {
        server?.close()
    }

    @Test
    fun `weigert een hello met een fout token`() {
        val (hub, port) = startHub(bridgeToken = "correct-token")
        val client = FakeFactory()

        client.connect(port, token = "wrong-token")

        await().atMost(Duration.ofSeconds(5)).until { client.closed() }
        assertEquals(false, hub.isConnected())
    }

    @Test
    fun `sendRequest geeft een ok-response terug met de body van de factory`() {
        val (hub, port) = startHub(bridgeToken = "correct-token")
        val client = FakeFactory()
        client.connect(port, token = "correct-token")
        await().atMost(Duration.ofSeconds(5)).until { hub.isConnected() }

        val resultRef = arrayOfNulls<BridgeResponse>(1)
        val thread = Thread { resultRef[0] = hub.sendRequest("myActions.count") }
        thread.start()
        client.awaitRequest()
        client.respondOk(client.lastRequest()!!.id, """{"count":2}""")
        thread.join(5000)

        val response = resultRef[0]
        assertEquals(true, response?.ok)
        assertEquals(2, response?.body?.path("count")?.asInt())
    }

    @Test
    fun `geen verbonden factory geeft FactoryOfflineException`() {
        val (hub, _) = startHub(bridgeToken = "correct-token")

        val exception = runCatching { hub.sendRequest("stories.list") }.exceptionOrNull()

        assertTrue(exception is FactoryOfflineException)
    }

    @Test
    fun `time-out geeft een ok-false response met code TIMEOUT`() {
        val (hub, port) = startHub(bridgeToken = "correct-token", timeoutMs = 200)
        val client = FakeFactory()
        client.connect(port, token = "correct-token")
        await().atMost(Duration.ofSeconds(5)).until { hub.isConnected() }

        // Factory ontvangt de request maar antwoordt bewust niet: de time-out moet toeslaan.
        val response = hub.sendRequest("stories.list")

        assertEquals(false, response.ok)
        assertEquals("TIMEOUT", response.error?.code)
    }

    @Test
    fun `gelijktijdige sendRequest-aanroepen crashen niet op de gedeelde websocket-sessie`() {
        // Regressietest voor SF: de 20s-statuspoll van de Flutter-app liep gelijktijdig met een
        // schermfetch, en zonder synchronisatie op de websocket-write gooide Tomcat
        // IllegalStateException("TEXT_PARTIAL_WRITING") zodra twee threads tegelijk schreven —
        // met lege Dashboard/Nightly-schermen tot gevolg.
        val (hub, port) = startHub(bridgeToken = "correct-token")
        val client = FakeFactory(autoRespond = true)
        client.connect(port, token = "correct-token")
        await().atMost(Duration.ofSeconds(5)).until { hub.isConnected() }

        val threadCount = 20
        val errors = CopyOnWriteArrayList<Throwable>()
        val responses = CopyOnWriteArrayList<BridgeResponse>()
        val threads = (1..threadCount).map { i ->
            Thread {
                try {
                    responses.add(hub.sendRequest("op-$i"))
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }

        assertTrue(errors.isEmpty(), "onverwachte exceptions: $errors")
        assertEquals(threadCount, responses.size)
        assertTrue(responses.all { it.ok })
    }

    @Test
    fun `een nieuwe verbinding vervangt de oude`() {
        val (hub, port) = startHub(bridgeToken = "correct-token")
        val first = FakeFactory()
        first.connect(port, token = "correct-token")
        await().atMost(Duration.ofSeconds(5)).until { hub.isConnected() }

        val second = FakeFactory()
        second.connect(port, token = "correct-token")

        await().atMost(Duration.ofSeconds(5)).until { first.closed() }
        assertEquals(true, hub.isConnected())
    }

    private fun startHub(bridgeToken: String, timeoutMs: Long = 5000): Pair<BridgeHub, Int> {
        HubTestConfig.secretsOverride = DashboardSecrets(
            dashboardUsername = "admin",
            dashboardPassword = "secret",
            rememberSecret = "admin:secret",
            bridgeToken = bridgeToken,
        )
        HubTestConfig.timeoutMsOverride = timeoutMs
        // `.properties(...)` zet defaultProperties (laagste prioriteit) — application.yml's
        // `server.port: ${PORT:8080}` wint daarvan, dus bindt altijd op 8080 i.p.v. een vrije
        // poort. Command-line-args via `.run(...)` hebben wél de hoogste prioriteit.
        val context = SpringApplicationBuilder(HubTestConfig::class.java)
            .run("--server.port=0", "--spring.main.banner-mode=off")
        server = context
        val port = (context as WebServerApplicationContext).webServer!!.port
        return context.getBean(BridgeHub::class.java) to port
    }

    @Configuration
    @EnableAutoConfiguration(exclude = [DataSourceAutoConfiguration::class, FlywayAutoConfiguration::class])
    @org.springframework.web.socket.config.annotation.EnableWebSocket
    private class HubTestConfig {
        @Bean
        fun dashboardSecrets(): DashboardSecrets = secretsOverride!!

        @Bean
        fun bridgeHub(secrets: DashboardSecrets): BridgeHub =
            BridgeHub(secrets, BridgeHub.Duration(timeoutMsOverride, TimeUnit.MILLISECONDS))

        @Bean
        fun bridgeWebSocketConfig(hub: BridgeHub): BridgeWebSocketConfig = BridgeWebSocketConfig(hub)

        companion object {
            var secretsOverride: DashboardSecrets? = null
            var timeoutMsOverride: Long = 5000
        }
    }

    /** Scriptbare fake factory: verbindt, stuurt hello, en kan requests beantwoorden. */
    private class FakeFactory(private val autoRespond: Boolean = false) : TextWebSocketHandler() {
        private val client = StandardWebSocketClient()
        private var session: WebSocketSession? = null
        private val requests = CopyOnWriteArrayList<BridgeRequest>()
        @Volatile private var isClosed = false

        fun connect(port: Int, token: String) {
            val future = client.execute(this, null, URI.create("ws://localhost:$port/bridge"))
            session = future.get(5, TimeUnit.SECONDS)
            session!!.sendMessage(
                TextMessage(mapper.writeValueAsString(BridgeHello(token = token, factoryVersion = "test-sha"))),
            )
        }

        fun closed(): Boolean = isClosed

        fun lastRequest(): BridgeRequest? = requests.lastOrNull()

        fun awaitRequest() {
            await().atMost(Duration.ofSeconds(5)).until { requests.isNotEmpty() }
        }

        fun respondOk(id: String, bodyJson: String) {
            val response = BridgeResponse(id = id, ok = true, body = mapper.readTree(bodyJson))
            session?.sendMessage(TextMessage(mapper.writeValueAsString(response)))
        }

        override fun handleTextMessage(webSocketSession: WebSocketSession, message: TextMessage) {
            val raw = message.payload
            if (nl.vdzon.softwarefactory.contract.BridgeFrameReader.typeOf(raw) == "request") {
                val request = mapper.readValue<BridgeRequest>(raw)
                requests.add(request)
                if (autoRespond) {
                    respondOk(request.id, """{"echo":"${request.operation}"}""")
                }
            }
        }

        override fun afterConnectionClosed(webSocketSession: WebSocketSession, status: CloseStatus) {
            isClosed = true
        }

        private val mapper get() = jacksonObjectMapper()
    }
}
