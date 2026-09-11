package nl.vdzon.softwarefactory.runtime.v2

import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.softwarefactory.core.contracts.AgentRunRecord
import nl.vdzon.softwarefactory.core.contracts.AgentRunRepository
import nl.vdzon.softwarefactory.core.contracts.StoryRunRepository
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.runtime.RuntimeApi
import nl.vdzon.softwarefactory.runtime.repositories.AgentEventRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

@Component
@ConditionalOnProperty(name = ["softwarefactory.runtime"], havingValue = "v2", matchIfMissing = true)
class AgentRuntimeV2CompletionPoller(
    private val agentRuns: AgentRunRepository,
    private val storyRuns: StoryRunRepository,
    private val client: AgentRuntimeV2HttpClient,
    private val runtimeApi: RuntimeApi,
    private val events: AgentEventRepository,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = AgentRuntimeV2ResultMapper()

    @Scheduled(fixedDelayString = "\${SF_AGENT_RUNTIME_POLL_MS:2000}")
    fun poll() {
        agentRuns.activeRuns().forEach { run ->
            val jobId = runCatching { UUID.fromString(run.containerName) }.getOrNull() ?: return@forEach
            runCatching { reconcile(run, jobId) }
                .onFailure { logger.warn("Could not reconcile Agent Runtime job {}", jobId, it) }
        }
    }

    private fun reconcile(run: AgentRunRecord, jobId: UUID) {
        mirrorEvents(run.id, jobId)
        val job = client.getJob(jobId)
        agentRuns.updateRuntimeJob(
            runtimeJobId = jobId.toString(),
            status = job.status.name,
            phase = job.phase,
            errorCode = job.errorCode,
            errorMessage = job.errorMessage,
        )
        // Audits hebben geen trackerissue. AuditGatewayAdapter leest hetzelfde getypeerde
        // Runtime-resultaat en publiceert rapport/vraag/vervolgstory via de audit-pipeline.
        if (run.role == AgentRole.AUDITOR) return
        if (!job.terminal) return
        val story = storyRuns.get(run.storyRunId) ?: return
        val result = terminalResult(job)
        val completion = if (result == null) {
            mapper.failed(story.storyKey, run.role, job)
        } else {
            storeResult(jobId, result)
            mapper.completed(story.storyKey, run.role, jobId.toString(), job, result)
        }
        runtimeApi.complete(completion)
    }

    private fun terminalResult(job: RuntimeJobView): RuntimeJobResultView? = when (job.status) {
        RuntimeJobStatus.SUCCEEDED -> client.getResult(job.id)
        RuntimeJobStatus.FAILED -> try {
            // Een blijvend rode repositoryverificatie eindigt FAILED mét een getypeerd resultaat.
            // Een infrastructuur-/agentfout heeft juist geen resultaat en geeft RESULT_NOT_READY.
            client.getResult(job.id)
        } catch (exception: AgentRuntimeV2Exception) {
            if (exception.hasErrorCode("RESULT_NOT_READY")) null else throw exception
        }
        RuntimeJobStatus.CANCELLED, RuntimeJobStatus.TIMED_OUT -> null
        else -> error("Niet-terminale Runtime-status ${job.status} in terminalResult")
    }

    private fun storeResult(jobId: UUID, result: RuntimeJobResultView) {
        agentRuns.storeRuntimeJobResult(
            runtimeJobId = jobId.toString(),
            checkoutCommitSha = result.repositoryResult?.checkoutCommitSha,
            publishedCommitSha = result.repositoryResult?.commitSha,
            repositoryResultJson = result.repositoryResult?.let(objectMapper::writeValueAsString),
            verificationResultJson = result.verificationResult?.let(objectMapper::writeValueAsString),
        )
    }

    private fun mirrorEvents(agentRunId: Long, jobId: UUID) {
        client.events(jobId).items.forEach { event ->
            val text = event.logText ?: event.message ?: event.phase ?: event.status?.name.orEmpty()
            events.appendOnce(
                agentRunId = agentRunId,
                effectKey = "runtime-event-${event.sequence}",
                kind = event.logKind?.lowercase()?.replace('_', '-') ?: event.type.lowercase(),
                payload = mapOf(
                    "runtimeSequence" to event.sequence,
                    "line" to text,
                    "runtimeType" to event.type,
                    "runtimeStatus" to event.status?.name,
                ),
            )
        }
    }
}
