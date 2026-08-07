package nl.vdzon.softwarefactory.dashboard.bridge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.contract.BridgeError
import nl.vdzon.softwarefactory.contract.BridgeParams
import nl.vdzon.softwarefactory.contract.BridgeResponse
import nl.vdzon.softwarefactory.dashboard.config.DashboardSecrets
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class ProductFactoryStoryRequest(
    val productSlug: String,
    val projectKey: String? = null,
    val title: String,
    val description: String,
    val repo: String,
    val aiSupplier: String? = null,
    val aiModel: String? = null,
    val workspaceRunId: String,
    val workspaceCommitSha: String,
    val artifactPath: String,
    val deliveryMode: String = "start-next",
)

data class ProductFactoryStoryResponse(
    val storyKey: String,
    val created: Boolean,
    val deliveryMode: String,
)

data class ProductFactoryAnswerRequest(
    val targetType: String,
    val targetKey: String,
    val phase: String,
    val answer: String,
)

/**
 * Klein machine-tot-machine contract tussen Product Factory en Software Factory.
 * De dashboard-API blijft Google-auth gebruiken; deze route accepteert alleen het aparte,
 * minimaal gescopeerde integratietoken. Idempotentie leeft duurzaam in de storybeschrijving,
 * zodat ook een retry na een time-out geen dubbele story kan maken.
 */
