package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.youtrack.AgentRole
import java.time.OffsetDateTime

interface StoryRunRepository {
    fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord

    fun get(storyRunId: Long): StoryRunRecord?

    fun updatePullRequest(
        storyRunId: Long,
        branchName: String,
        prNumber: Int,
        prUrl: String?,
        baseBranch: String?,
        branchPrefix: String?,
        previewUrlTemplate: String?,
        previewNamespaceTemplate: String?,
        previewDbSecretRecipe: String?,
    )

    fun activePullRequests(): List<StoryRunRecord>

    fun activeRuns(): List<StoryRunRecord>

    fun close(storyRunId: Long, finalStatus: String, endedAt: OffsetDateTime)
}

data class StoryRunRecord(
    val id: Long,
    val storyKey: String,
    val targetRepo: String,
    val branchName: String? = null,
    val prNumber: Int? = null,
    val prUrl: String? = null,
    val baseBranch: String? = null,
    val branchPrefix: String? = null,
    val previewUrlTemplate: String? = null,
    val previewNamespaceTemplate: String? = null,
    val previewDbSecretRecipe: String? = null,
    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0,
    val totalCacheReadTokens: Long = 0,
    val totalCacheCreationTokens: Long = 0,
    val totalCostUsdEst: Double = 0.0,
) {
    val totalTokens: Long =
        totalInputTokens + totalOutputTokens + totalCacheReadTokens + totalCacheCreationTokens
}

data class SystemStateRecord(
    val creditsPausedUntil: OffsetDateTime?,
    val creditsPausedReason: String?,
)

interface SystemStateRepository {
    fun current(): SystemStateRecord

    fun pauseCredits(until: OffsetDateTime, reason: String)

    fun resumeCredits()
}

interface AgentRunRepository {
    fun recordStarted(
        storyRunId: Long,
        role: AgentRole,
        containerName: String,
        model: String?,
        effort: String?,
        level: Int?,
        workspacePath: String?,
    ): Long

    fun complete(containerName: String, completion: AgentRunCompletionRecord, endedAt: OffsetDateTime): CompletedAgentRun?

    fun addUsageToStoryRun(storyRunId: Long, completion: AgentRunCompletionRecord)

    fun latestForRole(storyRunId: Long, role: AgentRole): AgentRunRecord?

    fun recentForRole(storyRunId: Long, role: AgentRole, limit: Int): List<AgentRunRecord>

    fun countForRole(storyRunId: Long, role: AgentRole): Int
}

data class AgentRunRecord(
    val id: Long,
    val storyRunId: Long,
    val role: AgentRole,
    val startedAt: OffsetDateTime,
    val endedAt: OffsetDateTime?,
    val outcome: String?,
    val summaryText: String?,
    val model: String? = null,
    val effort: String? = null,
    val level: Int? = null,
    val workspacePath: String? = null,
)

data class AgentRunCompletionRecord(
    val outcome: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadInputTokens: Int,
    val cacheCreationInputTokens: Int,
    val numTurns: Int,
    val durationMs: Int,
    val costUsdEst: Double,
    val summaryText: String?,
)

data class CompletedAgentRun(
    val agentRunId: Long,
    val storyRunId: Long,
    val workspacePath: String?,
)
