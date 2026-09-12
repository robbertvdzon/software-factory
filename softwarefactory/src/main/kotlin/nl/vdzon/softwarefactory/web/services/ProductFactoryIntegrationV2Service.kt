package nl.vdzon.softwarefactory.web.services

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.core.contracts.FactoryCommand
import nl.vdzon.softwarefactory.core.contracts.FactoryOperations
import nl.vdzon.softwarefactory.core.contracts.NotificationEvent
import nl.vdzon.softwarefactory.core.contracts.ProductFactoryAttachmentNames
import nl.vdzon.softwarefactory.core.contracts.ProductFactoryMetadata
import nl.vdzon.softwarefactory.dashboard.DashboardCommands
import nl.vdzon.softwarefactory.dashboard.DashboardQueries
import nl.vdzon.softwarefactory.dashboard.FactoryVersionQuery
import nl.vdzon.softwarefactory.dashboard.models.CreateStoryCommand
import nl.vdzon.softwarefactory.dashboard.models.ProductFactoryStoryFilter
import nl.vdzon.softwarefactory.dashboard.models.ProductFactoryStoryStatusView
import nl.vdzon.softwarefactory.web.controllers.AttachmentConflictException
import nl.vdzon.softwarefactory.web.controllers.DashboardNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
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

class ProductFactoryV2Exception(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val retryable: Boolean,
) : RuntimeException(message)

/**
 * Contract, validatie en idempotentie van de Product Factory v2-integratie. Een verzoek wordt
 * herkend aan de idempotentiesleutel én aan de pakkethash van de inhoud; beide staan als marker
 * in de storyomschrijving (zie [ProductFactoryMetadata]) zodat een retry na een time-out nooit
 * een tweede story oplevert.
 */
