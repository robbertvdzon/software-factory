package nl.vdzon.softwarefactory.runtime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.orchestrator.AgentDispatchRequest
import nl.vdzon.softwarefactory.orchestrator.AgentDispatchResult
import nl.vdzon.softwarefactory.orchestrator.AgentRunCompletionRecord
import nl.vdzon.softwarefactory.orchestrator.AgentRunRecord
import nl.vdzon.softwarefactory.orchestrator.AgentRunRepository
import nl.vdzon.softwarefactory.orchestrator.AgentRuntime
import nl.vdzon.softwarefactory.orchestrator.CompletedAgentRun
import nl.vdzon.softwarefactory.orchestrator.StoryRunRecord
import nl.vdzon.softwarefactory.orchestrator.StoryRunRepository
import nl.vdzon.softwarefactory.runtime.services.AgentResultFileCompletionPoller
import nl.vdzon.softwarefactory.runtime.repositories.AgentEventRecord
import nl.vdzon.softwarefactory.runtime.repositories.AgentEventRepository
import nl.vdzon.softwarefactory.youtrack.AgentRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.ResponseEntity
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.io.path.writeText

class AgentResultFileCompletionPollerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `poller reads result file after container stopped`() {
        tempDir.resolve("agent-result.json").writeText(
            """
            {
              "storyKey": "KAN-69",
              "role": "developer",
              "containerName": "factory-kan-69-developer",
              "phase": "developed",
              "outcome": "developed",
              "summaryText": "done",
              "exitCode": 0,
              "inputTokens": 10,
              "events": [{"kind": "github-pr", "payload": "{\"prNumber\":42}"}],
              "knowledgeUpdates": [{"category": "build", "key": "mvn", "content": "Use mvn test."}]
            }
            """.trimIndent(),
        )
        val runtimeApi = FakeRuntimeApi()
        val poller = poller(runtimeApi = runtimeApi, runningContainers = emptySet())

        poller.poll()

