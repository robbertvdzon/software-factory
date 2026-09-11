package nl.vdzon.softwarefactory.runtime.v2

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

enum class RuntimeJobKind { APPLICATION_WORK, REPOSITORY_WORK }
enum class RuntimeTaskType { STRUCTURED_GENERATION, REPOSITORY_AGENT }
enum class RuntimeExecutionMode { API, SUBSCRIPTION, MOCK }
enum class RuntimePublicationMode { NONE, COMMIT_AND_PUSH }
enum class RuntimeVerificationMode { NONE, REPOSITORY_CONFIG }
enum class RuntimeJobStatus {
    QUEUED,
    WAITING_FOR_WORKER,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
}
enum class RuntimePublicationStatus { NONE, NO_CHANGES, PUSHED }
enum class RuntimeVerificationStatus { PASSED, FAILED, SKIPPED, CONFIG_MISSING, CONFIG_INVALID, TIMEOUT }
enum class RuntimeVerificationCommandStatus { PASSED, FAILED, TIMEOUT, SKIPPED }
enum class RuntimeUploadState { UPLOADING, READY, EXPIRED }

data class RuntimeExecution(
    val vendorId: String,
    val model: String,
    val mode: RuntimeExecutionMode,
)

data class RuntimeJobInput(
    val instruction: String,
    val objects: List<RuntimeInputObjectRef> = emptyList(),
)

data class RuntimeInputObjectRef(
    val objectId: UUID,
    val name: String,
    val role: String,
)

data class RuntimeCreateUploadRequest(
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class RuntimeUploadView(
    val uploadId: UUID,
    val objectId: UUID,
    val state: RuntimeUploadState,
    val protocol: String,
    val chunkSizeBytes: Long,
    val uploadUrl: String,
    val offset: Long,
    val sizeBytes: Long,
    val expiresAt: OffsetDateTime,
)

data class RuntimeUploadHead(
    val offset: Long,
    val length: Long,
    val state: RuntimeUploadState,
)

data class RuntimeInputObjectView(
    val objectId: UUID,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val state: RuntimeUploadState,
    val createdAt: OffsetDateTime,
    val readyAt: OffsetDateTime,
)

data class RuntimeOutputContract(
    val resultSchema: JsonNode? = null,
    val artifacts: List<RuntimeArtifactDeclaration> = emptyList(),
)

data class RuntimeArtifactDeclaration(
    val name: String,
    val required: Boolean,
    val mimeTypes: List<String>,
    val maxBytes: Long? = null,
)

data class RuntimeRepositoryCheckout(
    val alias: String,
    val branch: String,
    val publicationMode: RuntimePublicationMode,
)

data class RuntimeJobVerification(
    val mode: RuntimeVerificationMode,
    val maxRepairAttempts: Int = 3,
    val repairInstruction: String? = null,
)

data class RuntimeCreateJobRequest(
    val idempotencyKey: String,
    val jobKind: RuntimeJobKind,
    val taskType: RuntimeTaskType,
    val execution: RuntimeExecution,
    val input: RuntimeJobInput,
    val output: RuntimeOutputContract,
    val repositoryCheckout: RuntimeRepositoryCheckout? = null,
    val verification: RuntimeJobVerification? = null,
    val environmentKeys: List<String> = emptyList(),
    val executionTimeoutSeconds: Int,
)

data class RuntimeJobView(
    val id: UUID,
    val tenantId: String,
    val idempotencyKey: String,
    val jobKind: RuntimeJobKind,
    val taskType: RuntimeTaskType,
    val execution: RuntimeExecution,
    val status: RuntimeJobStatus,
    val phase: String,
    val attemptCount: Int,
    val maxAttempts: Int,
    val progressPercent: Int? = null,
    val progressMessage: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val completedAt: OffsetDateTime? = null,
) {
    val terminal: Boolean
        get() = status in setOf(
            RuntimeJobStatus.SUCCEEDED,
            RuntimeJobStatus.FAILED,
            RuntimeJobStatus.CANCELLED,
            RuntimeJobStatus.TIMED_OUT,
        )
}

data class RuntimeJobResultView(
    val jobId: UUID,
    val result: JsonNode,
    val repositoryResult: RuntimeRepositoryResult? = null,
    val verificationResult: RuntimeVerificationResult? = null,
    val artifacts: List<RuntimeOutputObject> = emptyList(),
    val usageSummary: RuntimeUsageSummary,
    val completedAt: OffsetDateTime,
)

data class RuntimeRepositoryResult(
    val alias: String,
    val branch: String,
    val checkoutCommitSha: String,
    val publicationStatus: RuntimePublicationStatus,
    val commitSha: String? = null,
    val diffStat: String? = null,
)

data class RuntimeVerificationResult(
    val status: RuntimeVerificationStatus,
    val configVersion: Int? = null,
    val agentRounds: Int,
    val commands: List<RuntimeVerificationCommandResult> = emptyList(),
)

data class RuntimeVerificationCommandResult(
    val id: String,
    val argv: List<String>,
    val status: RuntimeVerificationCommandStatus,
    val exitCode: Int? = null,
    val durationMillis: Long,
    val outputTail: String? = null,
)

data class RuntimeOutputObject(
    val objectId: UUID,
    val name: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val state: String,
    val createdAt: OffsetDateTime,
    val readyAt: OffsetDateTime,
    val downloadUrl: String,
)

data class RuntimeUsageSummary(
    val attemptCount: Int,
    val usageQuality: String,
    val metrics: List<RuntimeUsageMetric> = emptyList(),
    val costs: List<RuntimeUsageCost> = emptyList(),
)

data class RuntimeUsageMetric(
    val metric: String,
    val quantity: BigDecimal,
    val unit: String,
)

data class RuntimeUsageCost(
    val kind: String,
    val status: String,
    val currency: String,
    val amount: BigDecimal,
)

data class RuntimeJobEventPage(
    val items: List<RuntimeJobEvent> = emptyList(),
    val nextSequence: Long? = null,
    val active: Boolean,
)

data class RuntimeJobEvent(
    val sequence: Long,
    val jobId: UUID,
    val type: String,
    val phase: String? = null,
    val message: String? = null,
    val logKind: String? = null,
    val logText: String? = null,
    val logStreamId: String? = null,
    val logFinal: Boolean = true,
    val status: RuntimeJobStatus? = null,
    val progressPercent: Int? = null,
    val createdAt: OffsetDateTime,
)

data class RuntimeExecutionOption(
    val execution: RuntimeExecution,
    val taskTypes: Set<RuntimeTaskType>,
    val available: Boolean,
    val matchingOnlineWorkers: Int,
    val lastSeenAt: OffsetDateTime? = null,
)

data class RuntimeRepositoryAliasOption(
    val alias: String,
    val available: Boolean,
    val matchingOnlineWorkers: Int,
    val lastSeenAt: OffsetDateTime? = null,
)
