package nl.vdzon.softwarefactory.dashboard.bridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.contract.BridgeError
import nl.vdzon.softwarefactory.contract.BridgeResponse
import nl.vdzon.softwarefactory.dashboard.api.AuthService
import nl.vdzon.softwarefactory.dashboard.api.GoogleIdTokenVerifier
import nl.vdzon.softwarefactory.dashboard.api.GoogleIdentity
import nl.vdzon.softwarefactory.dashboard.config.DashboardSecrets
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** Dekt de REST-vertaling van [BridgeHub]-responses: ok-passthrough, FACTORY_OFFLINE, auth. */
class BridgeApiControllerTest {

    private val secrets = DashboardSecrets(
        rememberSecret = "robbert:secret",
        googleClientId = "client-id",
        allowedEmails = setOf("robbert@vdzon.com"),
        bridgeToken = "token",
    )

    /** Stub-verifier: geeft altijd een geldige, allowlisted identiteit terug (geen echte JWT nodig). */
    private val verifier = GoogleIdTokenVerifier { GoogleIdentity(email = "robbert@vdzon.com", emailVerified = true) }
    private val authService = AuthService(secrets, verifier)
    private val token = authService.loginWithGoogle("stub-id-token").token

    @Test
    fun `stories zonder token geeft 401`() {
        val mockMvc = mockMvcWith(StubHub { _, _ -> error("ongebruikt") })

        mockMvc.perform(get("/api/v1/stories"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `stories geeft de body van de factory door als de hub ok antwoordt`() {
        val body = jacksonObjectMapper().readTree("""{"issues":[{"key":"SF-1"}]}""")
        val mockMvc = mockMvcWith(StubHub { op, _ -> BridgeResponse(id = op, ok = true, body = body) })

        mockMvc.perform(get("/api/v1/stories").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.issues[0].key").value("SF-1"))
    }

    @Test
    fun `assistant-status vertaalt naar de assistant-status-operatie`() {
        val body = jacksonObjectMapper().readTree("""{"enabled":true,"busy":false,"activeChatCount":0,"lastActivityAt":null}""")
        val mockMvc = mockMvcWith(StubHub { op, _ -> BridgeResponse(id = op, ok = op == "assistant.status", body = body) })

        mockMvc.perform(get("/api/v1/assistant/status").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(true))
    }

    @Test
    fun `geen verbonden factory geeft HTTP 503 met FACTORY_OFFLINE`() {
        val mockMvc = mockMvcWith(StubHub { _, _ -> throw FactoryOfflineException() })

        mockMvc.perform(get("/api/v1/my-actions/count").header("Authorization", "Bearer $token"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("FACTORY_OFFLINE"))
    }

    @Test
    fun `story-detail geeft de storyKey door aan de hub-request`() {
        var seenOperation: String? = null
        val mockMvc = mockMvcWith(
            StubHub { op, _ ->
                seenOperation = op
                BridgeResponse(id = op, ok = true, body = jacksonObjectMapper().readTree("""{"storyKey":"SF-1"}"""))
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
        val mockMvc = mockMvcWith(StubHub { op, _ -> BridgeResponse(id = op, ok = true, body = body) })

        mockMvc.perform(get("/api/v1/stories/SF-1/screenshots/1/image").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(content().contentType("image/png"))
            .andExpect(content().bytes(bytes))
    }

    @Test
    fun `screenshot-image geeft 404 door bij NOT_FOUND`() {
        val mockMvc = mockMvcWith(
            StubHub { op, _ -> BridgeResponse(id = op, ok = false, error = BridgeError("NOT_FOUND", "weg")) },
        )

        mockMvc.perform(get("/api/v1/stories/SF-1/screenshots/1/image").header("Authorization", "Bearer $token"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `status meldt de verbindingsstatus van de hub`() {
        val mockMvc = mockMvcWith(
            object : StubHub({ _, _ -> error("ongebruikt") }) {
                override fun isConnected() = true
                override fun factoryVersion() = "abc1234"
            },
        )

        mockMvc.perform(get("/api/v1/status").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.connected").value(true))
            .andExpect(jsonPath("$.factoryVersion").value("abc1234"))
    }

    @Test
    fun `story-purge stuurt de operatie story-purge met de storyKey`() {
        var seenOperation: String? = null
        var seenParams: com.fasterxml.jackson.databind.JsonNode? = null
        val hub = StubHub { op, params ->
            seenOperation = op
            seenParams = params
            BridgeResponse(id = "x", ok = true)
        }

        mockMvcWith(hub).perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/stories/SF-1/purge")
                .header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk)

        org.junit.jupiter.api.Assertions.assertEquals("story.purge", seenOperation)
        org.junit.jupiter.api.Assertions.assertEquals("SF-1", seenParams?.path("storyKey")?.asText())
    }

    @Test
    fun `story-command stuurt commando en reden door`() {
        var seenParams: com.fasterxml.jackson.databind.JsonNode? = null
        val hub = StubHub { _, params ->
            seenParams = params
            BridgeResponse(id = "x", ok = true)
        }

        mockMvcWith(hub).perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/stories/SF-1/command/reject")
                .header("Authorization", "Bearer $token")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""{"reason":"niet compleet"}"""),
        ).andExpect(status().isOk)

        org.junit.jupiter.api.Assertions.assertEquals("reject", seenParams?.path("command")?.asText())
        org.junit.jupiter.api.Assertions.assertEquals("niet compleet", seenParams?.path("reason")?.asText())
    }

    @Test
    fun `auto-approve stuurt het enabled-veld als boolean door`() {
        var seenParams: com.fasterxml.jackson.databind.JsonNode? = null
        val hub = StubHub { _, params ->
            seenParams = params
            BridgeResponse(id = "x", ok = true)
        }

        mockMvcWith(hub).perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/stories/SF-1/auto-approve")
                .header("Authorization", "Bearer $token")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""{"enabled":true}"""),
        ).andExpect(status().isOk)

        org.junit.jupiter.api.Assertions.assertEquals(true, seenParams?.path("enabled")?.asBoolean())
    }

    @Test
    fun `refresh endpoints sturen exact ontbrekend false en true als bridgeparams`() {
        listOf(
            "/api/v1/projects" to "projects.list",
            "/api/v1/downloads" to "downloads.list",
            "/api/v1/builds" to "builds.list",
        ).forEach { (path, operation) ->
            listOf(null, false, true).forEach { refresh ->
                var seenOperation: String? = null
                var seenParams: com.fasterxml.jackson.databind.JsonNode? = null
                val hub = StubHub { op, params ->
                    seenOperation = op
                    seenParams = params
                    BridgeResponse(id = "x", ok = true)
                }
                val request = get(path).header("Authorization", "Bearer $token")
                refresh?.let { request.queryParam("refresh", it.toString()) }

                mockMvcWith(hub).perform(request).andExpect(status().isOk)

                assertAll(
                    { org.junit.jupiter.api.Assertions.assertEquals(operation, seenOperation) },
                    {
                        if (refresh == null) {
                            org.junit.jupiter.api.Assertions.assertNull(seenParams)
                        } else {
                            org.junit.jupiter.api.Assertions.assertEquals(
                                jacksonObjectMapper().readTree("""{"force":$refresh}"""),
                                seenParams,
                            )
                            org.junit.jupiter.api.Assertions.assertTrue(seenParams?.path("force")?.isBoolean == true)
                        }
                    },
                )
            }
        }
    }

    @Test
    fun `agents-events stuurt de agent-log-operatie met de agentRunId als param`() {
        var seenOperation: String? = null
        var seenParams: com.fasterxml.jackson.databind.JsonNode? = null
        val body = jacksonObjectMapper().readTree("""{"agentRunId":42,"lines":[{"kind":"docker-stdout","text":"hallo"}],"errors":[]}""")
        val hub = StubHub { op, params ->
            seenOperation = op
            seenParams = params
            BridgeResponse(id = "x", ok = true, body = body)
        }

        mockMvcWith(hub).perform(get("/api/v1/agents/42/events").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lines[0].text").value("hallo"))

        org.junit.jupiter.api.Assertions.assertEquals("agent.log", seenOperation)
        org.junit.jupiter.api.Assertions.assertEquals("42", seenParams?.path("agentRunId")?.asText())
    }

    @Test
    fun `agents-events zonder token geeft 401`() {
        val mockMvc = mockMvcWith(StubHub { _, _ -> error("ongebruikt") })

        mockMvc.perform(get("/api/v1/agents/42/events"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `een INVALID_PARAMS-fout van de factory geeft HTTP 400`() {
        val hub = StubHub { _, _ -> BridgeResponse(id = "x", ok = false, error = BridgeError("INVALID_PARAMS", "storyKey ontbreekt")) }

        mockMvcWith(hub).perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/stories/SF-1/purge")
                .header("Authorization", "Bearer $token"),
        ).andExpect(status().isBadRequest)
    }

    private fun mockMvcWith(hub: StubHub): MockMvc =
        MockMvcBuilders.standaloneSetup(BridgeApiController(authService, hub)).build()

    /** Test-double voor [BridgeHub]: geen echte socket, alleen het gedrag dat de controller ziet. */
    private open class StubHub(private val responder: (String, com.fasterxml.jackson.databind.JsonNode?) -> BridgeResponse) :
        BridgeHub(
            DashboardSecrets(
                rememberSecret = "robbert:secret",
                googleClientId = "client-id",
                allowedEmails = setOf("robbert@vdzon.com"),
                bridgeToken = "token",
            ),
        ) {
        override fun sendRequest(operation: String, params: com.fasterxml.jackson.databind.JsonNode?) =
            responder(operation, params)

        override fun isConnected() = false
        override fun connectedSince(): java.time.Instant? = null
        override fun factoryVersion(): String? = null
        override fun addEventListener(listener: (nl.vdzon.softwarefactory.contract.BridgeEvent) -> Unit) {}
    }
}
