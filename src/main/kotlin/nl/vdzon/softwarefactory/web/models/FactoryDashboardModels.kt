package nl.vdzon.softwarefactory.web.models

import nl.vdzon.softwarefactory.youtrack.TrackerIssue
import java.time.OffsetDateTime

data class UiStoryRun(
    val id: Long,
    val storyKey: String,
    val targetRepo: String,
    val startedAt: OffsetDateTime,
    val endedAt: OffsetDateTime?,
    val finalStatus: String?,
    val branchName: String?,
    val prNumber: Int?,
    val prUrl: String?,
    val baseBranch: String?,
    val branchPrefix: String?,
    val previewUrlTemplate: String?,
    val previewNamespaceTemplate: String?,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalCacheReadTokens: Long,
    val totalCacheCreationTokens: Long,
    val totalCostUsdEst: Double,
) {
    val totalTokens: Long =
        totalInputTokens + totalOutputTokens + totalCacheReadTokens + totalCacheCreationTokens
}

data class UiAgentRun(
    val id: Long,
    val storyRunId: Long,
    val storyKey: String,
    val role: String,
    val containerName: String,
    val model: String?,
    val effort: String?,
    val level: Int?,
    val startedAt: OffsetDateTime,
    val endedAt: OffsetDateTime?,
    val outcome: String?,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadInputTokens: Long,
    val cacheCreationInputTokens: Long,
    val numTurns: Int,
    val durationMs: Long,
    val costUsdEst: Double,
    val summaryText: String?,
    val workspacePath: String?,
) {
    val totalTokens: Long =
        inputTokens + outputTokens + cacheReadInputTokens + cacheCreationInputTokens
}

data class UiAgentEvent(
    val id: Long,
    val agentRunId: Long,
    val storyKey: String,
    val role: String,
    val ts: OffsetDateTime,
    val kind: String,
    val payloadText: String,
)

data class DashboardPageData(
    val issues: List<TrackerIssue>,
    val activeRuns: List<UiStoryRun>,
    val recentRuns: List<UiStoryRun>,
    val activeAgentRuns: List<UiAgentRun>,
    val errors: List<String>,
)

data class StoriesPageData(
    val issues: List<TrackerIssue>,
    val runsByStory: Map<String, UiStoryRun>,
    val errors: List<String>,
)

data class StoryDetailPageData(
    val issue: TrackerIssue?,
    val storyKey: String,
    val run: UiStoryRun?,
    val agentRuns: List<UiAgentRun>,
    val events: List<UiAgentEvent>,
    val youTrackUrl: String,
    val previewUrl: String?,
    val errors: List<String>,
)

data class AgentsPageData(
    val activeAgentRuns: List<UiAgentRun>,
    val recentAgentRuns: List<UiAgentRun>,
    val errors: List<String>,
)

data class MergedPageData(
    val mergedRuns: List<UiStoryRun>,
    val errors: List<String>,
)

data class SettingsPageData(
    val username: String,
    val configuration: Map<String, String>,
)
