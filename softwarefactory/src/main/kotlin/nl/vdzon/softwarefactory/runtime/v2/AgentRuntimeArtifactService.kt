package nl.vdzon.softwarefactory.runtime.v2

import nl.vdzon.softwarefactory.core.AgentRole
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.zip.ZipInputStream

data class RuntimeScreenshot(
    val filename: String,
    val mimeType: String,
    val bytes: ByteArray,
)

interface RuntimeArtifactApi {
    fun validate(runtimeJobId: String, role: String, artifacts: List<RuntimeOutputObject>)
    fun testerScreenshots(runtimeJobId: String, artifacts: List<RuntimeOutputObject>): List<RuntimeScreenshot>

    companion object {
        fun none(): RuntimeArtifactApi = object : RuntimeArtifactApi {
            override fun validate(runtimeJobId: String, role: String, artifacts: List<RuntimeOutputObject>) = Unit
            override fun testerScreenshots(
                runtimeJobId: String,
                artifacts: List<RuntimeOutputObject>,
            ): List<RuntimeScreenshot> = emptyList()
        }
    }
}

@Service
class AgentRuntimeArtifactService(
    private val client: AgentRuntimeV2HttpClient,
) : RuntimeArtifactApi {
    override fun validate(runtimeJobId: String, role: String, artifacts: List<RuntimeOutputObject>) {
        val expected = declarations(role).associateBy(RuntimeArtifactDeclaration::name)
        require(artifacts.map(RuntimeOutputObject::name).distinct().size == artifacts.size) {
            "Agent Runtime retourneerde dubbele artifactnamen."
        }
        require(artifacts.all { it.name in expected }) {
            "Agent Runtime retourneerde een niet-gedeclareerd artifact."
        }
        require(expected.values.filter(RuntimeArtifactDeclaration::required).all { declaration ->
            artifacts.any { it.name == declaration.name }
        }) { "Agent Runtime retourneerde niet alle verplichte artifacts." }

        val jobId = if (artifacts.isEmpty()) null else runtimeJobId.toRuntimeJobId()
        artifacts.forEach { artifact ->
            val bytes = validatedContent(requireNotNull(jobId), artifact, expected.getValue(artifact.name))
            if (artifact.name == AgentRuntimeArtifactContract.TESTER_SCREENSHOTS) extractScreenshots(bytes)
        }
    }

    override fun testerScreenshots(
        runtimeJobId: String,
        artifacts: List<RuntimeOutputObject>,
    ): List<RuntimeScreenshot> {
        val artifact = artifacts.singleOrNull {
            it.name == AgentRuntimeArtifactContract.TESTER_SCREENSHOTS
        } ?: return emptyList()
        val declaration = AgentRuntimeArtifactContract.declarations(AgentRole.TESTER).single()
        return extractScreenshots(validatedContent(runtimeJobId.toRuntimeJobId(), artifact, declaration))
    }

    private fun declarations(role: String): List<RuntimeArtifactDeclaration> =
        AgentRole.entries.firstOrNull { it.markerKeyPart == role }
            ?.let(AgentRuntimeArtifactContract::declarations)
            .orEmpty()

    private fun validatedContent(
        jobId: UUID,
        artifact: RuntimeOutputObject,
        declaration: RuntimeArtifactDeclaration,
    ): ByteArray {
        require(artifact.state == "READY") { "Runtime-artifact ${artifact.name} is niet READY." }
        require(artifact.mimeType in declaration.mimeTypes) {
            "Runtime-artifact ${artifact.name} heeft onverwacht MIME-type ${artifact.mimeType}."
        }
        require(artifact.sizeBytes > 0 && declaration.maxBytes?.let { artifact.sizeBytes <= it } != false) {
            "Runtime-artifact ${artifact.name} is groter dan toegestaan."
        }
        require(artifact.sha256.matches(SHA_256)) { "Runtime-artifact ${artifact.name} heeft een ongeldige SHA-256." }
        val bytes = client.downloadJobObject(jobId, artifact.objectId)
        require(bytes.size.toLong() == artifact.sizeBytes) {
            "Runtime-artifact ${artifact.name} heeft niet de aangekondigde grootte."
        }
        val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        require(MessageDigest.isEqual(actual.toByteArray(), artifact.sha256.toByteArray())) {
            "Runtime-artifact ${artifact.name} heeft niet de aangekondigde SHA-256."
        }
        return bytes
    }

    private fun extractScreenshots(zipBytes: ByteArray): List<RuntimeScreenshot> {
        val screenshots = mutableListOf<RuntimeScreenshot>()
        var totalBytes = 0L
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    require(screenshots.size < MAX_SCREENSHOTS) { "Runtime screenshot-ZIP bevat te veel bestanden." }
                    require(isSafeZipEntry(entry.name)) { "Runtime screenshot-ZIP bevat een onveilig pad." }
                    val filename = entry.name.substringAfterLast('/')
                    val mimeType = screenshotMimeType(filename)
                    val bytes = readEntry(zip)
                    totalBytes += bytes.size
                    require(totalBytes <= MAX_UNCOMPRESSED_BYTES) {
                        "Runtime screenshot-ZIP is uitgepakt groter dan toegestaan."
                    }
                    require(matchesImageSignature(bytes, mimeType)) {
                        "Runtime screenshot $filename komt niet overeen met MIME-type $mimeType."
                    }
                    screenshots += RuntimeScreenshot(filename, mimeType, bytes)
                }
                zip.closeEntry()
            }
        }
        require(screenshots.isNotEmpty()) { "Runtime screenshot-ZIP bevat geen screenshots." }
        return screenshots
    }

    private fun readEntry(zip: ZipInputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= MAX_SCREENSHOT_BYTES) { "Runtime screenshot is groter dan toegestaan." }
        }
        return output.toByteArray()
    }

    private fun isSafeZipEntry(name: String): Boolean =
        name.isNotBlank() &&
            !name.startsWith('/') &&
            '\\' !in name &&
            '\u0000' !in name &&
            name.split('/').none { it == ".." }

    private fun screenshotMimeType(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> error("Runtime screenshot-ZIP bevat een niet-ondersteund bestand: $filename")
    }

    private fun matchesImageSignature(bytes: ByteArray, mimeType: String): Boolean = when (mimeType) {
        "image/png" -> bytes.size >= 8 && bytes.take(8).toByteArray().contentEquals(PNG_SIGNATURE)
        "image/jpeg" -> bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        "image/webp" -> bytes.size >= 12 &&
            String(bytes, 0, 4, StandardCharsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, StandardCharsets.US_ASCII) == "WEBP"
        else -> false
    }

    private fun String.toRuntimeJobId(): UUID =
        runCatching { UUID.fromString(this) }.getOrElse { throw IllegalArgumentException("Ongeldig Runtime-job-ID: $this") }

    private companion object {
        const val MAX_SCREENSHOTS = 50
        const val MAX_SCREENSHOT_BYTES = 15 * 1024 * 1024
        const val MAX_UNCOMPRESSED_BYTES = 100L * 1024 * 1024
        val SHA_256 = Regex("[a-f0-9]{64}")
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
