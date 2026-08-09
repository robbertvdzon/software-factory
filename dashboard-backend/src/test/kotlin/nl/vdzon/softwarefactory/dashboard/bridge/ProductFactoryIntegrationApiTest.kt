package nl.vdzon.softwarefactory.dashboard.bridge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.contract.BridgeResponse
import nl.vdzon.softwarefactory.dashboard.config.DashboardSecrets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ProductFactoryIntegrationApiTest {
    private val mapper = jacksonObjectMapper()
    private val secrets = DashboardSecrets("remember", "client", setOf("a@example.com"), "bridge", "integration-secret")

    @Test
    fun `integratieroute vereist het aparte machine-token`() {
        val mvc = mvc { _, _ -> error("mag niet worden aangeroepen") }
        mvc.perform(get("/api/integrations/v1/status")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/integrations/v1/status").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `nieuwe story krijgt context marker en wordt start-next gezet`() {
        val calls = mutableListOf<Pair<String, JsonNode?>>()
        val mvc = mvc { operation, params ->
            calls += operation to params
            when (operation) {
                "stories.list" -> ok("""{"issues":[]}""")
                "story.create" -> ok("""{"key":"SF-2100","description":"x"}""")
                "story.queue" -> ok("""{"ok":true}""")
                else -> error(operation)
            }
        }
        mvc.perform(
            post("/api/integrations/v1/stories")
                .header("Authorization", "Bearer integration-secret")
                .header("Idempotency-Key", "hkh-autopilot:candidate:42")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request()),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.storyKey").value("SF-2100"))
            .andExpect(jsonPath("$.created").value(true))

        assertEquals(listOf("stories.list", "story.create", "story.queue"), calls.map { it.first })
        val create = calls[1].second!!
        assert(create.path("description").asText().contains("Product-Factory-Idempotency-Key: hkh-autopilot:candidate:42"))
        assertEquals("hkh-autopilot", create.path("repo").asText())
        assertEquals(false, create.path("start").asBoolean())
        assertEquals("SF-2100", calls[2].second?.path("storyKey")?.asText())
    }

    @Test
    fun `notificationEvents, questionsAllowed en approvalMode uit het verzoek overschrijven de defaults`() {
        val calls = mutableListOf<Pair<String, JsonNode?>>()
        val mvc = mvc { operation, params ->
            calls += operation to params
            when (operation) {
                "stories.list" -> ok("""{"issues":[]}""")
                "story.create" -> ok("""{"key":"SF-2101","description":"x"}""")
                "story.queue" -> ok("""{"ok":true}""")
                else -> error(operation)
            }
        }
        mvc.perform(
            post("/api/integrations/v1/stories")
                .header("Authorization", "Bearer integration-secret")
                .header("Idempotency-Key", "product-factory:candidate:1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{
                        "productSlug":"product-factory","title":"x","description":"x","repo":"product-factory",
                        "workspaceRunId":"shadow-product-factory-0012","workspaceCommitSha":"abcdef1234567",
                        "artifactPath":"research/shadow-iteration-0012.md","deliveryMode":"start-next",
                        "notificationEvents":["ERROR"],"questionsAllowed":true,"approvalMode":"automatisch"
                    }""",
                ),
        ).andExpect(status().isCreated)

        val create = calls[1].second!!
        assertEquals(listOf("ERROR"), create.path("notificationEvents").map { it.asText() })
        assertEquals(true, create.path("questionsAllowed").asBoolean())
        assertEquals("automatisch", create.path("approvalMode").asText())
    }

    @Test
    fun `ontbrekende notificatie-opties vallen terug op de platformbrede defaults`() {
        val calls = mutableListOf<Pair<String, JsonNode?>>()
        val mvc = mvc { operation, params ->
            calls += operation to params
            when (operation) {
                "stories.list" -> ok("""{"issues":[]}""")
                "story.create" -> ok("""{"key":"SF-2102","description":"x"}""")
                "story.queue" -> ok("""{"ok":true}""")
                else -> error(operation)
            }
        }
        mvc.perform(
            post("/api/integrations/v1/stories")
                .header("Authorization", "Bearer integration-secret")
                .header("Idempotency-Key", "hkh-autopilot:candidate:43")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request()),
        ).andExpect(status().isCreated)

        val create = calls[1].second!!
        assertEquals(
            setOf("DEPLOYED", "QUESTION", "MANUAL_ACTION_REQUIRED", "ERROR"),
            create.path("notificationEvents").map { it.asText() }.toSet(),
        )
        assertEquals(true, create.path("questionsAllowed").asBoolean())
        assertEquals("automatisch", create.path("approvalMode").asText())
    }

    @Test
    fun `retry met dezelfde sleutel maakt geen dubbele story`() {
        val calls = mutableListOf<String>()
        val mvc = mvc { operation, _ ->
            calls += operation
            when (operation) {
                "stories.list" -> ok("""{"issues":[{"key":"SF-2099","description":"Product-Factory-Idempotency-Key: hkh-autopilot:candidate:42","fields":{"storyPhase":"start-next"}}]}""")
                else -> error("Onverwachte write: $operation")
            }
        }
        mvc.perform(
            post("/api/integrations/v1/stories")
                .header("Authorization", "Bearer integration-secret")
                .header("Idempotency-Key", "hkh-autopilot:candidate:42")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request()),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.storyKey").value("SF-2099"))
            .andExpect(jsonPath("$.created").value(false))
        assertEquals(listOf("stories.list"), calls)
    }

    @Test
    fun `antwoordcontract staat alleen bekende overgang toe`() {
        var operation = ""
        var params: JsonNode? = null
        val mvc = mvc { op, body -> operation = op; params = body; ok("""{"ok":true}""") }
        mvc.perform(
            post("/api/integrations/v1/stories/SF-1/answers")
                .header("Authorization", "Bearer integration-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"targetType":"story","targetKey":"SF-1","phase":"questions-answered","answer":"Gebruik de bestaande productvisie."}"""),
        ).andExpect(status().isOk)
        assertEquals("story.setStoryPhase", operation)
        assertEquals("Gebruik de bestaande productvisie.", params?.path("comment")?.asText())

        mvc.perform(
            post("/api/integrations/v1/stories/SF-1/answers")
                .header("Authorization", "Bearer integration-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"subtask\",\"targetKey\":\"SF-2\",\"phase\":\"manual-action-done\",\"answer\":\"De Product Factory heeft een agent-uitvoerbaar alternatief gekozen.\"}"),
        ).andExpect(status().isOk)
        assertEquals("subtask.setPhase", operation)
        assertEquals("manual-action-done", params?.path("phase")?.asText())
    }

    private fun request() = """{
        "productSlug":"hkh-autopilot","title":"Historische kaart","description":"Maak de kaart.",
        "repo":"hkh-autopilot","workspaceRunId":"shadow-hkh-autopilot-0004",
        "workspaceCommitSha":"abcdef1234567","artifactPath":"research/shadow-iteration-0004.md",
        "deliveryMode":"start-next"
    }"""

    private fun ok(json: String) = BridgeResponse(id = "x", ok = true, body = mapper.readTree(json))

    private fun mvc(responder: (String, JsonNode?) -> BridgeResponse) = MockMvcBuilders.standaloneSetup(
        ProductFactoryIntegrationApi(
            object : BridgeHub(secrets) {
                override fun sendRequest(operation: String, params: JsonNode?) = responder(operation, params)
                override fun isConnected() = true
                override fun factoryVersion() = "test"
            },
            secrets,
        ),
    ).build()
}
