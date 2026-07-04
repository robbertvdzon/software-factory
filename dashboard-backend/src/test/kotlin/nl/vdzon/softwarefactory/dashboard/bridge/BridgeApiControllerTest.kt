package nl.vdzon.softwarefactory.dashboard.bridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.contract.BridgeError
import nl.vdzon.softwarefactory.contract.BridgeResponse
import nl.vdzon.softwarefactory.dashboard.api.AuthService
import nl.vdzon.softwarefactory.dashboard.config.DashboardSecrets
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** Dekt de REST-vertaling van [BridgeHub]-responses: ok-passthrough, FACTORY_OFFLINE, auth. */
class BridgeApiControllerTest {

    private val secrets = DashboardSecrets(
        dashboardUsername = "robbert",
        dashboardPassword = "secret",
        rememberSecret = "robbert:secret",
        bridgeToken = "token",
    )
    private val authService = AuthService(secrets)
    private val token = authService.login("robbert", "secret").token

    @Test
    fun `stories zonder token geeft 401`() {
        val mockMvc = mockMvcWith(StubHub { error("ongebruikt") })

        mockMvc.perform(get("/api/v1/stories"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `stories geeft de body van de factory door als de hub ok antwoordt`() {
        val body = jacksonObjectMapper().readTree("""{"issues":[{"key":"SF-1"}]}""")
        val mockMvc = mockMvcWith(StubHub { BridgeResponse(id = it, ok = true, body = body) })

        mockMvc.perform(get("/api/v1/stories").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.issues[0].key").value("SF-1"))
    }

    @Test
    fun `geen verbonden factory geeft HTTP 503 met FACTORY_OFFLINE`() {
        val mockMvc = mockMvcWith(StubHub { throw FactoryOfflineException() })

        mockMvc.perform(get("/api/v1/my-actions/count").header("Authorization", "Bearer $token"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("FACTORY_OFFLINE"))
    }

    @Test
    fun `story-detail geeft de storyKey door aan de hub-request`() {
        var seenOperation: String? = null
        val mockMvc = mockMvcWith(
            StubHub {
                seenOperation = it
                BridgeResponse(id = it, ok = true, body = jacksonObjectMapper().readTree("""{"storyKey":"SF-1"}"""))
            },
        )

        mockMvc.perform(get("/api/v1/stories/SF-1").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.storyKey").value("SF-1"))
        org.junit.jupiter.api.Assertions.assertEquals("story.detail", seenOperation)
    }

    @Test
    fun `screenshot-image decodeert de base64-body en zet het content-type`() {
        val bytes = byteArrayOf(1, 2, 3)
        val body = jacksonObjectMapper().createObjectNode()
            .put("mimeType", "image/png")
            .put("base64", java.util.Base64.getEncoder().encodeToString(bytes))
        val mockMvc = mockMvcWith(StubHub { BridgeResponse(id = it, ok = true, body = body) })

        mockMvc.perform(get("/api/v1/stories/SF-1/screenshots/1/image").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(content().contentType("image/png"))
            .andExpect(content().bytes(bytes))
    }

    @Test
    fun `screenshot-image geeft 404 door bij NOT_FOUND`() {
        val mockMvc = mockMvcWith(
            StubHub { BridgeResponse(id = it, ok = false, error = BridgeError("NOT_FOUND", "weg")) },
        )

        mockMvc.perform(get("/api/v1/stories/SF-1/screenshots/1/image").header("Authorization", "Bearer $token"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `status meldt de verbindingsstatus van de hub`() {
        val mockMvc = mockMvcWith(
            object : StubHub({ error("ongebruikt") }) {
                override fun isConnected() = true
                override fun factoryVersion() = "abc1234"
            },
        )

        mockMvc.perform(get("/api/v1/status").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.connected").value(true))
            .andExpect(jsonPath("$.factoryVersion").value("abc1234"))
    }

    private fun mockMvcWith(hub: StubHub): MockMvc =
        MockMvcBuilders.standaloneSetup(BridgeApiController(authService, hub, secrets)).build()

    /** Test-double voor [BridgeHub]: geen echte socket, alleen het gedrag dat de controller ziet. */
    private open class StubHub(private val responder: (String) -> BridgeResponse) :
        BridgeHub(
            DashboardSecrets(
                dashboardUsername = "robbert",
                dashboardPassword = "secret",
                rememberSecret = "robbert:secret",
                bridgeToken = "token",
            ),
        ) {
        override fun sendRequest(operation: String, params: com.fasterxml.jackson.databind.JsonNode?) =
            responder(operation)

        override fun isConnected() = false
        override fun connectedSince(): java.time.Instant? = null
        override fun factoryVersion(): String? = null
        override fun addEventListener(listener: (nl.vdzon.softwarefactory.contract.BridgeEvent) -> Unit) {}
    }
}
