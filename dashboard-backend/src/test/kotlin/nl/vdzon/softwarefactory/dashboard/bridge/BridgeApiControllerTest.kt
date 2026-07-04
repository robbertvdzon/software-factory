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
        MockMvcBuilders.standaloneSetup(BridgeApiController(authService, hub)).build()

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
