package nl.vdzon.softwarefactory.contract

/**
 * Kleine, gedeelde markerconventie voor Product Factory v2-stories. De dashboard-backend schrijft
 * deze regels in de storyomschrijving; de lokale factory gebruikt ze voor statusqueries en
 * idempotent herstel. Vrije storytekst is nooit een marker: alleen een volledige regel met exact
 * deze prefix wordt gelezen.
 */
object ProductFactoryMetadata {
    const val API_VERSION_PREFIX = "Product-Factory-Api-Version:"
    const val PRODUCT_ID_PREFIX = "Product-Factory-Product-Id:"
    const val SOURCE_STORY_ID_PREFIX = "Product-Factory-Source-Story-Id:"
    const val SOURCE_STORY_VERSION_PREFIX = "Product-Factory-Source-Story-Version:"
    const val IDEMPOTENCY_KEY_PREFIX = "Product-Factory-Idempotency-Key:"
    const val PACKAGE_SHA256_PREFIX = "Product-Factory-Package-Sha256:"

    fun block(
        productId: String,
        sourceStoryId: String,
        sourceStoryVersion: Long,
        idempotencyKey: String,
        packageSha256: String,
    ): String =
        listOf(
            "$API_VERSION_PREFIX 2",
            "$PRODUCT_ID_PREFIX $productId",
            "$SOURCE_STORY_ID_PREFIX $sourceStoryId",
            "$SOURCE_STORY_VERSION_PREFIX $sourceStoryVersion",
            "$IDEMPOTENCY_KEY_PREFIX $idempotencyKey",
            "$PACKAGE_SHA256_PREFIX $packageSha256",
        ).joinToString("\n")

    fun parse(description: String?): ProductFactoryStoryMetadata? {
        val values = description.orEmpty().lineSequence()
            .map(String::trim)
            .mapNotNull { line ->
                PREFIXES.firstOrNull(line::startsWith)?.let { prefix ->
                    prefix to line.removePrefix(prefix).trim()
                }
            }
            .toMap()
        val productId = values[PRODUCT_ID_PREFIX]?.takeIf(String::isNotBlank)
        val sourceStoryId = values[SOURCE_STORY_ID_PREFIX]?.takeIf(String::isNotBlank)
        val sourceStoryVersion = values[SOURCE_STORY_VERSION_PREFIX]?.toLongOrNull()?.takeIf { it > 0 }
        val idempotencyKey = values[IDEMPOTENCY_KEY_PREFIX]?.takeIf(String::isNotBlank)
        val packageSha256 = values[PACKAGE_SHA256_PREFIX]?.takeIf { it.matches(SHA_256) }
        return ProductFactoryStoryMetadata(
            productId.orEmpty(),
            sourceStoryId.orEmpty(),
            sourceStoryVersion ?: 0,
            idempotencyKey.orEmpty(),
            packageSha256,
        )
            .takeIf { values[API_VERSION_PREFIX] == "2" }
            ?.takeIf { it.productId.isNotBlank() }
            ?.takeIf { it.sourceStoryId.isNotBlank() }
            ?.takeIf { it.sourceStoryVersion > 0 }
            ?.takeIf { it.idempotencyKey.isNotBlank() }
    }

    private val PREFIXES = listOf(
        API_VERSION_PREFIX,
        PRODUCT_ID_PREFIX,
        SOURCE_STORY_ID_PREFIX,
        SOURCE_STORY_VERSION_PREFIX,
        IDEMPOTENCY_KEY_PREFIX,
        PACKAGE_SHA256_PREFIX,
    )

    private val SHA_256 = Regex("[a-f0-9]{64}")
}

data class ProductFactoryStoryMetadata(
    val productId: String,
    val sourceStoryId: String,
    val sourceStoryVersion: Long,
    val idempotencyKey: String,
    val packageSha256: String?,
)

object ProductFactoryAttachmentNames {
    const val PREFIX = "product-factory-input__"

    fun storageName(attachmentId: String, fileName: String): String = "$PREFIX${attachmentId}__$fileName"

    fun parse(storageName: String): ProductFactoryAttachmentName? {
        if (!storageName.startsWith(PREFIX)) return null
        val remainder = storageName.removePrefix(PREFIX)
        val separator = remainder.indexOf("__")
        if (separator <= 0 || separator == remainder.lastIndex - 1) return null
        return ProductFactoryAttachmentName(
            attachmentId = remainder.substring(0, separator),
            fileName = remainder.substring(separator + 2),
        )
    }
}

data class ProductFactoryAttachmentName(
    val attachmentId: String,
    val fileName: String,
)
