package nl.vdzon.softwarefactory.dashboard.bridge

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.contract.BridgeParams
import nl.vdzon.softwarefactory.contract.BridgeResponse
import nl.vdzon.softwarefactory.contract.ProductFactoryAttachmentNames
import nl.vdzon.softwarefactory.contract.ProductFactoryMetadata
import nl.vdzon.softwarefactory.dashboard.config.DashboardSecrets
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

data class ProductFactoryV2AttachmentRequest(
    val id: String,
    val fileName: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
    val contentBase64: String,
)

data class ProductFactoryV2StoryRequest(
    val productId: String,
    val sourceStoryId: String,
    val sourceStoryVersion: Long,
    val type: String,
    val targetRepositoryUrl: String,
    val title: String,
    val description: String,
    val attachments: List<ProductFactoryV2AttachmentRequest> = emptyList(),
    val aiSupplier: String? = null,
    val aiModel: String? = null,
)

data class ProductFactoryV2StatusResponse(
    val connected: Boolean,
    val factoryVersion: String?,
    val apiVersion: String = "2",
)

data class ProductFactoryV2CreateResponse(
    val storyKey: String,
    val created: Boolean,
    val status: String,
)

data class ProductFactoryV2StoryResponse(
    val storyKey: String,
    val productId: String,
    val sourceStoryId: String,
    val sourceStoryVersion: Long,
    val status: String,
    val deliveredCommitSha: String?,
    val cancelReason: String?,
    val updatedAt: String?,
)

data class ProductFactoryV2StoriesResponse(val items: List<ProductFactoryV2StoryResponse>)
data class ProductFactoryV2CancelRequest(val reason: String)
data class ProductFactoryV2CancelResponse(val accepted: Boolean)

data class ProductFactoryV2ErrorResponse(
    val code: String,
    val message: String,
    val retryable: Boolean,
)

@RestController
@RequestMapping("/api/integrations/v2")
class ProductFactoryIntegrationV2Api(
    private val service: ProductFactoryIntegrationV2Service,
) {
    @GetMapping("/status")
    fun status(@RequestHeader("Authorization", required = false) authorization: String?) =
        service.status(authorization)

    @PostMapping("/stories")
    fun createStory(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody body: ProductFactoryV2StoryRequest,
    ) = service.createStory(authorization, idempotencyKey, body)

    @GetMapping("/stories/{storyKey}")
    fun story(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
    ) = service.story(authorization, storyKey)

    @GetMapping("/stories")
    fun stories(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestParam(required = false) productId: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) idempotencyKey: String?,
    ) = service.stories(authorization, productId, status, idempotencyKey)

    @PostMapping("/stories/{storyKey}/cancel")
    fun cancelStory(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
        @RequestBody body: ProductFactoryV2CancelRequest,
    ) = service.cancelStory(authorization, storyKey, body)
}

