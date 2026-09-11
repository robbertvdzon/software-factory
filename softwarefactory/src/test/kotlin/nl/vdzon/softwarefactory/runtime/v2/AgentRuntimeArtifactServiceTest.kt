package nl.vdzon.softwarefactory.runtime.v2

import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.HexFormat
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentRuntimeArtifactServiceTest {
    @Test
    fun `tester artifact wordt op declaratie grootte hash en screenshotinhoud gevalideerd`() {
        val jobId = UUID.randomUUID()
        val objectId = UUID.randomUUID()
        val png = PNG_SIGNATURE + byteArrayOf(1, 2, 3)
        val zip = screenshotZip("browser/home.png" to png)
        val artifact = artifact(objectId, zip)
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("/v2/jobs/$jobId/objects/$objectId/content"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(zip, MediaType.APPLICATION_OCTET_STREAM))
        val service = AgentRuntimeArtifactService(AgentRuntimeV2HttpClient(builder.build()))

        service.validate(jobId.toString(), "tester", listOf(artifact))

        server.verify()
    }

    @Test
    fun `screenshot zip wordt veilig uitgepakt naar getypeerde afbeeldingen`() {
        val jobId = UUID.randomUUID()
        val objectId = UUID.randomUUID()
        val png = PNG_SIGNATURE + byteArrayOf(4, 5, 6)
        val zip = screenshotZip("home.png" to png)
        val artifact = artifact(objectId, zip)
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("/v2/jobs/$jobId/objects/$objectId/content"))
            .andRespond(withSuccess(zip, MediaType.APPLICATION_OCTET_STREAM))
        val service = AgentRuntimeArtifactService(AgentRuntimeV2HttpClient(builder.build()))

        val screenshots = service.testerScreenshots(jobId.toString(), listOf(artifact))

        assertEquals("home.png", screenshots.single().filename)
        assertEquals("image/png", screenshots.single().mimeType)
        assertTrue(screenshots.single().bytes.contentEquals(png))
        server.verify()
    }

    @Test
    fun `inhoud met een andere hash wordt voor domeinpublicatie geweigerd`() {
        val jobId = UUID.randomUUID()
        val objectId = UUID.randomUUID()
        val zip = screenshotZip("home.png" to (PNG_SIGNATURE + byteArrayOf(7)))
        val artifact = artifact(objectId, zip).copy(sha256 = "0".repeat(64))
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("/v2/jobs/$jobId/objects/$objectId/content"))
            .andRespond(withSuccess(zip, MediaType.APPLICATION_OCTET_STREAM))
        val service = AgentRuntimeArtifactService(AgentRuntimeV2HttpClient(builder.build()))

        assertFailsWith<IllegalArgumentException> {
            service.validate(jobId.toString(), "tester", listOf(artifact))
        }
        server.verify()
    }

    private fun artifact(objectId: UUID, bytes: ByteArray) = RuntimeOutputObject(
        objectId = objectId,
        name = AgentRuntimeArtifactContract.TESTER_SCREENSHOTS,
        filename = "screenshots",
        mimeType = "application/zip",
        sizeBytes = bytes.size.toLong(),
        sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        state = "READY",
        createdAt = OffsetDateTime.parse("2026-09-11T07:00:00Z"),
        readyAt = OffsetDateTime.parse("2026-09-11T07:01:00Z"),
        downloadUrl = "/unused",
    )

    private fun screenshotZip(vararg files: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                files.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
