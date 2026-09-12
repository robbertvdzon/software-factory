package nl.vdzon.softwarefactory.web

import nl.vdzon.softwarefactory.core.contracts.TrackerAttachment
import nl.vdzon.softwarefactory.dashboard.models.ProductFactoryStoriesPageData
import nl.vdzon.softwarefactory.dashboard.models.ProductFactoryStoryFilter
import nl.vdzon.softwarefactory.dashboard.models.ProductFactoryStoryStatusView
import nl.vdzon.softwarefactory.dashboard.models.StoryDetailPageData
import nl.vdzon.softwarefactory.web.controllers.ProductFactoryIntegrationV2Controller
import nl.vdzon.softwarefactory.web.controllers.ProductFactoryIntegrationV2ErrorHandler
import nl.vdzon.softwarefactory.web.services.ProductFactoryIntegrationV2Service
import nl.vdzon.softwarefactory.web.services.StoryAttachmentStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Base64
import java.util.HexFormat

class ProductFactoryIntegrationV2ControllerTest {

    /** Poorten van één test: [stories] beantwoordt elke statusquery, de filters worden vastgelegd in [filters]. */
    private class Ports(
        stories: (ProductFactoryStoryFilter) -> List<ProductFactoryStoryStatusView> = { emptyList() },
        existingAttachments: List<TrackerAttachment> = emptyList(),
        attachmentBytes: Map<String, ByteArray> = emptyMap(),
    ) {
        val filters = mutableListOf<ProductFactoryStoryFilter>()
        val queries = object : StubDashboardQueries() {
            override fun productFactoryStories(filter: ProductFactoryStoryFilter): ProductFactoryStoriesPageData {
                filters += filter
                return ProductFactoryStoriesPageData(stories(filter))
            }
            override fun storyDetail(storyKey: String) = StoryDetailPageData(
                issue = DashboardApiFixtures.issue(storyKey),
                storyKey = storyKey, run = null, agentRuns = emptyList(), events = emptyList(), previewUrl = null, errors = emptyList(),
            )
        }
        val commands = StubDashboardCommands()
        val operations = StubFactoryOperations()
        val attachments = StubAttachmentPort(mapOf("SF-3000" to existingAttachments), attachmentBytes)
        val mvc = MockMvcBuilders.standaloneSetup(
            ProductFactoryIntegrationV2Controller(
                ProductFactoryIntegrationV2Service(
                    queries, commands, operations, StoryAttachmentStore(queries, attachments), StubVersion, DashboardApiFixtures.fakeSecrets(),
                ),
            ),
        ).setControllerAdvice(ProductFactoryIntegrationV2ErrorHandler()).build()
    }

