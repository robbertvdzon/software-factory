package nl.vdzon.softwarefactory.web.models

import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerProject
import nl.vdzon.softwarefactory.nightly.NightlyJob
import nl.vdzon.softwarefactory.nightly.NightlySettings
import java.time.OffsetDateTime

sealed interface UiBriefingItem {
    val timestamp: OffsetDateTime
}

data class UiBriefingAgentRun(
    val agentRun: UiAgentRun,
) : UiBriefingItem {
    override val timestamp: OffsetDateTime = agentRun.startedAt
}

data class UiBriefingUserComment(
    val id: String,
    val authorName: String?,
    val body: String,
    val created: OffsetDateTime,
) : UiBriefingItem {
    override val timestamp: OffsetDateTime = created
}

data class UiStoryRun(
    val id: Long,
    val storyKey: String,
    val targetRepo: String,
    val workspacePath: String?,
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
    val subtaskKey: String? = null,
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
    // Story-keys die (al) gemerged zijn — voor de merged-indicator in het overzicht.
    val mergedStoryKeys: Set<String> = emptySet(),
    // Voor het "Nieuwe story"-formulier: keuzelijsten.
    val projects: List<TrackerProject> = emptyList(),
    val repoNames: List<String> = emptyList(),
)

/** "My actions"-inbox: alle (sub)taken die op de mens wachten, gegroepeerd per story. */
data class MyActionsPageData(
    val groups: List<MyActionsStoryGroup>,
    val errors: List<String>,
)

data class MyActionsStoryGroup(
    val storyKey: String,
    val storySummary: String,
    val prUrl: String?,
    /** Runs van de owner-story (story + subtaken) — voor de "Bekijk resultaat" in goedkeur-kaarten. */
    val runs: List<UiAgentRun>,
    val items: List<MyActionItem>,
)

data class MyActionItem(
    val issue: TrackerIssue,
    val isSubtask: Boolean,
    /** Vraagtekst bij een `*-with-questions`-fase, anders null. */
    val question: String?,
)

data class StoryDetailPageData(
    val issue: TrackerIssue?,
    val storyKey: String,
    val run: UiStoryRun?,
    val agentRuns: List<UiAgentRun>,
    /** Alle agent-runs van de story-run (story + alle subtaken); voor de gecombineerde story-briefing. */
    val allAgentRuns: List<UiAgentRun> = emptyList(),
    val events: List<UiAgentEvent>,
    val youTrackUrl: String,
    val previewUrl: String?,
    val errors: List<String>,
    /** Subtaken van deze story (alleen gevuld voor een STORY-detail). */
    val subtasks: List<TrackerIssue> = emptyList(),
    /** Parent-story-key (alleen gevuld wanneer dit issue zelf een subtask is). */
    val parentKey: String? = null,
    /**
     * Laatste agent-bericht per issue-key (story + elke subtask): de vraag die de agent stelde.
     * Gebruikt om de vraag in de actiekaart te tonen wanneer een issue in een `*-with-questions`-fase staat.
     */
    val agentQuestions: Map<String, String> = emptyMap(),
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
    val version: FactoryVersionInfo,
    val nightly: NightlySettings,
    /** Optionele feedback na opslaan van de nightly-settings (`saved`/`invalid`). */
    val nightlySaveResult: String? = null,
)

/** Versie-/startinfo van het draaiende factory-proces, vastgelegd bij opstart. */
data class FactoryVersionInfo(
    val startedAt: OffsetDateTime,
    val branch: String,
    val commitShort: String,
    val commitSubject: String,
    val commitDate: String,
    /** Waren er ongecommitte wijzigingen toen de factory startte? */
    val dirty: Boolean,
)

data class PrdVersionInfo(
    val commitShort: String,
    val commitDate: String,
    val branch: String,
)

data class ProjectOverviewItem(
    val name: String,
    val repoUrl: String,
    val storiesTodo: Int,
    val storiesInProgress: Int,
    val storiesDone: Int,
    val totalCostUsd: Double,
    val activeAgentCount: Int,
    val prdVersion: PrdVersionInfo?,
    val hasDeployConfig: Boolean,
)

data class ProjectsPageData(
    val projects: List<ProjectOverviewItem>,
    val errors: List<String>,
)

/** Eén `.apk`-download uit een GitHub-release (zie [nl.vdzon.softwarefactory.web.services.GitHubReleaseClient]). */
data class DownloadInfo(
    val repository: String,
    val projectKey: String,
    val name: String,
    val size: Long,
    val createdAt: String?,
    val downloadUrl: String,
    val releaseTag: String?,
    val releaseUrl: String?,
)

data class DownloadsPageData(
    val downloads: List<DownloadInfo>,
    val errors: List<String>,
)

data class NightlyJobsPageData(
    val jobs: List<NightlyJob>,
    val errors: List<String>,
    /** Status van de huidige/laatste automatische run (scheduler), of null als er nog geen run is. */
    val run: NightlyRunView? = null,
    /** Feedback na een actie (Run nu / onderbreken): `started`/`busy`/`stopped`/`stop-none`. */
    val runNotice: String? = null,
)

/** Statusweergave van één automatische nightly-run op `/nightly`, per project gescheiden. */
data class NightlyRunView(
    val runDate: java.time.LocalDate,
    val status: String,
    /** 'scheduled' (automatisch) of 'manual' (handmatig gestart). */
    val kind: String,
    val startedAt: OffsetDateTime?,
    val endedAt: OffsetDateTime?,
    val summarySentAt: OffsetDateTime?,
    val summaryText: String?,
    val projects: List<NightlyRunProjectView>,
)

data class NightlyRunProjectView(
    val project: String,
    val jobs: List<NightlyRunJobView>,
)

data class NightlyRunJobView(
    val jobName: String,
    val title: String,
    val status: String,
    val storyKey: String?,
    /** Wanneer deze job (de story ervan) in deze run gestart is, of null als nog niet gestart. */
    val startedAt: OffsetDateTime? = null,
)