@Service
class ProductFactoryIntegrationV2Service(
    private val dashboard: DashboardQueries,
    private val commands: DashboardCommands,
    private val operations: FactoryOperations,
    private val attachmentStore: StoryAttachmentStore,
    private val version: FactoryVersionQuery,
    private val secrets: FactorySecrets,
) {
    private val createLock = Any()

    fun status(authorization: String?): ProductFactoryV2StatusResponse {
        authorize(authorization)
        return ProductFactoryV2StatusResponse(connected = true, factoryVersion = version.commitShort())
    }

    fun createStory(
        authorization: String?,
        idempotencyKey: String?,
        body: ProductFactoryV2StoryRequest,
    ): ResponseEntity<ProductFactoryV2CreateResponse> {
        authorize(authorization)
        val key = validateIdempotencyKey(idempotencyKey)
        val validated = ProductFactoryV2Validator.validate(body)
        return synchronized(createLock) {
            val hashMatches = projectedStories(ProductFactoryStoryFilter(packageSha256 = validated.packageSha256))
            val keyMatches = projectedStories(ProductFactoryStoryFilter(idempotencyKey = key))
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
                factory { commands.queueStory(story.storyKey) }
            }
            val response = ProductFactoryV2CreateResponse(story.storyKey, created, story.status)
            ResponseEntity.status(if (created) HttpStatus.CREATED else HttpStatus.OK).body(response)
        }
    }

    fun story(authorization: String?, storyKey: String): ProductFactoryV2StoryResponse {
        authorize(authorization)
        requireStoryKey(storyKey)
        return projectedStories(ProductFactoryStoryFilter(storyKey = storyKey)).singleOrNull()?.public()
            ?: fail(HttpStatus.NOT_FOUND, "STORY_NOT_FOUND", "Story $storyKey is niet gevonden.")
    }

    fun stories(
        authorization: String?,
        productId: String?,
        status: String?,
        idempotencyKey: String?,
    ): ProductFactoryV2StoriesResponse {
        authorize(authorization)
        if (productId == null && idempotencyKey == null) {
            fail(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "productId of idempotencyKey is verplicht.")
        }
        val normalizedProductId = productId?.let(ProductFactoryV2Validator::validateProductId)
        val normalizedIdempotencyKey = idempotencyKey?.let(::validateIdempotencyKey)
        val normalizedStatus = status?.trim()?.uppercase()?.also {
            if (it !in PUBLIC_STATUSES) fail(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "Status moet OPEN, DONE of CANCELLED zijn.")
        }
        val filter = ProductFactoryStoryFilter(
            productId = normalizedProductId,
            idempotencyKey = normalizedIdempotencyKey,
            status = normalizedStatus,
        )
        return ProductFactoryV2StoriesResponse(projectedStories(filter).map(ProductFactoryStoryStatusView::public))
    }

    fun cancelStory(
        authorization: String?,
        storyKey: String,
        body: ProductFactoryV2CancelRequest,
    ): ProductFactoryV2CancelResponse {
        authorize(authorization)
        requireStoryKey(storyKey)
        val reason = body.reason.trim()
        if (reason.length !in CANCEL_REASON_LENGTH) fail(HttpStatus.BAD_REQUEST, "INVALID_CANCEL_REASON", "Een begrensde annuleringsreden is verplicht.")
        val story = projectedStories(ProductFactoryStoryFilter(storyKey = storyKey)).singleOrNull()
            ?: fail(HttpStatus.NOT_FOUND, "STORY_NOT_FOUND", "Story $storyKey is niet gevonden.")
        if (story.status == "DONE") fail(HttpStatus.CONFLICT, "STORY_ALREADY_DONE", "Een opgeleverde story wordt niet automatisch teruggedraaid.")
        if (story.status != "CANCELLED") {
            factory { operations.queueCommand(storyKey, FactoryCommand.DELETE, reason) }
        }
        return ProductFactoryV2CancelResponse(true)
    }

    private fun create(
        body: ProductFactoryV2StoryRequest,
        validated: ValidatedStoryRequest,
        idempotencyKey: String,
    ): ProductFactoryStoryStatusView {
        val description = "${body.description.trim()}\n\n${ProductFactoryMetadata.block(validated.productId, validated.sourceStoryId, body.sourceStoryVersion, idempotencyKey, validated.packageSha256)}"
        val created = factory {
            commands.createStory(
                CreateStoryCommand(
                    projectKey = null,
                    title = body.title.trim(),
                    description = description,
                    repo = validated.repositoryUrl,
                    aiSupplier = validated.aiSupplier ?: "claude",
                    aiModel = validated.aiModel,
                    start = false,
                    questionsAllowed = false,
                    // Product Factory's "BUGFIX" storytype beschrijft de AARD van de wijziging (een fix i.p.v.
                    // een nieuwe feature), niet urgentie. Software Factory's hotfix-modus is een apart, bewust
                    // handmatig te kiezen spoor dat review, tests en documentatie overslaat voor echte
                    // productie-incidenten — dat mag nooit automatisch aan de hand van storytype worden gezet.
                    hotfix = false,
                    approvalMode = "automatisch",
                    notificationEvents = NotificationEvent.DEFAULT,
                ),
            )
        }
        val storyKey = created.key.takeIf(String::isNotBlank)
            ?: fail(HttpStatus.BAD_GATEWAY, "INVALID_FACTORY_RESPONSE", "Software Factory gaf geen storykey terug.", true)
        return ProductFactoryStoryStatusView(
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
        try {
            attachmentStore.put(storyKey, attachment.storageName, attachment.mediaType, attachment.sha256, attachment.bytes)
        } catch (conflict: AttachmentConflictException) {
            fail(HttpStatus.CONFLICT, "CONFLICT", conflict.message.orEmpty())
        } catch (notFound: DashboardNotFoundException) {
            fail(HttpStatus.NOT_FOUND, "NOT_FOUND", notFound.message.orEmpty())
        } catch (invalid: IllegalArgumentException) {
            fail(HttpStatus.BAD_REQUEST, "INVALID_PARAMS", invalid.message.orEmpty())
        }
    }

    private fun projectedStories(filter: ProductFactoryStoryFilter): List<ProductFactoryStoryStatusView> =
        factory { dashboard.productFactoryStories(filter).items }.onEach(::validateProjection)

    private fun validateProjection(story: ProductFactoryStoryStatusView) {
        if (story.sourceStoryVersion <= 0 || story.status !in PUBLIC_STATUSES) {
            fail(HttpStatus.BAD_GATEWAY, "INVALID_FACTORY_RESPONSE", "Software Factory gaf een ongeldige storystatus terug.", true)
        }
        if (story.status == "DONE" && story.deliveredCommitSha?.matches(FULL_GIT_SHA) != true) {
            fail(HttpStatus.BAD_GATEWAY, "INVALID_FACTORY_RESPONSE", "DONE-status mist een volledige oplevercommit.", true)
        }
    }

    /**
     * Voert een factory-aanroep uit en vertaalt fouten naar het v2-foutcontract: ongeldige invoer is
     * een 400 (een retry faalt opnieuw), al het andere een 500 die Product Factory mag herhalen.
     */
    private fun <T> factory(call: () -> T): T =
        try {
            call()
        } catch (exception: ProductFactoryV2Exception) {
            throw exception
        } catch (invalid: IllegalArgumentException) {
            fail(HttpStatus.BAD_REQUEST, "INVALID_PARAMS", invalid.message ?: "Ongeldige invoer.")
        } catch (exception: Exception) {
            fail(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", exception.message ?: "Onbekende fout", true)
        }

    private fun authorize(header: String?) {
        val expected = secrets.productFactoryToken.orEmpty()
        if (expected.isBlank()) fail(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Ongeldig integratietoken.")
        val supplied = header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim().orEmpty()
        val matches = MessageDigest.isEqual(
            supplied.toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )
        if (!matches) fail(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Ongeldig integratietoken.")
    }

    private companion object {
        val CANCEL_REASON_LENGTH = 5..1000
    }
}

private fun ProductFactoryStoryStatusView.public() = ProductFactoryV2StoryResponse(
    storyKey, productId, sourceStoryId, sourceStoryVersion, status, deliveredCommitSha, cancelReason, updatedAt?.toString(),
)

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
     * Bekende legacy suppliers uit het bestaande Product Factory-contract. De daadwerkelijke
     * uitvoering wordt gekozen via de Runtime-configuratie per agentrol. Het model zelf blijft
     * onuitgevalideerde vrije tekst, exact zoals `DashboardCommandService.createStory` het al
     * accepteert.
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

private fun fail(status: HttpStatus, code: String, message: String, retryable: Boolean = false): Nothing =
    throw ProductFactoryV2Exception(status, code, message, retryable)

private fun validateIdempotencyKey(value: String?): String {
    val key = value?.trim().orEmpty()
    if (!key.matches(IDEMPOTENCY_KEY)) fail(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "Ongeldige of ontbrekende Idempotency-Key.")
    return key
}

private fun requireStoryKey(value: String) {
    if (!value.matches(STORY_KEY)) fail(HttpStatus.BAD_REQUEST, "INVALID_STORY_KEY", "Ongeldige storykey.")
}

private val IDEMPOTENCY_KEY = Regex("[A-Za-z0-9._:-]{8,160}")
private val PRODUCT_ID = Regex("[A-Za-z0-9._:-]{1,160}")
private val STORY_KEY = Regex("[A-Za-z0-9._-]{1,80}")
private val ATTACHMENT_ID = Regex("[A-Za-z0-9._-]+")
private val SHA_256 = Regex("[a-f0-9]{64}")
private val FULL_GIT_SHA = Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")
private val STORY_TYPES = setOf("PRODUCT_STORY", "BUGFIX")
private val PUBLIC_STATUSES = setOf("OPEN", "DONE", "CANCELLED")
