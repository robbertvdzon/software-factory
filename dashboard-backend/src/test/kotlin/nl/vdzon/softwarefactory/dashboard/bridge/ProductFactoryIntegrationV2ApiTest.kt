package nl.vdzon.softwarefactory.dashboard.bridge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.contract.BridgeError
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
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat

class ProductFactoryIntegrationV2ApiTest {
    private val mapper = jacksonObjectMapper()
    private val secrets = DashboardSecrets("remember", "client", setOf("a@example.com"), "bridge", "integration-secret")

    @Test
    fun `alle v2-routes vereisen het integratietoken`() {
        val mvc = mvc { operation, _ -> error("mag niet worden aangeroepen: $operation") }

        mvc.perform(get("/api/integrations/v2/status"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.retryable").value(false))
    }

    @Test
    fun `story zonder attachments wordt na aanmaak gequeued`() {
        val calls = mutableListOf<Pair<String, JsonNode?>>()
        val mvc = mvc { operation, params ->
            calls += operation to params
            when (operation) {
                "productFactory.stories" -> ok("""{"items":[]}""")
                "story.create" -> ok("""{"key":"SF-3001"}""")
                "story.queue" -> ok("""{"ok":true}""")
                else -> error(operation)
            }
        }

        mvc.perform(validPost(storyRequest()))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.storyKey").value("SF-3001"))
            .andExpect(jsonPath("$.created").value(true))
            .andExpect(jsonPath("$.status").value("OPEN"))

        assertEquals(listOf("productFactory.stories", "productFactory.stories", "story.create", "story.queue"), calls.map { it.first })
        val create = calls[2].second!!
        assertEquals(false, create.path("start").asBoolean())
        assertEquals(false, create.path("questionsAllowed").asBoolean())
        assertEquals("claude", create.path("aiSupplier").asText())
        assertEquals("https://github.com/example/hkh.git", create.path("repo").asText())
        val description = create.path("description").asText()
        assert(description.contains("Product-Factory-Api-Version: 2"))
        assert(description.contains("Product-Factory-Product-Id: hkh"))
        assert(description.contains("Product-Factory-Idempotency-Key: product-factory:hkh:story:1:v1"))
        assert(description.contains("Product-Factory-Package-Sha256:"))
    }

    @Test
    fun `attachments worden afzonderlijk opgeslagen voordat de story wordt gequeued`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val calls = mutableListOf<Pair<String, JsonNode?>>()
        val mvc = mvc { operation, params ->
            calls += operation to params
            when (operation) {
                "productFactory.stories" -> ok("""{"items":[]}""")
                "story.create" -> ok("""{"key":"SF-3002"}""")
                "story.attachment.put" -> ok("""{"id":"8","created":true}""")
                "story.queue" -> ok("""{"ok":true}""")
                else -> error(operation)
            }
        }

        mvc.perform(validPost(storyRequest(listOf(attachment("ux", "ux.png", "image/png", bytes)))))
            .andExpect(status().isCreated)

        assertEquals(
            listOf("productFactory.stories", "productFactory.stories", "story.create", "story.attachment.put", "story.queue"),
            calls.map { it.first },
        )
        val upload = calls[3].second!!
        assertEquals("SF-3002", upload.path("storyKey").asText())
        assertEquals("product-factory-input__ux__ux.png", upload.path("name").asText())
        assertEquals(Base64.getEncoder().encodeToString(bytes), upload.path("base64").asText())
    }

    @Test
    fun `retry hervat attachments maar queuet een actieve story niet opnieuw`() {
        val bytes = byteArrayOf(9, 8, 7)
        val calls = mutableListOf<String>()
        val mvc = mvc { operation, _ ->
            calls += operation
            when (operation) {
                "productFactory.stories" -> ok(storyProjection(needsQueue = false))
                "story.attachment.put" -> ok("""{"id":"9","created":false}""")
                else -> error("Onverwachte operatie: $operation")
            }
        }

        mvc.perform(validPost(storyRequest(listOf(attachment("ux", "ux.png", "image/png", bytes)))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.created").value(false))

        assertEquals(listOf("productFactory.stories", "productFactory.stories", "story.attachment.put"), calls)
    }

    @Test
    fun `retry van gedeeltelijke ontvangst uploadt opnieuw en queuet daarna`() {
        val bytes = byteArrayOf(9, 8, 7)
        val calls = mutableListOf<String>()
        val mvc = mvc { operation, _ ->
            calls += operation
            when (operation) {
                "productFactory.stories" -> ok(storyProjection(needsQueue = true))
                "story.attachment.put", "story.queue" -> ok("""{"ok":true}""")
                else -> error(operation)
            }
        }

        mvc.perform(validPost(storyRequest(listOf(attachment("ux", "ux.png", "image/png", bytes)))))
            .andExpect(status().isOk)

        assertEquals(listOf("productFactory.stories", "productFactory.stories", "story.attachment.put", "story.queue"), calls)
    }

    @Test
    fun `dezelfde storyinhoud met een andere idempotentiesleutel gebruikt de eerste story`() {
        val calls = mutableListOf<String>()
        val mvc = mvc { operation, params ->
            calls += operation
            when (operation) {
                "productFactory.stories" -> if (params?.has("packageSha256") == true) {
                    ok(storyProjection(packageSha256 = params.path("packageSha256").asText()))
                } else {
                    ok("""{"items":[]}""")
                }
                else -> error("Er mag geen nieuwe story worden aangemaakt: $operation")
            }
        }

        mvc.perform(
            validPost(
                storyRequest(),
                idempotencyKey = "product-factory:hkh:story:1:second-call",
            ),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.storyKey").value("SF-3000"))
            .andExpect(jsonPath("$.created").value(false))

        assertEquals(listOf("productFactory.stories", "productFactory.stories"), calls)
    }

    @Test
    fun `ongeldige attachmenthash wordt voor storyaanmaak geweigerd`() {
        val mvc = mvc { operation, _ -> error("mag niet worden aangeroepen: $operation") }
        val invalid = """{
          "id":"ux","fileName":"ux.png","mediaType":"image/png","sizeBytes":3,
          "sha256":"${"0".repeat(64)}","contentBase64":"AQID"
        }"""

        mvc.perform(validPost(storyRequestRaw("[$invalid]")))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("ATTACHMENT_HASH_MISMATCH"))
            .andExpect(jsonPath("$.retryable").value(false))
    }

    @Test
    fun `query ondersteunt product status en idempotentiesleutel zonder interne velden te lekken`() {
        var seen: JsonNode? = null
        val mvc = mvc { operation, params ->
            assertEquals("productFactory.stories", operation)
            seen = params
            ok(storyProjection(needsQueue = true))
        }

        mvc.perform(
            get("/api/integrations/v2/stories")
                .header("Authorization", "Bearer integration-secret")
                .param("productId", "hkh")
                .param("status", "open"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].storyKey").value("SF-3000"))
            .andExpect(jsonPath("$.items[0].needsQueue").doesNotExist())
        assertEquals("hkh", seen?.path("productId")?.asText())
        assertEquals("OPEN", seen?.path("status")?.asText())

        mvc.perform(
            get("/api/integrations/v2/stories")
                .header("Authorization", "Bearer integration-secret"),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_QUERY"))
    }

    @Test
    fun `done zonder volledige commit wordt als ongeldige factoryresponse geweigerd`() {
        val mvc = mvc { _, _ -> ok(storyProjection(status = "DONE", deliveredCommitSha = null)) }

        mvc.perform(
            get("/api/integrations/v2/stories/SF-3000")
                .header("Authorization", "Bearer integration-secret"),
        ).andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.code").value("INVALID_FACTORY_RESPONSE"))
            .andExpect(jsonPath("$.retryable").value(true))
    }

    @Test
    fun `attachmentconflict van de bridge geeft 409`() {
        val bytes = byteArrayOf(1)
        val mvc = mvc { operation, _ ->
            when (operation) {
                "productFactory.stories" -> ok(storyProjection(needsQueue = true))
                "story.attachment.put" -> BridgeResponse(error = BridgeError("CONFLICT", "andere inhoud"), id = "x", ok = false)
                else -> error(operation)
            }
        }

        mvc.perform(validPost(storyRequest(listOf(attachment("ux", "ux.png", "image/png", bytes)))))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
    }

    private fun validPost(
        body: String,
        idempotencyKey: String = "product-factory:hkh:story:1:v1",
    ) = post("/api/integrations/v2/stories")
        .header("Authorization", "Bearer integration-secret")
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)

    private fun storyRequest(attachments: List<String> = emptyList()) = storyRequestRaw(attachments.joinToString(prefix = "[", postfix = "]"))

    private fun storyRequestRaw(attachmentsJson: String) = """{
      "productId":"hkh",
      "sourceStoryId":"550e8400-e29b-41d4-a716-446655440000",
      "sourceStoryVersion":1,
      "type":"PRODUCT_STORY",
      "targetRepositoryUrl":"https://github.com/example/hkh.git",
      "title":"Toon lege afsprakenlijst",
      "description":"## Gedrag\\nToon de lege toestand.",
      "attachments":$attachmentsJson
    }"""

    private fun attachment(id: String, fileName: String, mediaType: String, bytes: ByteArray): String {
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        return """{
          "id":"$id","fileName":"$fileName","mediaType":"$mediaType","sizeBytes":${bytes.size},
          "sha256":"$sha","contentBase64":"${Base64.getEncoder().encodeToString(bytes)}"
        }"""
    }

    private fun storyProjection(
        needsQueue: Boolean = false,
        status: String = "OPEN",
        deliveredCommitSha: String? = null,
        packageSha256: String? = null,
    ): String {
        val sha = deliveredCommitSha?.let { "\"$it\"" } ?: "null"
        val packageSha = packageSha256?.let { "\"$it\"" } ?: "null"
        return """{"items":[{
          "storyKey":"SF-3000","productId":"hkh",
          "sourceStoryId":"550e8400-e29b-41d4-a716-446655440000","sourceStoryVersion":1,
          "packageSha256":$packageSha,
          "status":"$status","deliveredCommitSha":$sha,"cancelReason":null,
          "updatedAt":"2026-08-24T14:30:00Z","needsQueue":$needsQueue
        }]}"""
    }

    private fun ok(json: String) = BridgeResponse(id = "x", ok = true, body = mapper.readTree(json))

    private fun mvc(responder: (String, JsonNode?) -> BridgeResponse) = MockMvcBuilders.standaloneSetup(
        ProductFactoryIntegrationV2Api(
            ProductFactoryIntegrationV2Service(
                object : BridgeHub(secrets) {
                    override fun sendRequest(operation: String, params: JsonNode?) = responder(operation, params)
                    override fun isConnected() = true
                    override fun factoryVersion() = "test"
                },
                secrets,
            ),
        ),
    ).setControllerAdvice(ProductFactoryIntegrationV2ErrorHandler()).build()
}
