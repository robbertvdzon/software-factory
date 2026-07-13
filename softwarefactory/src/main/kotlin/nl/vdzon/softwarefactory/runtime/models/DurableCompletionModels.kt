package nl.vdzon.softwarefactory.runtime.models

import nl.vdzon.softwarefactory.runtime.types.CompletionStatus
import nl.vdzon.softwarefactory.runtime.types.CompletionStep
import java.time.OffsetDateTime
import java.time.Duration

data class DurableCompletion(
    val id: Long,
    val agentRunId: Long,
    val storyRunId: Long,
    val storyKey: String,
    val containerName: String,
    val workspacePath: String?,
    val payloadHash: String,
    val payloadJson: String?,
    val payloadValidated: Boolean,
    val status: CompletionStatus,
)

data class CompletionStepState(
    val step: CompletionStep,
    val status: CompletionStatus,
    val attempts: Int,
    val nextAttemptAt: OffsetDateTime,
    val leaseUntil: OffsetDateTime?,
    val lastError: String?,
)

data class AcceptedCompletion(
    val completion: DurableCompletion,
    val request: AgentRunCompleteRequest,
)

data class CompletionExecutionPolicy(
    val maxAttempts: Int = 8,
    val lease: Duration = Duration.ofMinutes(5),
    val backoff: Duration = Duration.ofSeconds(2),
)
