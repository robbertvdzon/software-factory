package nl.vdzon.softwarefactory.runtime.v2

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.core.contracts.AgentInputAttachment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.HexFormat
import java.util.UUID

data class RuntimeInputUploadRecord(
    val idempotencyKey: String,
    val logicalName: String,
    val uploadId: UUID,
    val objectId: UUID,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val chunkSize: Long,
    val uploadedOffset: Long,
    val state: RuntimeUploadState,
)

interface RuntimeInputUploadRepository {
    fun find(idempotencyKey: String, logicalName: String): RuntimeInputUploadRecord?
    fun save(record: RuntimeInputUploadRecord)
    fun updateProgress(idempotencyKey: String, logicalName: String, offset: Long, state: RuntimeUploadState)
    fun delete(idempotencyKey: String, logicalName: String)
}

@Repository
class JdbcRuntimeInputUploadRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val factorySecrets: FactorySecrets,
) : RuntimeInputUploadRepository {
    override fun find(idempotencyKey: String, logicalName: String): RuntimeInputUploadRecord? =
        jdbcTemplate.query(
            """
            SELECT idempotency_key, logical_name, upload_id, object_id, filename, mime_type,
                   size_bytes, sha256, chunk_size, uploaded_offset, state
            FROM ${factorySecrets.factoryDatabaseSchema}.agent_runtime_input_uploads
            WHERE idempotency_key = ? AND logical_name = ?
            """.trimIndent(),
            { rs, _ ->
                RuntimeInputUploadRecord(
                    idempotencyKey = rs.getString("idempotency_key"),
                    logicalName = rs.getString("logical_name"),
                    uploadId = rs.getObject("upload_id", UUID::class.java),
                    objectId = rs.getObject("object_id", UUID::class.java),
                    filename = rs.getString("filename"),
                    mimeType = rs.getString("mime_type"),
                    sizeBytes = rs.getLong("size_bytes"),
                    sha256 = rs.getString("sha256"),
                    chunkSize = rs.getLong("chunk_size"),
                    uploadedOffset = rs.getLong("uploaded_offset"),
                    state = RuntimeUploadState.valueOf(rs.getString("state")),
                )
            },
            idempotencyKey,
            logicalName,
        ).firstOrNull()

    override fun save(record: RuntimeInputUploadRecord) {
        jdbcTemplate.update(
            """
            INSERT INTO ${factorySecrets.factoryDatabaseSchema}.agent_runtime_input_uploads
              (idempotency_key, logical_name, upload_id, object_id, filename, mime_type,
               size_bytes, sha256, chunk_size, uploaded_offset, state, updated_at)
            VALUES (?, ?, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (idempotency_key, logical_name) DO UPDATE SET
              upload_id = EXCLUDED.upload_id,
              object_id = EXCLUDED.object_id,
              filename = EXCLUDED.filename,
              mime_type = EXCLUDED.mime_type,
              size_bytes = EXCLUDED.size_bytes,
              sha256 = EXCLUDED.sha256,
              chunk_size = EXCLUDED.chunk_size,
              uploaded_offset = EXCLUDED.uploaded_offset,
              state = EXCLUDED.state,
              updated_at = now()
            """.trimIndent(),
            record.idempotencyKey,
            record.logicalName,
            record.uploadId,
            record.objectId,
            record.filename,
            record.mimeType,
            record.sizeBytes,
            record.sha256,
            record.chunkSize,
            record.uploadedOffset,
            record.state.name,
        )
    }

    override fun updateProgress(
        idempotencyKey: String,
        logicalName: String,
        offset: Long,
        state: RuntimeUploadState,
    ) {
        jdbcTemplate.update(
            """
            UPDATE ${factorySecrets.factoryDatabaseSchema}.agent_runtime_input_uploads
            SET uploaded_offset = ?, state = ?, updated_at = now()
            WHERE idempotency_key = ? AND logical_name = ?
            """.trimIndent(),
            offset,
            state.name,
            idempotencyKey,
            logicalName,
        )
    }

    override fun delete(idempotencyKey: String, logicalName: String) {
        jdbcTemplate.update(
            """
            DELETE FROM ${factorySecrets.factoryDatabaseSchema}.agent_runtime_input_uploads
            WHERE idempotency_key = ? AND logical_name = ?
            """.trimIndent(),
            idempotencyKey,
            logicalName,
        )
    }
}

