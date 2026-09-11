package nl.vdzon.softwarefactory.runtime.v2

import nl.vdzon.softwarefactory.core.contracts.AgentInputAttachment
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals

class AgentRuntimeInputUploadServiceTest {
    @Test
    fun `onderbroken upload hervat vanaf de serveroffset en hergebruikt hetzelfde object`() {
        val bytes = "abcdef".toByteArray()
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val uploadId = UUID.randomUUID()
        val objectId = UUID.randomUUID()
        val repository = InMemoryUploadRepository(
            RuntimeInputUploadRecord(
                idempotencyKey = "story-1-developer-1",
                logicalName = "product-factory-source",
                uploadId = uploadId,
                objectId = objectId,
                filename = "source.txt",
                mimeType = "text/plain",
                sizeBytes = 6,
                sha256 = sha,
                chunkSize = 3,
                uploadedOffset = 0,
                state = RuntimeUploadState.UPLOADING,
            ),
        )
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = AgentRuntimeV2UploadClient(builder.build())
        server.expect(requestTo("/v2/uploads/$uploadId"))
            .andExpect(method(HttpMethod.HEAD))
            .andRespond(
                withStatus(HttpStatus.NO_CONTENT)
                    .header("Upload-Offset", "3")
                    .header("Upload-Length", "6")
                    .header("Upload-State", "UPLOADING"),
            )
        server.expect(requestTo("/v2/uploads/$uploadId"))
            .andExpect(method(HttpMethod.PATCH))
            .andExpect { exchange ->
                assertEquals("3", exchange.headers.getFirst("Upload-Offset"))
                assertEquals("def", String((exchange as MockClientHttpRequest).bodyAsBytes))
            }
            .andRespond(withStatus(HttpStatus.NO_CONTENT).header("Upload-Offset", "6"))
        server.expect(requestTo("/v2/uploads/$uploadId/complete"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(
                withSuccess(
                    """{"objectId":"$objectId","filename":"source.txt","mimeType":"text/plain","sizeBytes":6,"sha256":"$sha","state":"READY","createdAt":"2026-09-11T07:00:00Z","readyAt":"2026-09-11T07:01:00Z"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        val service = AgentRuntimeInputUploadService(client, repository)

        val refs = service.upload(
            "story-1-developer-1",
            listOf(AgentInputAttachment("product-factory-source", "bron.txt", "source.txt", "text/plain", bytes)),
        )

        assertEquals(listOf(RuntimeInputObjectRef(objectId, "product-factory-source", "DOCUMENT")), refs)
        assertEquals(RuntimeUploadState.READY, repository.record?.state)
        assertEquals(6, repository.record?.uploadedOffset)
        server.verify()
    }

    private class InMemoryUploadRepository(initial: RuntimeInputUploadRecord?) : RuntimeInputUploadRepository {
        var record = initial

        override fun find(idempotencyKey: String, logicalName: String) =
            record?.takeIf { it.idempotencyKey == idempotencyKey && it.logicalName == logicalName }

        override fun save(record: RuntimeInputUploadRecord) {
            this.record = record
        }

        override fun updateProgress(
            idempotencyKey: String,
            logicalName: String,
            offset: Long,
            state: RuntimeUploadState,
        ) {
            record = requireNotNull(record).copy(uploadedOffset = offset, state = state)
        }

        override fun delete(idempotencyKey: String, logicalName: String) {
            record = null
        }
    }
}