        val request = runtimeApi.completed.single()
        assertEquals("KAN-69", request.storyKey)
        assertEquals("developer", request.role)
        assertEquals("factory-kan-69-developer", request.containerName)
        assertEquals("developed", request.phase)
        assertEquals("done", request.summaryText)
        assertEquals(10, request.inputTokens)
        assertEquals("github-pr", request.events.single().kind)
        assertEquals("mvn", request.knowledgeUpdates.single().key)
    }

    @Test
    fun `poller waits while container is still running`() {
        val runtimeApi = FakeRuntimeApi()
        val poller = poller(runtimeApi = runtimeApi, runningContainers = setOf("factory-kan-69-developer"))

        poller.poll()

        assertEquals(emptyList<AgentRunCompleteRequest>(), runtimeApi.completed)
    }

    @Test
    fun `missing result includes recent docker logs`() {
        val runtimeApi = FakeRuntimeApi()
        val poller = poller(
            runtimeApi = runtimeApi,
            runningContainers = emptySet(),
            events = listOf(
                AgentEventRecord(1, "docker-stderr", """{"line":"Error: missing class"}"""),
                AgentEventRecord(2, "docker-stdout", """{"line":"starting agent"}"""),
            ),
        )

        poller.poll()

        val summary = runtimeApi.completed.single().summaryText.orEmpty()
        assertEquals(true, summary.contains("Agent container stopped without writing /work/agent-result.json."))
        assertEquals(true, summary.contains("docker-stderr: Error: missing class"))
        assertEquals(true, summary.contains("docker-stdout: starting agent"))
    }

    private fun poller(
        runtimeApi: FakeRuntimeApi,
        runningContainers: Set<String>,
        events: List<AgentEventRecord> = emptyList(),
    ): AgentResultFileCompletionPoller =
        AgentResultFileCompletionPoller(
            agentRunRepository = FakeAgentRunRepository(tempDir.toString()),
            storyRunRepository = FakeStoryRunRepository(),
            agentRuntime = FakeAgentRuntime(runningContainers),
            runtimeApi = runtimeApi,
            agentEventRepository = FakeAgentEventRepository(events),
            objectMapper = jacksonObjectMapper(),
        )

    private class FakeRuntimeApi : RuntimeApi {
        val completed = mutableListOf<AgentRunCompleteRequest>()

        override fun complete(request: AgentRunCompleteRequest): ResponseEntity<AgentRunCompleteResponse> {
            completed += request
            return ResponseEntity.ok(AgentRunCompleteResponse(1, 7))
        }
    }

    private class FakeAgentEventRepository(
        private val events: List<AgentEventRecord>,
    ) : AgentEventRepository {
        override fun append(agentRunId: Long, kind: String, payload: Map<String, Any?>) = Unit

        override fun recentForAgentRun(agentRunId: Long, kinds: Set<String>, limit: Int): List<AgentEventRecord> =
            events.filter { kinds.isEmpty() || it.kind in kinds }.take(limit)
    }

    private class FakeAgentRunRepository(
        private val workspacePath: String,
    ) : AgentRunRepository {
        override fun recordStarted(
            storyRunId: Long,
            role: AgentRole,
            containerName: String,
            model: String?,
            effort: String?,
            level: Int?,
            workspacePath: String?,
        ): Long = 1

        override fun complete(containerName: String, completion: AgentRunCompletionRecord, endedAt: OffsetDateTime): CompletedAgentRun? = null

        override fun addUsageToStoryRun(storyRunId: Long, completion: AgentRunCompletionRecord) = Unit

        override fun activeRuns(): List<AgentRunRecord> =
            listOf(
                AgentRunRecord(
                    id = 1,
                    storyRunId = 7,
                    role = AgentRole.DEVELOPER,
                    containerName = "factory-kan-69-developer",
                    startedAt = OffsetDateTime.parse("2026-05-24T12:00:00Z"),
                    endedAt = null,
                    outcome = null,
                    summaryText = null,
                    workspacePath = workspacePath,
                ),
            )

        override fun latestForRole(storyRunId: Long, role: AgentRole): AgentRunRecord? = null

        override fun recentForRole(storyRunId: Long, role: AgentRole, limit: Int): List<AgentRunRecord> = emptyList()

        override fun countForRole(storyRunId: Long, role: AgentRole): Int = 0
    }

    private class FakeStoryRunRepository : StoryRunRepository {
        override fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord =
            StoryRunRecord(7, storyKey, targetRepo)

        override fun get(storyRunId: Long): StoryRunRecord? =
            StoryRunRecord(7, "KAN-69", "git@example/repo.git")

        override fun updatePullRequest(
            storyRunId: Long,
            branchName: String,
            prNumber: Int,
            prUrl: String?,
            baseBranch: String?,
            branchPrefix: String?,
            previewUrlTemplate: String?,
            previewNamespaceTemplate: String?,
            previewDbSecretRecipe: String?,
        ) = Unit

        override fun activePullRequests(): List<StoryRunRecord> = emptyList()

        override fun activeRuns(): List<StoryRunRecord> = emptyList()

        override fun close(storyRunId: Long, finalStatus: String, endedAt: OffsetDateTime) = Unit
    }

    private class FakeAgentRuntime(
        private val runningContainers: Set<String>,
    ) : AgentRuntime {
        override fun dispatch(request: AgentDispatchRequest): AgentDispatchResult =
            throw UnsupportedOperationException()

        override fun isContainerRunning(containerName: String): Boolean =
            containerName in runningContainers

        override fun isAgentRunning(storyKey: String, role: AgentRole): Boolean = false

        override fun isAnyAgentRunningForStory(storyKey: String): Boolean = false

        override fun runningCount(role: AgentRole?): Int = 0

        override fun killForStory(storyKey: String): Int = 0
    }
}
