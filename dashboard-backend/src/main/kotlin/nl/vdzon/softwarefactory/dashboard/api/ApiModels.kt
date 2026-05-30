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
data class RepositoriesResponse(val repositories: List<ManagedRepositoryDto>)
data class RepositoryDetailResponse(
    val repository: ManagedRepositoryDto,
    val workflows: List<WorkflowDto>,
    val runs: List<WorkflowRunDto>,
    val downloads: List<DownloadDto>,
    val stories: List<StoryDto>,
)
data class WorkflowsResponse(val workflows: List<WorkflowDto>, val runs: List<WorkflowRunDto>)
data class ReleasesResponse(val downloads: List<DownloadDto>)
data class DownloadsResponse(val downloads: List<DownloadDto>, val repositories: List<ManagedRepositoryDto>)
data class StoryDetailResponse(
    val issue: StoryDto?,
    val run: StoryRunDto?,
    val agentRuns: List<AgentRunDto>,
    val events: List<AgentEventDto>,
    val screenshots: List<ScreenshotDto>,
)
data class ScreenshotsResponse(val screenshots: List<ScreenshotDto>)
data class CommandResponse(val queued: Boolean)
data class OpenWorkspaceResponse(val opened: Boolean, val path: String)

data class ManagedRepositoryDto(
    val projectKey: String,
    val projectName: String,
    val repoUrl: String,
    val owner: String,
    val repo: String,
    val defaultBranch: String?,
    val workflowCount: Int,
    val latestWorkflow: String?,
    val latestConclusion: String?,
    val latestRunTitle: String?,
    val latestRunUrl: String?,
    val latestRunCreatedAt: String?,
    val apkAvailable: Boolean,
    val latestApkName: String?,
    val latestApkUrl: String?,
    val latestApkSize: Long?,
    val latestReleaseTag: String?,
    val activeStories: Int,
    val blockedStories: Int,
)

data class WorkflowDto(
    val id: Long,
    val name: String,
    val path: String?,
    val state: String?,
    val url: String?,
)

data class WorkflowRunDto(
    val id: Long,
    val name: String?,
    val displayTitle: String?,
    val status: String?,
    val conclusion: String?,
    val event: String?,
    val headBranch: String?,
    val headSha: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val runStartedAt: String?,
    val url: String?,
)

data class DownloadDto(
    val repository: String,
    val projectKey: String?,
    val name: String,
    val size: Long,
    val createdAt: String?,
    val downloadUrl: String,
    val releaseTag: String?,
    val releaseUrl: String?,
)

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
    val workspacePath: String?,
    val startedAt: OffsetDateTime?,
    val endedAt: OffsetDateTime?,
    val finalStatus: String?,
    val branchName: String?,
    val prNumber: Int?,
    val prUrl: String?,
    val previewUrl: String?,
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

data class ScreenshotDto(
    val id: String,
    val name: String,
    val size: Long?,
    val createdAt: String?,
    val mimeType: String?,
    val imageUrl: String,
)