@RestController
@RequestMapping("/api/integrations/v1")
class ProductFactoryIntegrationApi(
    private val hub: BridgeHub,
    private val secrets: DashboardSecrets,
) {
    private val mapper = jacksonObjectMapper()
    private val createLock = Any()

    @GetMapping("/status")
    fun status(@RequestHeader("Authorization", required = false) authorization: String?): Map<String, Any?> {
        authorize(authorization)
        return mapOf("connected" to hub.isConnected(), "factoryVersion" to hub.factoryVersion())
    }

    @PostMapping("/stories")
    fun createStory(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody body: ProductFactoryStoryRequest,
    ): ResponseEntity<Any> {
        authorize(authorization)
        val key = idempotencyKey?.trim().orEmpty()
        require(key.matches(Regex("[A-Za-z0-9._:-]{8,160}"))) { "Ongeldige of ontbrekende Idempotency-Key" }
        require(body.productSlug.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "Ongeldige productslug" }
        require(body.title.isNotBlank() && body.description.isNotBlank() && body.repo.isNotBlank()) { "Titel, omschrijving en repo zijn verplicht" }
        require(body.workspaceCommitSha.matches(Regex("[a-fA-F0-9]{7,64}"))) { "Ongeldige workspace commit-SHA" }
        require(body.deliveryMode in setOf("draft", "start-next")) { "deliveryMode moet draft of start-next zijn" }

        return synchronized(createLock) {
            existingStory(key)?.let { existing ->
                val storyKey = existing.path("key").asText()
                val phase = existing.path("fields").path("storyPhase").asText()
                if (body.deliveryMode == "start-next" && phase.isBlank()) {
                    dispatch("story.queue", BridgeParams.strings("storyKey" to storyKey))
                }
                return@synchronized ResponseEntity.ok(ProductFactoryStoryResponse(storyKey, false, body.deliveryMode))
            }
            val marker = marker(key)
            val description = """
                ${body.description.trim()}

                Product Factory-context:
                - product: ${body.productSlug}
                - workspace run: ${body.workspaceRunId}
                - workspace commit: ${body.workspaceCommitSha}
                - artefact: ${body.artifactPath}

                $marker
            """.trimIndent()
            val params = mapper.createObjectNode()
                .put("title", body.title.trim())
                .put("description", description)
                .put("repo", body.repo.trim())
                .put("start", false)
                .put("questionsAllowed", true)
                .put("approvalMode", "automatisch")
                .put("hotfix", false)
            params.putArray("notificationEvents").also { array ->
                setOf("DEPLOYED", "QUESTION", "MANUAL_ACTION_REQUIRED", "ERROR").forEach(array::add)
            }
            body.projectKey?.takeIf { it.isNotBlank() }?.let { params.put("projectKey", it) }
            body.aiSupplier?.takeIf { it.isNotBlank() }?.let { params.put("aiSupplier", it) }
            body.aiModel?.takeIf { it.isNotBlank() && it != "default" }?.let { params.put("aiModel", it) }
            val created = dispatch("story.create", params)
            val storyKey = created.path("key").asText().ifBlank { error("Software Factory gaf geen story key terug") }
            if (body.deliveryMode == "start-next") {
                dispatch("story.queue", BridgeParams.strings("storyKey" to storyKey))
            }
            ResponseEntity.status(HttpStatus.CREATED).body(ProductFactoryStoryResponse(storyKey, true, body.deliveryMode))
        }
    }

    @GetMapping("/stories/{storyKey}")
    fun story(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
    ): ResponseEntity<Any> {
        authorize(authorization)
        return respond(hub.safeDispatch("story.detail", BridgeParams.strings("storyKey" to storyKey)))
    }

    @PostMapping("/stories/{storyKey}/answers")
    fun answer(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
        @RequestBody body: ProductFactoryAnswerRequest,
    ): ResponseEntity<Any> {
        authorize(authorization)
        require(body.answer.isNotBlank()) { "Antwoord is verplicht" }
        val operation = when (body.targetType) {
            "story" -> {
                require(body.targetKey == storyKey) { "Story target hoort niet bij het pad" }
                require(body.phase in STORY_ANSWER_PHASES) { "Ongeldige antwoordfase voor story" }
                "story.setStoryPhase"
            }
            "subtask" -> {
                require(body.phase in SUBTASK_ANSWER_PHASES) { "Ongeldige antwoordfase voor subtask" }
                "subtask.setPhase"
            }
            else -> throw IllegalArgumentException("targetType moet story of subtask zijn")
        }
        val keyName = if (body.targetType == "story") "storyKey" else "subtaskKey"
        val params = BridgeParams.strings(keyName to body.targetKey, "phase" to body.phase, "comment" to body.answer.trim())
        return respond(hub.safeDispatch(operation, params))
    }

    private fun existingStory(idempotencyKey: String): JsonNode? {
        val list = dispatch("stories.list")
        val issues = if (list.isArray) list else list.path("issues")
        return issues.firstOrNull { it.path("description").asText().contains(marker(idempotencyKey)) }
            ?.takeIf { it.path("key").asText().isNotBlank() }
    }

    private fun marker(key: String) = "Product-Factory-Idempotency-Key: $key"

    private fun authorize(header: String?) {
        val expected = secrets.productFactoryToken
        val supplied = header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim().orEmpty()
        if (expected.isBlank() || !MessageDigest.isEqual(supplied.toByteArray(StandardCharsets.UTF_8), expected.toByteArray(StandardCharsets.UTF_8))) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid integration token")
        }
    }

    private fun dispatch(operation: String, params: JsonNode? = null): JsonNode {
        val response = hub.safeDispatch(operation, params)
        if (!response.ok) throw ResponseStatusException(statusFor(response.error?.code), response.error?.message ?: "Software Factory-fout")
        return response.body ?: mapper.createObjectNode()
    }

    private fun respond(response: BridgeResponse): ResponseEntity<Any> =
        if (response.ok) ResponseEntity.ok(response.body ?: mapper.createObjectNode())
        else ResponseEntity.status(statusFor(response.error?.code)).body(response.error)

    private fun statusFor(code: String?): HttpStatus = when (code) {
        "FACTORY_OFFLINE" -> HttpStatus.SERVICE_UNAVAILABLE
        "NOT_FOUND" -> HttpStatus.NOT_FOUND
        "INVALID_PARAMS" -> HttpStatus.BAD_REQUEST
        else -> HttpStatus.BAD_GATEWAY
    }

    private companion object {
        val STORY_ANSWER_PHASES = setOf("questions-answered", "planning-questions-answered")
        val SUBTASK_ANSWER_PHASES = setOf(
            "development-questions-answered", "review-questions-answered", "test-questions-answered",
            "summary-questions-answered", "documentation-questions-answered", "manual-action-done",
        )
    }
}

private fun BridgeHub.safeDispatch(operation: String, params: JsonNode? = null): BridgeResponse =
    try {
        sendRequest(operation, params)
    } catch (offline: FactoryOfflineException) {
        BridgeResponse(id = "", ok = false, error = BridgeError("FACTORY_OFFLINE", offline.message ?: "Geen factory verbonden"))
    }
