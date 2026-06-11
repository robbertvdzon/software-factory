package nl.vdzon.softwarefactory.e2e

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.orchestrator.AgentDispatchRequest
import nl.vdzon.softwarefactory.orchestrator.AgentRunCompletionRecord
import nl.vdzon.softwarefactory.orchestrator.AgentRunRecord
import nl.vdzon.softwarefactory.orchestrator.AgentRunRepository
import nl.vdzon.softwarefactory.orchestrator.CompletedAgentRun
import nl.vdzon.softwarefactory.orchestrator.StoryRunRecord
import nl.vdzon.softwarefactory.orchestrator.StoryRunRepository
import nl.vdzon.softwarefactory.runtime.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.AgentRunCompleteResponse
import nl.vdzon.softwarefactory.runtime.RuntimeApi
import nl.vdzon.softwarefactory.runtime.repositories.AgentEventRepository
import nl.vdzon.softwarefactory.runtime.services.AgentResultFileCompletionPoller
import nl.vdzon.softwarefactory.youtrack.AgentRole
import org.springframework.http.ResponseEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * Borgt bouwstap 2 uit het e2e-plan: een door [TestAgentRuntime] geschreven
 * `agent-result.json` levert via de **echte** [AgentResultFileCompletionPoller]
 * een [AgentRunCompleteRequest] met de juiste phase op. Geen Spring-context
 * nodig — alleen de poller zelf met lichte fakes rond de naden.
 */
class TestAgentRuntimePollerTest {

    @Test
    fun `developer question then developed flows through the real poller`() {
        val runtime = TestAgentRuntime()
        val runtimeApi = CapturingRuntimeApi()
        val agentRuns = mutableListOf<AgentRunRecord>()
        val storyRun = StoryRunRecord(id = 7, storyKey = "KAN-1", targetRepo = "repo")

        val poller = AgentResultFileCompletionPoller(
            agentRunRepository = FakeAgentRunRepository(agentRuns),
            storyRunRepository = FakeStoryRunRepository(storyRun),
            agentRuntime = runtime,
            runtimeApi = runtimeApi,
            agentEventRepository = NoopAgentEventRepository,
            objectMapper = jacksonObjectMapper(),
        )

        // Eerste developer-dispatch (attempt 1) → vraag.
        agentRuns.clear()
        agentRuns += runtime.dispatch(developerRequest()).toRunRecord(storyRun.id)
        poller.poll()

        // Tweede developer-dispatch (attempt 2) → developed.
        agentRuns.clear()
        agentRuns += runtime.dispatch(developerRequest()).toRunRecord(storyRun.id)
        poller.poll()

        assertEquals(2, runtimeApi.completed.size)
        assertEquals("developed-with-questions", runtimeApi.completed[0].phase)
        assertEquals("questions", runtimeApi.completed[0].outcome)
        assertEquals("developed", runtimeApi.completed[1].phase)
        assertEquals("ok", runtimeApi.completed[1].outcome)
        // De poller dwingt de eigen container-naam af op het verwerkte resultaat.
        assertEquals("KAN-1", runtimeApi.completed[1].storyKey)
        assertTrue(runtimeApi.completed[1].containerName.isNotBlank())
    }

    @Test
    fun `planner result carries the four subtasks`() {
        val runtime = TestAgentRuntime()
        val result = AgentScript().resultFor(
            AgentDispatchRequest(
                storyKey = "KAN-1",
                targetRepo = "repo",
                storyRunId = 7,
                role = AgentRole.PLANNER,
                phase = "planning",
            ),
            attempt = 1,
        )
        assertEquals("planned", result.phase)
        assertEquals(listOf("development", "review", "test", "summary"), result.subtasks.map { it.type })
    }

    private fun developerRequest() = AgentDispatchRequest(
        storyKey = "KAN-1",
        targetRepo = "repo",
        storyRunId = 7,
        role = AgentRole.DEVELOPER,
        phase = "developing",
        serializationKey = "KAN-1",
    )

    private fun nl.vdzon.softwarefactory.orchestrator.AgentDispatchResult.toRunRecord(storyRunId: Long) =
        AgentRunRecord(
            id = 1,
            storyRunId = storyRunId,
            role = AgentRole.DEVELOPER,
            containerName = containerName,
            startedAt = OffsetDateTime.now(),
            endedAt = null,
            outcome = null,
            summaryText = null,
            workspacePath = workspacePath,
        )
}

private class CapturingRuntimeApi : RuntimeApi {
    val completed = mutableListOf<AgentRunCompleteRequest>()

    override fun complete(request: AgentRunCompleteRequest): ResponseEntity<AgentRunCompleteResponse> {
        completed += request
        return ResponseEntity.ok(AgentRunCompleteResponse(agentRunId = 1, storyRunId = 7))
    }
}

private class FakeAgentRunRepository(
    private val active: List<AgentRunRecord>,
) : AgentRunRepository {
    override fun recordStarted(
        storyRunId: Long,
        role: AgentRole,
        containerName: String,
        model: String?,
        effort: String?,
        level: Int?,
        workspacePath: String?,
        subtaskKey: String?,
    ): Long = 1

    override fun complete(
        containerName: String,
        completion: AgentRunCompletionRecord,
        endedAt: OffsetDateTime,
    ): CompletedAgentRun? = null

    override fun addUsageToStoryRun(storyRunId: Long, completion: AgentRunCompletionRecord) = Unit

    override fun activeRuns(): List<AgentRunRecord> = active

    override fun latestForRole(storyRunId: Long, role: AgentRole): AgentRunRecord? = null

    override fun recentForRole(storyRunId: Long, role: AgentRole, limit: Int): List<AgentRunRecord> = emptyList()

    override fun countForRole(storyRunId: Long, role: AgentRole): Int = 0

    override fun countForRoleAndSubtask(storyRunId: Long, role: AgentRole, subtaskKey: String): Int = 0
}

private class FakeStoryRunRepository(
    private val storyRun: StoryRunRecord,
) : StoryRunRepository {
    override fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord = storyRun

    override fun get(storyRunId: Long): StoryRunRecord? = storyRun.takeIf { it.id == storyRunId }

    override fun updatePullRequest(
        storyRunId: Long,
        branchName: String,
        prNumber: Int?,
        prUrl: String?,
        baseBranch: String?,
        branchPrefix: String?,
        previewUrlTemplate: String?,
        previewNamespaceTemplate: String?,
        previewDbSecretRecipe: String?,
    ) = Unit

    override fun activePullRequests(): List<StoryRunRecord> = emptyList()

    override fun activeRuns(): List<StoryRunRecord> = listOf(storyRun)

    override fun close(storyRunId: Long, finalStatus: String, endedAt: OffsetDateTime) = Unit
}

private object NoopAgentEventRepository : AgentEventRepository {
    override fun append(agentRunId: Long, kind: String, payload: Map<String, Any?>) = Unit
}
