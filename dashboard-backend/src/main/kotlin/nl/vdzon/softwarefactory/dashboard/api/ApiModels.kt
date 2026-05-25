package nl.vdzon.softwarefactory.dashboard.api

import java.time.OffsetDateTime

data class LoginRequest(val username: String = "", val password: String = "")
data class LoginResponse(val token: String, val username: String)
data class StateResponse(
    val activeStories: Int,
    val activeAgents: Int,
    val recentStories: List<StoryRunDto>,
    val activeAgentRuns: List<AgentRunDto>,
    val configuration: Map<String, String>,
)
data class StoriesResponse(val stories: List<StoryDto>)
data class StoryDetailResponse(
    val issue: StoryDto?,
    val run: StoryRunDto?,
    val agentRuns: List<AgentRunDto>,
    val events: List<AgentEventDto>,
)
data class CommandResponse(val queued: Boolean)

data class StoryDto(
    val key: String,
    val summary: String,
    val description: String?,
    val status: String,
    val targetRepo: String?,
    val aiSupplier: String?,
    val aiPhase: String?,
    val aiLevel: Int?,
    val aiTokenBudget: Long?,
    val aiTokensUsed: Long?,
    val paused: Boolean,
    val error: String?,
)

data class StoryRunDto(
    val id: Long,
    val storyKey: String,
    val targetRepo: String,
    val startedAt: OffsetDateTime?,
    val endedAt: OffsetDateTime?,
    val finalStatus: String?,
    val branchName: String?,
    val prNumber: Int?,
    val prUrl: String?,
    val totalTokens: Long,
    val totalCostUsd: Double,
)

data class AgentRunDto(
    val id: Long,
    val storyRunId: Long,
    val storyKey: String,
    val role: String,
    val containerName: String,
    val model: String?,
    val effort: String?,
    val level: Int?,
    val startedAt: OffsetDateTime?,
    val endedAt: OffsetDateTime?,
    val outcome: String?,
    val totalTokens: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheCreationTokens: Long,
    val turns: Int,
    val durationMs: Long,
    val costUsd: Double,
    val summary: String?,
)

data class AgentEventDto(
    val id: Long,
    val agentRunId: Long,
    val storyKey: String,
    val role: String,
    val timestamp: OffsetDateTime?,
    val kind: String,
    val payload: String,
)
