package nl.vdzon.softwarefactory.runtime.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import nl.vdzon.softwarefactory.runtime.models.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.models.*
import nl.vdzon.softwarefactory.runtime.errors.CompletionPermanentlyFailedException
import nl.vdzon.softwarefactory.runtime.repositories.CompletionInboxRepository
import nl.vdzon.softwarefactory.runtime.types.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

@Component
class DurableCompletionCoordinator(
    private val repository: CompletionInboxRepository,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val owner = "${runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("factory")}-${UUID.randomUUID()}"

    fun accept(request: AgentRunCompleteRequest): AcceptedCompletion? = repository.accept(request)
    fun storeValidatedPayload(id: Long, request: AgentRunCompleteRequest): AcceptedCompletion =
        repository.storeValidatedPayload(id, request)

    fun load(id: Long): AcceptedCompletion? {
        val completion = repository.get(id)
        return completion?.payloadJson?.let { payload ->
            AcceptedCompletion(completion, objectMapper.readValue(payload))
        }
    }

    fun runStep(
        completionId: Long,
        step: CompletionStep,
        policy: CompletionExecutionPolicy = CompletionExecutionPolicy(),
        effect: () -> Unit,
    ): Boolean {
        val state = repository.steps(completionId).first { it.step == step }
        return when (state.status) {
            CompletionStatus.COMPLETED -> true
            CompletionStatus.FAILED_PERMANENT -> throw CompletionPermanentlyFailedException(
                "Completion $completionId failed permanently at ${step.name}: ${state.lastError}",
            )
            else -> repository.claimStep(completionId, step, owner, policy.lease)?.let { claimed ->
                executeClaimed(completionId, step, policy, claimed, effect)
            } ?: false
        }
    }

    fun runSteps(
        completionId: Long,
        policy: CompletionExecutionPolicy,
        effects: Map<CompletionStep, () -> Unit>,
    ): Boolean = effects.all { (step, effect) -> runStep(completionId, step, policy, effect) }

    private fun executeClaimed(
        completionId: Long,
        step: CompletionStep,
        policy: CompletionExecutionPolicy,
        claimed: CompletionStepState,
        effect: () -> Unit,
    ): Boolean {
        return try {
            effect()
            repository.completeStep(completionId, step)
            true
        } catch (error: Throwable) {
            repository.failStep(completionId, step, error, policy.maxAttempts, policy.backoff)
            logger.warn(
                "Durable completion {} failed at {} (attempt {}): {}",
                completionId,
                step,
                claimed.attempts,
                error.message ?: error.javaClass.simpleName,
            )
            false
        }
    }

    fun dueCompletionIds(limit: Int = 25): List<Long> = repository.dueCompletionIds(limit)
    fun manualRequeue(id: Long, step: CompletionStep, requestedBy: String, reason: String) =
        repository.manualRequeue(id, step, requestedBy, reason)
    fun purgePayloads(retention: Duration = Duration.ofDays(30)): Int = repository.purgePayloads(retention)
}