@Service
class AgentRuntimeInputUploadService(
    private val client: AgentRuntimeV2UploadClient,
    private val repository: RuntimeInputUploadRepository,
) {
    fun upload(idempotencyKey: String, attachments: List<AgentInputAttachment>): List<RuntimeInputObjectRef> =
        attachments.map { attachment -> uploadOne(idempotencyKey, attachment) }

    private fun uploadOne(idempotencyKey: String, attachment: AgentInputAttachment): RuntimeInputObjectRef {
        require(attachment.bytes.isNotEmpty()) { "Runtime-inputobject ${attachment.logicalName} is leeg." }
        val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(attachment.bytes))
        var record = repository.find(idempotencyKey, attachment.logicalName)
        if (record != null) {
            require(
                record.sha256 == sha256 &&
                    record.sizeBytes == attachment.bytes.size.toLong() &&
                    record.filename == attachment.uploadFilename &&
                    record.mimeType == attachment.mimeType,
            ) {
                "Runtime-inputobject ${attachment.logicalName} wijzigde binnen dezelfde idempotente job."
            }
        }
        if (record == null) {
            record = reserve(idempotencyKey, attachment, sha256)
        }

        val resumed = readHeadOrReplaceExpired(idempotencyKey, attachment, sha256, record)
        record = resumed.first
        var head = resumed.second
        require(head.length == attachment.bytes.size.toLong()) {
            "Runtime-upload ${record.uploadId} verwacht ${head.length} bytes, lokaal zijn er ${attachment.bytes.size}."
        }
        var offset = head.offset
        while (head.state == RuntimeUploadState.UPLOADING && offset < attachment.bytes.size) {
            val end = (offset + record.chunkSize).coerceAtMost(attachment.bytes.size.toLong()).toInt()
            val chunk = attachment.bytes.copyOfRange(offset.toInt(), end)
            offset = client.appendUploadChunk(record.uploadId, offset, chunk)
            require(offset in 0..attachment.bytes.size.toLong()) {
                "Runtime-upload ${record.uploadId} gaf een ongeldige offset $offset terug."
            }
            repository.updateProgress(idempotencyKey, attachment.logicalName, offset, RuntimeUploadState.UPLOADING)
            head = if (offset < attachment.bytes.size) client.uploadHead(record.uploadId) else head.copy(offset = offset)
        }

        val completed = if (head.state == RuntimeUploadState.READY) {
            null
        } else {
            require(offset == attachment.bytes.size.toLong()) {
                "Runtime-upload ${record.uploadId} is onvolledig: $offset/${attachment.bytes.size}."
            }
            client.completeUpload(record.uploadId)
        }
        completed?.let { value ->
            require(value.objectId == record.objectId && value.sha256 == sha256 && value.sizeBytes == record.sizeBytes) {
                "Agent Runtime finaliseerde onverwachte metadata voor ${attachment.logicalName}."
            }
        }
        repository.updateProgress(idempotencyKey, attachment.logicalName, record.sizeBytes, RuntimeUploadState.READY)
        return RuntimeInputObjectRef(record.objectId, attachment.logicalName, attachment.role)
    }

    private fun readHeadOrReplaceExpired(
        idempotencyKey: String,
        attachment: AgentInputAttachment,
        sha256: String,
        existing: RuntimeInputUploadRecord,
    ): Pair<RuntimeInputUploadRecord, RuntimeUploadHead> {
        val currentHead = try {
            client.uploadHead(existing.uploadId)
        } catch (exception: AgentRuntimeV2Exception) {
            if (exception.statusCode != 404 && !exception.hasErrorCode("UPLOAD_EXPIRED")) throw exception
            null
        }
        return if (currentHead != null && currentHead.state != RuntimeUploadState.EXPIRED) {
            existing to currentHead
        } else {
            runCatching { client.deleteUpload(existing.uploadId) }
            repository.delete(idempotencyKey, attachment.logicalName)
            val replacement = reserve(idempotencyKey, attachment, sha256)
            replacement to client.uploadHead(replacement.uploadId)
        }
    }

    private fun reserve(
        idempotencyKey: String,
        attachment: AgentInputAttachment,
        sha256: String,
    ): RuntimeInputUploadRecord {
        val upload = client.createUpload(
            RuntimeCreateUploadRequest(
                filename = attachment.uploadFilename,
                mimeType = attachment.mimeType,
                sizeBytes = attachment.bytes.size.toLong(),
                sha256 = sha256,
            ),
        )
        require(upload.protocol == "RESUMABLE_PATCH") { "Onbekend Runtime-uploadprotocol: ${upload.protocol}" }
        return RuntimeInputUploadRecord(
            idempotencyKey = idempotencyKey,
            logicalName = attachment.logicalName,
            uploadId = upload.uploadId,
            objectId = upload.objectId,
            filename = attachment.uploadFilename,
            mimeType = attachment.mimeType,
            sizeBytes = attachment.bytes.size.toLong(),
            sha256 = sha256,
            chunkSize = upload.chunkSizeBytes,
            uploadedOffset = upload.offset,
            state = upload.state,
        ).also(repository::save)
    }
}