@Service
class ProductFactoryIntegrationV2Service(
    private val hub: BridgeHub,
    private val secrets: DashboardSecrets,
) {
    private val mapper = jacksonObjectMapper()
    private val createLock = Any()

    fun status(authorization: String?): ProductFactoryV2StatusResponse {
        secrets.authorizeProductFactory(authorization)
        return ProductFactoryV2StatusResponse(hub.isConnected(), hub.factoryVersion())
    }

    fun createStory(
        authorization: String?,
        idempotencyKey: String?,
        body: ProductFactoryV2StoryRequest,
    ): ResponseEntity<ProductFactoryV2CreateResponse> {
        secrets.authorizeProductFactory(authorization)
        val key = validateIdempotencyKey(idempotencyKey)
        val validated = ProductFactoryV2Validator.validate(body)
        return synchronized(createLock) {
            val hashMatches = projectedStories(params("packageSha256" to validated.packageSha256))
            val keyMatches = projectedStories(params("idempotencyKey" to key))
            if (hashMatches.size > 1 || keyMatches.size > 1) {
                fail(HttpStatus.BAD_GATEWAY, "INVALID_FACTORY_RESPONSE", "Meerdere stories hebben dezelfde idempotentiesleutel.", true)
            }
            val existingByHash = hashMatches.singleOrNull()
            val existingByKey = keyMatches.singleOrNull()
            if (existingByHash != null && existingByKey != null && existingByHash.storyKey != existingByKey.storyKey) {
                fail(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Idempotentiesleutel en pakkethash verwijzen naar verschillende stories.")
            }
            if (existingByKey?.packageSha256 != null && existingByKey.packageSha256 != validated.packageSha256) {
                fail(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "De idempotentiesleutel is al gebruikt voor andere storyinhoud.")
            }
            val existing = existingByHash ?: existingByKey
            val created = existing == null
            val story = existing ?: create(body, validated, key)
            validated.attachments.forEach { putAttachment(story.storyKey, it) }
            if (story.needsQueue) {
                dispatch("story.queue", BridgeParams.strings("storyKey" to story.storyKey))
            }
            val response = ProductFactoryV2CreateResponse(story.storyKey, created, story.status)
            ResponseEntity.status(if (created) HttpStatus.CREATED else HttpStatus.OK).body(response)
        }
    }

    fun story(authorization: String?, storyKey: String): ProductFactoryV2StoryResponse {
        secrets.authorizeProductFactory(authorization)
        requireStoryKey(storyKey)
        return projectedStories(params("storyKey" to storyKey)).singleOrNull()?.public()
            ?: fail(HttpStatus.NOT_FOUND, "STORY_NOT_FOUND", "Story $storyKey is niet gevonden.")
    }

    fun stories(
        authorization: String?,
        productId: String?,
        status: String?,
        idempotencyKey: String?,
    ): ProductFactoryV2StoriesResponse {
        secrets.authorizeProductFactory(authorization)
        if (productId == null && idempotencyKey == null) {
            fail(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "productId of idempotencyKey is verplicht.")
        }
        val normalizedProductId = productId?.let(ProductFactoryV2Validator::validateProductId)
        val normalizedIdempotencyKey = idempotencyKey?.let(::validateIdempotencyKey)
        val normalizedStatus = status?.trim()?.uppercase()?.also {
            if (it !in PUBLIC_STATUSES) fail(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "Status moet OPEN, DONE of CANCELLED zijn.")
        }
        val query = mapper.createObjectNode().apply {
            normalizedProductId?.let { put("productId", it) }
            normalizedIdempotencyKey?.let { put("idempotencyKey", it) }
            normalizedStatus?.let { put("status", it) }
        }
        return ProductFactoryV2StoriesResponse(projectedStories(query).map(BridgeStoryProjection::public))
    }

    fun cancelStory(
        authorization: String?,
        storyKey: String,
        body: ProductFactoryV2CancelRequest,
    ): ProductFactoryV2CancelResponse {
        secrets.authorizeProductFactory(authorization)
        requireStoryKey(storyKey)
        val reason = body.reason.trim()
        if (reason.length !in 5..1000) fail(HttpStatus.BAD_REQUEST, "INVALID_CANCEL_REASON", "Een begrensde annuleringsreden is verplicht.")
        val story = projectedStories(params("storyKey" to storyKey)).singleOrNull()
            ?: fail(HttpStatus.NOT_FOUND, "STORY_NOT_FOUND", "Story $storyKey is niet gevonden.")
        if (story.status == "DONE") fail(HttpStatus.CONFLICT, "STORY_ALREADY_DONE", "Een opgeleverde story wordt niet automatisch teruggedraaid.")
        if (story.status != "CANCELLED") {
            dispatch("story.command", mapper.createObjectNode().put("storyKey", storyKey).put("command", "delete").put("reason", reason))
        }
        return ProductFactoryV2CancelResponse(true)
    }

    private fun create(
        body: ProductFactoryV2StoryRequest,
        validated: ValidatedStoryRequest,
        idempotencyKey: String,
    ): BridgeStoryProjection {
        val description = "${body.description.trim()}\n\n${ProductFactoryMetadata.block(validated.productId, validated.sourceStoryId, body.sourceStoryVersion, idempotencyKey, validated.packageSha256)}"
        val createParams = mapper.createObjectNode()
            .put("title", body.title.trim())
            .put("description", description)
            .put("repo", validated.repositoryUrl)
            .put("aiSupplier", validated.aiSupplier ?: "claude")
            .put("start", false)
            .put("questionsAllowed", false)
            .put("approvalMode", "automatisch")
            // Product Factory's "BUGFIX" storytype beschrijft de AARD van de wijziging (een fix i.p.v.
            // een nieuwe feature), niet urgentie. Software Factory's hotfix-modus is een apart, bewust
            // handmatig te kiezen spoor dat review, tests en documentatie overslaat voor echte
            // productie-incidenten — dat mag nooit automatisch aan de hand van storytype worden gezet.
            .put("hotfix", false)
        validated.aiModel?.let { createParams.put("aiModel", it) }
        val created = dispatch("story.create", createParams)
        val storyKey = created.path("key").asText().takeIf(String::isNotBlank)
            ?: fail(HttpStatus.BAD_GATEWAY, "INVALID_FACTORY_RESPONSE", "Software Factory gaf geen storykey terug.", true)
        return BridgeStoryProjection(
            storyKey = storyKey,
            productId = validated.productId,
            sourceStoryId = validated.sourceStoryId,
            sourceStoryVersion = body.sourceStoryVersion,
            packageSha256 = validated.packageSha256,
            status = "OPEN",
            deliveredCommitSha = null,
            cancelReason = null,
            updatedAt = null,
            needsQueue = true,
        )
    }

    private fun putAttachment(storyKey: String, attachment: ValidatedAttachment) {
        val params = mapper.createObjectNode()
            .put("storyKey", storyKey)
            .put("name", attachment.storageName)
            .put("mediaType", attachment.mediaType)
            .put("sha256", attachment.sha256)
            .put("base64", Base64.getEncoder().encodeToString(attachment.bytes))
        dispatch("story.attachment.put", params)
    }

    private fun projectedStories(params: ObjectNode): List<BridgeStoryProjection> {
        val body = dispatch("productFactory.stories", params)
        val items = body.path("items")
        if (!items.isArray) fail(HttpStatus.BAD_GATEWAY, "INVALID_FACTORY_RESPONSE", "Software Factory gaf geen geldige storylijst terug.", true)
        return items.map(::projection)
    }

    private fun projection(node: JsonNode): BridgeStoryProjection {
        val result = BridgeStoryProjection(
            storyKey = node.requiredText("storyKey"),
            productId = node.requiredText("productId"),
            sourceStoryId = node.requiredText("sourceStoryId"),
            sourceStoryVersion = node.path("sourceStoryVersion").asLong(0),
            packageSha256 = node.nullableText("packageSha256"),
            status = node.requiredText("status"),
            deliveredCommitSha = node.nullableText("deliveredCommitSha"),
            cancelReason = node.nullableText("cancelReason"),
            updatedAt = node.nullableText("updatedAt"),
            needsQueue = node.path("needsQueue").asBoolean(false),
        )
        if (result.sourceStoryVersion <= 0 || result.status !in PUBLIC_STATUSES) {
            fail(HttpStatus.BAD_GATEWAY, "INVALID_FACTORY_RESPONSE", "Software Factory gaf een ongeldige storystatus terug.", true)
        }
        if (result.status == "DONE" && result.deliveredCommitSha?.matches(FULL_GIT_SHA) != true) {
            fail(HttpStatus.BAD_GATEWAY, "INVALID_FACTORY_RESPONSE", "DONE-status mist een volledige oplevercommit.", true)
        }
        return result
    }

    private fun dispatch(operation: String, params: JsonNode? = null): JsonNode {
        val response = try {
            hub.sendRequest(operation, params)
        } catch (_: FactoryOfflineException) {
            fail(HttpStatus.SERVICE_UNAVAILABLE, "FACTORY_OFFLINE", "Software Factory is niet verbonden.", true)
        }
        if (!response.ok) throw response.toProductFactoryV2Exception()
        return response.body ?: mapper.createObjectNode()
    }
}

@RestControllerAdvice(assignableTypes = [ProductFactoryIntegrationV2Api::class])
class ProductFactoryIntegrationV2ErrorHandler {
    @ExceptionHandler(ProductFactoryV2Exception::class)
    fun productFactoryError(exception: ProductFactoryV2Exception): ResponseEntity<ProductFactoryV2ErrorResponse> =
        ResponseEntity.status(exception.status).body(ProductFactoryV2ErrorResponse(exception.code, exception.message, exception.retryable))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableRequest(): ResponseEntity<ProductFactoryV2ErrorResponse> =
        ResponseEntity.badRequest().body(ProductFactoryV2ErrorResponse("INVALID_REQUEST", "Ongeldige JSON-requestbody.", false))
}

private data class BridgeStoryProjection(
    val storyKey: String,
    val productId: String,
    val sourceStoryId: String,
    val sourceStoryVersion: Long,
    val packageSha256: String?,
    val status: String,
    val deliveredCommitSha: String?,
    val cancelReason: String?,
    val updatedAt: String?,
    val needsQueue: Boolean,
) {
    fun public() = ProductFactoryV2StoryResponse(
        storyKey, productId, sourceStoryId, sourceStoryVersion, status, deliveredCommitSha, cancelReason, updatedAt,
    )
}

private data class ValidatedStoryRequest(
    val productId: String,
    val sourceStoryId: String,
    val repositoryUrl: String,
    val attachments: List<ValidatedAttachment>,
    val packageSha256: String,
    val aiSupplier: String?,
    val aiModel: String?,
)

private data class ValidatedAttachment(
    val id: String,
    val fileName: String,
    val storageName: String,
    val mediaType: String,
    val sha256: String,
    val bytes: ByteArray,
)

private object ProductFactoryV2Validator {
    fun validate(body: ProductFactoryV2StoryRequest): ValidatedStoryRequest {
        val productId = validateProductId(body.productId)
        val sourceStoryId = runCatching { UUID.fromString(body.sourceStoryId.trim()).toString() }
            .getOrElse { fail(HttpStatus.BAD_REQUEST, "INVALID_SOURCE_STORY_ID", "sourceStoryId moet een UUID zijn.") }
        if (body.sourceStoryVersion <= 0) fail(HttpStatus.BAD_REQUEST, "INVALID_SOURCE_STORY_VERSION", "sourceStoryVersion moet positief zijn.")
        if (body.type !in STORY_TYPES) fail(HttpStatus.BAD_REQUEST, "INVALID_STORY_TYPE", "type moet PRODUCT_STORY of BUGFIX zijn.")
        val repositoryUrl = validateRepositoryUrl(body.targetRepositoryUrl)
        validateTitle(body.title)
        if (body.description.isBlank()) fail(HttpStatus.BAD_REQUEST, "INVALID_DESCRIPTION", "description is verplicht.")
        val attachments = body.attachments.map(::validateAttachment)
        val attachmentIdsAreUnique = body.attachments.map { it.id }.toSet().size == body.attachments.size
        val storageNamesAreUnique = attachments.map { it.storageName }.toSet().size == attachments.size
        if (!attachmentIdsAreUnique || !storageNamesAreUnique) {
            fail(HttpStatus.BAD_REQUEST, "DUPLICATE_ATTACHMENT", "Attachment-ID's en bestandsnamen moeten uniek zijn.")
        }
        val aiSupplier = validateAiSupplier(body.aiSupplier)
        val aiModel = body.aiModel?.trim()?.takeIf(String::isNotBlank)
        val packageSha256 = packageSha256(body, productId, sourceStoryId, repositoryUrl, attachments, aiSupplier, aiModel)
        return ValidatedStoryRequest(productId, sourceStoryId, repositoryUrl, attachments, packageSha256, aiSupplier, aiModel)
    }

    /**
     * Zelfde bekende suppliers als [nl.vdzon.softwarefactory.core.contracts.AiRouting.bucket] —
     * bewust hier los gehouden (dashboard-backend heeft geen afhankelijkheid op de
     * `softwarefactory`-module, alleen op `factory-contracts`). Het model zelf blijft onuitgevalideerde
     * vrije tekst, exact zoals `DashboardCommandService.createStory` het al accepteert.
     */
    private fun validateAiSupplier(value: String?): String? {
        val supplier = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (supplier.lowercase() !in setOf("claude", "copilot", "openai", "mock")) {
            fail(HttpStatus.BAD_REQUEST, "INVALID_AI_SUPPLIER", "Onbekende aiSupplier.")
        }
        return supplier
    }

    fun validateProductId(value: String): String {
        val productId = value.trim()
        if (!productId.matches(PRODUCT_ID)) fail(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_ID", "Ongeldig productId.")
        return productId
    }

    private fun validateRepositoryUrl(value: String): String {
        val uri = runCatching { URI(value.trim()) }.getOrNull()
        val valid = uri != null && listOf(
            uri.scheme == "https",
            !uri.host.isNullOrBlank(),
            uri.userInfo == null,
            uri.query == null,
            uri.fragment == null,
        ).all { it }
        if (!valid) {
            fail(HttpStatus.BAD_REQUEST, "INVALID_REPOSITORY_URL", "targetRepositoryUrl moet een publieke HTTPS-URL zijn.")
        }
        return requireNotNull(uri).toString()
    }

    private fun validateAttachment(input: ProductFactoryV2AttachmentRequest): ValidatedAttachment {
        val id = input.id.trim()
        if (!id.matches(ATTACHMENT_ID) || id == "." || id == "..") {
            fail(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT_ID", "Ongeldig attachment-ID.")
        }
        val fileName = input.fileName.trim()
        if (!validFileName(fileName)) {
            fail(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT_NAME", "Ongeldige attachment-bestandsnaam.")
        }
        val mediaType = validateMediaType(input.mediaType)
        val expectedSha = validateAttachmentSha(id, input.sha256)
        val bytes = decodeAttachment(id, input)
        requireMatchingAttachment(id, input.sizeBytes, expectedSha, bytes)
        val storageName = ProductFactoryAttachmentNames.storageName(id, fileName)
        return ValidatedAttachment(id, fileName, storageName, mediaType, expectedSha, bytes)
    }

    private fun validateTitle(value: String) {
        val title = value.trim()
        val valid = title.isNotBlank() && !title.contains('\n') && !title.contains('\r')
        if (!valid) fail(HttpStatus.BAD_REQUEST, "INVALID_TITLE", "title moet een korte enkelregelige titel zijn.")
    }

    private fun validFileName(value: String): Boolean =
        value.isNotBlank() && !value.contains("..") &&
            !value.contains('/') && !value.contains('\\') && value.none(Char::isISOControl)

    private fun validateMediaType(value: String): String {
        val mediaType = value.trim().lowercase()
        if (mediaType.isBlank()) fail(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT_TYPE", "Attachment-MIME-type is verplicht.")
        return mediaType
    }

    private fun validateAttachmentSha(id: String, value: String): String {
        val sha = value.trim().lowercase()
        if (!sha.matches(SHA_256)) {
            fail(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT_HASH", "Ongeldige SHA-256 voor attachment $id.")
        }
        return sha
    }

    private fun decodeAttachment(id: String, input: ProductFactoryV2AttachmentRequest): ByteArray {
        if (input.sizeBytes < 0) fail(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT_SIZE", "Attachment $id heeft een ongeldige grootte.")
        return runCatching { Base64.getDecoder().decode(input.contentBase64) }
            .getOrElse {
                fail(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT_BASE64", "Ongeldige Base64 voor attachment $id.")
            }
    }

    private fun requireMatchingAttachment(id: String, size: Long, expectedSha: String, bytes: ByteArray) {
        if (bytes.size.toLong() != size) {
            fail(HttpStatus.BAD_REQUEST, "ATTACHMENT_SIZE_MISMATCH", "Attachment $id heeft niet de opgegeven grootte.")
        }
        val actualSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        if (actualSha != expectedSha) {
            fail(HttpStatus.BAD_REQUEST, "ATTACHMENT_HASH_MISMATCH", "Attachment $id heeft niet de opgegeven SHA-256.")
        }
    }

    private fun packageSha256(
        body: ProductFactoryV2StoryRequest,
        productId: String,
        sourceStoryId: String,
        repositoryUrl: String,
        attachments: List<ValidatedAttachment>,
        aiSupplier: String?,
        aiModel: String?,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        field(productId)
        field(sourceStoryId)
        field(body.sourceStoryVersion.toString())
        field(body.type)
        field(repositoryUrl)
        field(body.title.trim())
        field(body.description.trim())
        field(aiSupplier.orEmpty())
        field(aiModel.orEmpty())
        attachments.sortedBy { it.id }.forEach { attachment ->
            field(attachment.id)
            field(attachment.fileName)
            field(attachment.mediaType)
            field(attachment.sha256)
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}

class ProductFactoryV2Exception(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val retryable: Boolean,
) : RuntimeException(message)

private fun fail(status: HttpStatus, code: String, message: String, retryable: Boolean = false): Nothing =
    throw ProductFactoryV2Exception(status, code, message, retryable)

private fun DashboardSecrets.authorizeProductFactory(header: String?) {
    val expected = productFactoryToken
    if (expected.isBlank()) fail(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Ongeldig integratietoken.")
    val supplied = header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim().orEmpty()
    val matches = MessageDigest.isEqual(
        supplied.toByteArray(StandardCharsets.UTF_8),
        expected.toByteArray(StandardCharsets.UTF_8),
    )
    if (!matches) fail(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Ongeldig integratietoken.")
}

private fun BridgeResponse.toProductFactoryV2Exception(): ProductFactoryV2Exception {
    val errorCode = error?.code ?: "INVALID_FACTORY_RESPONSE"
    val errorMessage = error?.message ?: "Software Factory gaf een onbruikbaar antwoord."
    return when (errorCode) {
        "FACTORY_OFFLINE" -> ProductFactoryV2Exception(HttpStatus.SERVICE_UNAVAILABLE, errorCode, errorMessage, true)
        "TIMEOUT" -> ProductFactoryV2Exception(HttpStatus.BAD_GATEWAY, errorCode, errorMessage, true)
        "NOT_FOUND" -> ProductFactoryV2Exception(HttpStatus.NOT_FOUND, errorCode, errorMessage, false)
        "INVALID_PARAMS" -> ProductFactoryV2Exception(HttpStatus.BAD_REQUEST, errorCode, errorMessage, false)
        "TOO_LARGE" -> ProductFactoryV2Exception(HttpStatus.PAYLOAD_TOO_LARGE, errorCode, errorMessage, false)
        "CONFLICT" -> ProductFactoryV2Exception(HttpStatus.CONFLICT, errorCode, errorMessage, false)
        else -> ProductFactoryV2Exception(HttpStatus.BAD_GATEWAY, errorCode, errorMessage, true)
    }
}

private fun validateIdempotencyKey(value: String?): String {
    val key = value?.trim().orEmpty()
    if (!key.matches(IDEMPOTENCY_KEY)) fail(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "Ongeldige of ontbrekende Idempotency-Key.")
    return key
}

private fun requireStoryKey(value: String) {
    if (!value.matches(STORY_KEY)) fail(HttpStatus.BAD_REQUEST, "INVALID_STORY_KEY", "Ongeldige storykey.")
}

private fun params(vararg entries: Pair<String, String>): ObjectNode =
    jacksonObjectMapper().createObjectNode().apply { entries.forEach { (key, value) -> put(key, value) } }

private fun JsonNode.requiredText(field: String): String =
    path(field).takeIf { it.isTextual }?.asText()?.takeIf(String::isNotBlank)
        ?: fail(HttpStatus.BAD_GATEWAY, "INVALID_FACTORY_RESPONSE", "Software Factory-response mist veld $field.", true)

private fun JsonNode.nullableText(field: String): String? =
    path(field).takeUnless { it.isMissingNode || it.isNull }?.takeIf { it.isTextual }?.asText()
        ?: if (path(field).isMissingNode || path(field).isNull) null
        else fail(HttpStatus.BAD_GATEWAY, "INVALID_FACTORY_RESPONSE", "Software Factory-response heeft een ongeldig veld $field.", true)

private val IDEMPOTENCY_KEY = Regex("[A-Za-z0-9._:-]{8,160}")
private val PRODUCT_ID = Regex("[A-Za-z0-9._:-]{1,160}")
private val STORY_KEY = Regex("[A-Za-z0-9._-]{1,80}")
private val ATTACHMENT_ID = Regex("[A-Za-z0-9._-]+")
private val SHA_256 = Regex("[a-f0-9]{64}")
private val FULL_GIT_SHA = Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")
private val STORY_TYPES = setOf("PRODUCT_STORY", "BUGFIX")
private val PUBLIC_STATUSES = setOf("OPEN", "DONE", "CANCELLED")
