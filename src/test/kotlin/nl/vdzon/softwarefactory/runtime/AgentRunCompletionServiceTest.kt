package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.jira.AgentRole
import nl.vdzon.softwarefactory.orchestrator.AgentRunCompletionRecord
import nl.vdzon.softwarefactory.orchestrator.AgentRunRecord
import nl.vdzon.softwarefactory.orchestrator.AgentRunRepository
import nl.vdzon.softwarefactory.orchestrator.CompletedAgentRun
import nl.vdzon.softwarefactory.orchestrator.StoryRunRecord
import nl.vdzon.softwarefactory.orchestrator.StoryRunRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

class AgentRunCompletionServiceTest {
    @Test
    fun `completion stores usage totals and redacted events`() {
        val runs = FakeAgentRunRepository()
        val storyRuns = FakeStoryRunRepository()
        val events = FakeAgentEventRepository()
        val service = AgentRunCompletionService(
            agentRunRepository = runs,
            storyRunRepository = storyRuns,
            agentEventRepository = events,
            clock = Clock.fixed(java.time.Instant.parse("2026-05-23T20:00:00Z"), ZoneOffset.UTC),
            objectMapper = jacksonObjectMapper(),
        )

        val response = service.complete(
            AgentRunCompleteRequest(
                storyKey = "KAN-69",
                role = "developer",
                containerName = "factory-kan-69-developer",
                outcome = "ok",
                summaryText = "done",
                inputTokens = 1000,
                outputTokens = 500,
                cacheReadInputTokens = 10,
                cacheCreationInputTokens = 20,
                numTurns = 2,
                durationMs = 1234,
                costUsdEst = 0.42,
                events = listOf(
                    AgentRunEventPayload("log", "SF_GITHUB_TOKEN=secret postgresql://user:pass@host/db"),
                    AgentRunEventPayload(
                        "github-pr",
                        """{"branchName":"ai/KAN-69","baseBranch":"main","branchPrefix":"ai/","prNumber":42,"prUrl":"https://github.example/pr/42","previewUrlTemplate":"https://app-pr-{pr_num}.example.com","previewNamespaceTemplate":"app-pr-{pr_num}","previewDbSecretRecipe":"printf db-url"}""",
                    ),
                ),
            ),
        )

        assertEquals(200, response.statusCode.value())
        assertEquals(1L, response.body?.agentRunId)
        assertEquals(7L, response.body?.storyRunId)
        assertEquals("ok", runs.completed.single().outcome)
        assertEquals(1000, runs.usageAdded.single().inputTokens)
        assertEquals(PullRequestUpdate(7L, "ai/KAN-69", 42, "main", "ai/", "https://app-pr-{pr_num}.example.com", "app-pr-{pr_num}", "printf db-url"), storyRuns.pullRequests.single())
        assertTrue(events.payloads.first()["payload"].toString().contains("SF_GITHUB_TOKEN=<redacted>"))
        assertTrue(events.payloads.first()["payload"].toString().contains("postgresql://<redacted>"))
    }

    private class FakeStoryRunRepository : StoryRunRepository {
        val pullRequests = mutableListOf<PullRequestUpdate>()

        override fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord =
            StoryRunRecord(7, storyKey, targetRepo)

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
        ) {
            pullRequests += PullRequestUpdate(
                storyRunId,
                branchName,
                prNumber,
                baseBranch,
                branchPrefix,
                previewUrlTemplate,
                previewNamespaceTemplate,
                previewDbSecretRecipe,
            )
        }

        override fun activePullRequests(): List<StoryRunRecord> = emptyList()

        override fun close(storyRunId: Long, finalStatus: String, endedAt: OffsetDateTime) = Unit
    }

    private data class PullRequestUpdate(
        val storyRunId: Long,
        val branchName: String,
        val prNumber: Int,
        val baseBranch: String?,
        val branchPrefix: String?,
        val previewUrlTemplate: String?,
        val previewNamespaceTemplate: String?,
        val previewDbSecretRecipe: String?,
    )

    private class FakeAgentRunRepository : AgentRunRepository {
        val completed = mutableListOf<AgentRunCompletionRecord>()
        val usageAdded = mutableListOf<AgentRunCompletionRecord>()

        override fun recordStarted(storyRunId: Long, role: AgentRole, containerName: String, level: Int?): Long = 1

        override fun complete(
            containerName: String,
            completion: AgentRunCompletionRecord,
            endedAt: OffsetDateTime,
        ): CompletedAgentRun? {
            completed += completion
            return CompletedAgentRun(agentRunId = 1, storyRunId = 7)
        }

        override fun addUsageToStoryRun(storyRunId: Long, completion: AgentRunCompletionRecord) {
            usageAdded += completion
        }

        override fun latestForRole(storyRunId: Long, role: AgentRole): AgentRunRecord? = null

        override fun recentForRole(storyRunId: Long, role: AgentRole, limit: Int): List<AgentRunRecord> = emptyList()

        override fun countForRole(storyRunId: Long, role: AgentRole): Int = 0
    }

    private class FakeAgentEventRepository : AgentEventRepository {
        val payloads = mutableListOf<Map<String, Any?>>()

        override fun append(agentRunId: Long, kind: String, payload: Map<String, Any?>) {
            payloads += payload
        }
    }
}