    @Test
    fun `alle v2-routes vereisen het integratietoken`() {
        val ports = Ports()

        ports.mvc.perform(get("/api/integrations/v2/status"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.retryable").value(false))
        assertEquals(0, ports.filters.size)
    }

    @Test
    fun `story zonder attachments wordt na aanmaak gequeued`() {
        val ports = Ports()

        ports.mvc.perform(validPost(storyRequest()))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.storyKey").value("SF-3001"))
            .andExpect(jsonPath("$.created").value(true))
            .andExpect(jsonPath("$.status").value("OPEN"))

        assertEquals(listOf("createStory", "queueStory(SF-3001)"), ports.commands.calls)
        val create = ports.commands.createdStories.single()
        assertEquals(false, create.start)
        assertEquals(false, create.questionsAllowed)
        assertEquals("claude", create.aiSupplier)
        assertEquals("https://github.com/example/hkh.git", create.repo)
        val description = create.description!!
        assert(description.contains("Product-Factory-Api-Version: 2"))
        assert(description.contains("Product-Factory-Product-Id: hkh"))
        assert(description.contains("Product-Factory-Idempotency-Key: product-factory:hkh:story:1:v1"))
        assert(description.contains("Product-Factory-Package-Sha256:"))
        // Eerst op pakkethash, dan op sleutel gezocht.
        assertEquals(listOf(true, false), ports.filters.map { it.packageSha256 != null })
    }

    @Test
    fun `een BUGFIX-story van Product Factory wordt nooit als hotfix aangemaakt`() {
        val ports = Ports()

        ports.mvc.perform(validPost(storyRequestRaw("[]", type = "BUGFIX"))).andExpect(status().isCreated)

        assertEquals(false, ports.commands.createdStories.single().hotfix)
    }

    @Test
    fun `expliciete aiSupplier en aiModel worden doorgegeven aan story-create`() {
        val ports = Ports()

        ports.mvc.perform(validPost(storyRequestWithAiPreference(aiSupplier = "copilot", aiModel = "claude-sonnet-4.5")))
            .andExpect(status().isCreated)

        val create = ports.commands.createdStories.single()
        assertEquals("copilot", create.aiSupplier)
        assertEquals("claude-sonnet-4.5", create.aiModel)
    }

    @Test
    fun `onbekende aiSupplier wordt voor storyaanmaak geweigerd`() {
        val ports = Ports()

        ports.mvc.perform(validPost(storyRequestWithAiPreference(aiSupplier = "onbekend", aiModel = null)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_AI_SUPPLIER"))
        assertEquals(emptyList<String>(), ports.commands.calls)
    }

    @Test
    fun `attachments worden afzonderlijk opgeslagen voordat de story wordt gequeued`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val ports = Ports()
        ports.commands.nextStoryKey = "SF-3002"

        ports.mvc.perform(validPost(storyRequest(listOf(attachment("ux", "ux.png", "image/png", bytes)))))
            .andExpect(status().isCreated)

        assertEquals(listOf("createStory", "queueStory(SF-3002)"), ports.commands.calls)
        val upload = ports.attachments.uploads.single()
        assertEquals("SF-3002", upload.first)
        assertEquals("product-factory-input__ux__ux.png", upload.second)
        assert(upload.third.contentEquals(bytes))
    }

    @Test
    fun `retry hervat attachments maar queuet een actieve story niet opnieuw`() {
        val bytes = byteArrayOf(9, 8, 7)
        val ports = Ports(stories = { listOf(projection(needsQueue = false)) })

        ports.mvc.perform(validPost(storyRequest(listOf(attachment("ux", "ux.png", "image/png", bytes)))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.created").value(false))

        assertEquals(emptyList<String>(), ports.commands.calls)
        assertEquals(1, ports.attachments.uploads.size)
    }

    @Test
    fun `retry van gedeeltelijke ontvangst uploadt opnieuw en queuet daarna`() {
        val bytes = byteArrayOf(9, 8, 7)
        val ports = Ports(stories = { listOf(projection(needsQueue = true)) })

        ports.mvc.perform(validPost(storyRequest(listOf(attachment("ux", "ux.png", "image/png", bytes)))))
            .andExpect(status().isOk)

        assertEquals(1, ports.attachments.uploads.size)
        assertEquals(listOf("queueStory(SF-3000)"), ports.commands.calls)
    }

    @Test
    fun `dezelfde storyinhoud met een andere idempotentiesleutel gebruikt de eerste story`() {
        val ports = Ports(stories = { filter ->
            if (filter.packageSha256 != null) listOf(projection(packageSha256 = filter.packageSha256)) else emptyList()
        })

        ports.mvc.perform(validPost(storyRequest(), idempotencyKey = "product-factory:hkh:story:1:second-call"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.storyKey").value("SF-3000"))
            .andExpect(jsonPath("$.created").value(false))

        assertEquals(emptyList<String>(), ports.commands.calls)
    }

    @Test
    fun `ongeldige attachmenthash wordt voor storyaanmaak geweigerd`() {
        val ports = Ports()
        val invalid = """{
          "id":"ux","fileName":"ux.png","mediaType":"image/png","sizeBytes":3,
          "sha256":"${"0".repeat(64)}","contentBase64":"AQID"
        }"""

        ports.mvc.perform(validPost(storyRequestRaw("[$invalid]")))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("ATTACHMENT_HASH_MISMATCH"))
            .andExpect(jsonPath("$.retryable").value(false))
        assertEquals(0, ports.filters.size)
    }

    @Test
    fun `query ondersteunt product status en idempotentiesleutel zonder interne velden te lekken`() {
        val ports = Ports(stories = { listOf(projection(needsQueue = true)) })

        ports.mvc.perform(
            get("/api/integrations/v2/stories")
                .header("Authorization", "Bearer integration-secret")
                .param("productId", "hkh")
                .param("status", "open"),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].storyKey").value("SF-3000"))
            .andExpect(jsonPath("$.items[0].updatedAt").value("2026-08-24T14:30Z"))
            .andExpect(jsonPath("$.items[0].needsQueue").doesNotExist())
        assertEquals(ProductFactoryStoryFilter(productId = "hkh", status = "OPEN"), ports.filters.single())

        ports.mvc.perform(get("/api/integrations/v2/stories").header("Authorization", "Bearer integration-secret"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_QUERY"))
    }

    @Test
    fun `done zonder volledige commit wordt als ongeldige factoryresponse geweigerd`() {
        val ports = Ports(stories = { listOf(projection(status = "DONE", deliveredCommitSha = null)) })

        ports.mvc.perform(get("/api/integrations/v2/stories/SF-3000").header("Authorization", "Bearer integration-secret"))
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.code").value("INVALID_FACTORY_RESPONSE"))
            .andExpect(jsonPath("$.retryable").value(true))
    }

    @Test
    fun `open story kan met vrije reden voor annulering worden aangeboden`() {
        val ports = Ports(stories = { listOf(projection(status = "OPEN")) })

        ports.mvc.perform(
            post("/api/integrations/v2/stories/SF-3000/cancel")
                .header("Authorization", "Bearer integration-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"Epic moet verder worden uitgewerkt."}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.accepted").value(true))

        assertEquals(listOf("queueCommand(SF-3000,delete,Epic moet verder worden uitgewerkt.)"), ports.operations.calls)
    }

    @Test
    fun `een attachmentconflict geeft 409`() {
        val oldBytes = byteArrayOf(2)
        val existing = TrackerAttachment(id = "old", name = "product-factory-input__ux__ux.png", url = null, mimeType = "image/png", size = 1, created = 1L)
        val ports = Ports(
            stories = { listOf(projection(needsQueue = true)) },
            existingAttachments = listOf(existing),
            attachmentBytes = mapOf("old" to oldBytes),
        )

        ports.mvc.perform(validPost(storyRequest(listOf(attachment("ux", "ux.png", "image/png", byteArrayOf(1))))))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))
        assertEquals(emptyList<String>(), ports.commands.calls)
    }

    private fun validPost(body: String, idempotencyKey: String = "product-factory:hkh:story:1:v1") =
        post("/api/integrations/v2/stories")
            .header("Authorization", "Bearer integration-secret")
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)

    private fun storyRequest(attachments: List<String> = emptyList()) = storyRequestRaw(attachments.joinToString(prefix = "[", postfix = "]"))

    private fun storyRequestRaw(attachmentsJson: String, type: String = "PRODUCT_STORY") = """{
      "productId":"hkh",
      "sourceStoryId":"550e8400-e29b-41d4-a716-446655440000",
      "sourceStoryVersion":1,
      "type":"$type",
      "targetRepositoryUrl":"https://github.com/example/hkh.git",
      "title":"Toon lege afsprakenlijst",
      "description":"## Gedrag\\nToon de lege toestand.",
      "attachments":$attachmentsJson
    }"""

    private fun storyRequestWithAiPreference(aiSupplier: String?, aiModel: String?) = """{
      "productId":"hkh",
      "sourceStoryId":"550e8400-e29b-41d4-a716-446655440000",
      "sourceStoryVersion":1,
      "type":"PRODUCT_STORY",
      "targetRepositoryUrl":"https://github.com/example/hkh.git",
      "title":"Toon lege afsprakenlijst",
      "description":"## Gedrag\\nToon de lege toestand.",
      "attachments":[],
      "aiSupplier":${aiSupplier?.let { "\"$it\"" }},
      "aiModel":${aiModel?.let { "\"$it\"" }}
    }"""

    private fun attachment(id: String, fileName: String, mediaType: String, bytes: ByteArray): String {
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        return """{
          "id":"$id","fileName":"$fileName","mediaType":"$mediaType","sizeBytes":${bytes.size},
          "sha256":"$sha","contentBase64":"${Base64.getEncoder().encodeToString(bytes)}"
        }"""
    }

    private fun projection(
        needsQueue: Boolean = false,
        status: String = "OPEN",
        deliveredCommitSha: String? = null,
        packageSha256: String? = null,
    ) = ProductFactoryStoryStatusView(
        storyKey = "SF-3000",
        productId = "hkh",
        sourceStoryId = "550e8400-e29b-41d4-a716-446655440000",
        sourceStoryVersion = 1,
        packageSha256 = packageSha256,
        status = status,
        deliveredCommitSha = deliveredCommitSha,
        cancelReason = null,
        updatedAt = OffsetDateTime.parse("2026-08-24T14:30:00Z"),
        needsQueue = needsQueue,
    )
}
