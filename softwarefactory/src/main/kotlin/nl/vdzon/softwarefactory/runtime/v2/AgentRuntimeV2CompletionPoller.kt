package nl.vdzon.softwarefactory.runtime.v2

import nl.vdzon.softwarefactory.core.contracts.AgentRunRecord
import nl.vdzon.softwarefactory.core.contracts.AgentRunRepository
import nl.vdzon.softwarefactory.core.contracts.StoryRunRepository
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
        if (!job.terminal) return
        val story = storyRuns.get(run.storyRunId) ?: return
        val completion = runCatching { client.getResult(jobId) }
            .map { result -> mapper.completed(story.storyKey, run.role, jobId.toString(), job, result) }
            .getOrElse { mapper.failed(story.storyKey, run.role, job) }
        runtimeApi.complete(completion)
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
