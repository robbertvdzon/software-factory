package nl.vdzon.softwarefactory.runtime.v2

import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.util.UUID

class AgentRuntimeV2UploadClient(
    private val restClient: RestClient,
) {
    fun createUpload(request: RuntimeCreateUploadRequest): RuntimeUploadView =
        requireNotNull(
            restClient.post()
                .uri("/v2/uploads")
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, runtimeError("create upload"))
                .body(RuntimeUploadView::class.java),
        ) { "Agent Runtime returned an empty create-upload response" }

    fun uploadHead(uploadId: UUID): RuntimeUploadHead {
        val headers = restClient.head()
            .uri("/v2/uploads/{uploadId}", uploadId)
            .retrieve()
            .onStatus(HttpStatusCode::isError, runtimeError("read upload $uploadId"))
            .toBodilessEntity()
            .headers
        return RuntimeUploadHead(
            offset = requireNotNull(headers.getFirst("Upload-Offset")?.toLongOrNull()) {
                "Agent Runtime omitted Upload-Offset for $uploadId"
            },
            length = requireNotNull(headers.getFirst("Upload-Length")?.toLongOrNull()) {
                "Agent Runtime omitted Upload-Length for $uploadId"
            },
            state = requireNotNull(headers.getFirst("Upload-State")) {
                "Agent Runtime omitted Upload-State for $uploadId"
            }.let(RuntimeUploadState::valueOf),
        )
    }

    fun appendUploadChunk(uploadId: UUID, offset: Long, bytes: ByteArray): Long {
        val headers = restClient.patch()
            .uri("/v2/uploads/{uploadId}", uploadId)
            .header("Upload-Offset", offset.toString())
            .contentType(MediaType.valueOf("application/offset+octet-stream"))
            .body(bytes)
            .retrieve()
            .onStatus(HttpStatusCode::isError, runtimeError("append upload $uploadId"))
            .toBodilessEntity()
            .headers
        return requireNotNull(headers.getFirst("Upload-Offset")?.toLongOrNull()) {
            "Agent Runtime omitted Upload-Offset after append for $uploadId"
        }
    }

    fun completeUpload(uploadId: UUID): RuntimeInputObjectView =
        requireNotNull(
            restClient.post()
                .uri("/v2/uploads/{uploadId}/complete", uploadId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, runtimeError("complete upload $uploadId"))
                .body(RuntimeInputObjectView::class.java),
        ) { "Agent Runtime returned an empty completed-upload response for $uploadId" }

    fun deleteUpload(uploadId: UUID) {
        restClient.delete()
            .uri("/v2/uploads/{uploadId}", uploadId)
            .retrieve()
            .onStatus(HttpStatusCode::isError, runtimeError("delete upload $uploadId"))
            .toBodilessEntity()
    }
}
