package nl.vdzon.softwarefactory.dashboard.models

import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.contracts.TrackerProject
import nl.vdzon.softwarefactory.dashboard.types.BuildSyncStatus
import nl.vdzon.softwarefactory.nightly.services.NightlyJob
import nl.vdzon.softwarefactory.nightly.repositories.NightlySettings
import nl.vdzon.softwarefactory.runtime.models.AgentLogLine
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

/** Detailweergave van de gecapturede docker-stdout/stderr-log voor één agent-run (SF-1010). */
data class AgentLogPageData(
    val agentRunId: Long,
    val lines: List<AgentLogLine>,
    val outcome: String?,
    /** True zodra de run een `endedAt` heeft; de frontend stopt dan met pollen. */
    val ended: Boolean,
    val errors: List<String> = emptyList(),
)

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
    /** Laatste runs op de default branch van een beheerd repo met `conclusion == failure` (SF-876). */
    val attentionBuilds: List<WorkflowRunInfo> = emptyList(),
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

/**
 * Sync-status van de productieversie ([PrdVersionInfo]) t.o.v. de laatst afgeronde main-build
 * (zie [nl.vdzon.softwarefactory.dashboard.services.DashboardQueryService.buildStatusFor]).
 * `UNAVAILABLE` geldt zowel voor projecten zonder deploy-configuratie als voor projecten waarvoor
 * de vergelijking (nog) niet te maken is (geen prd-versie of geen bekende main-build-sha).
 */
/** Build-/deploy-status per project op het Projects-scherm (zie [ProjectOverviewItem]). */
data class ProjectBuildStatus(
    /** Tijdstip van de laatst afgeronde workflow-run met `event == push` op de default branch. */
    val lastMainBuildAt: String?,
    /** Loopt er nu een workflow-run (`queued`/`in_progress`) op de default branch. */
    val mainBuildActive: Boolean,
    /** Loopt er nu een workflow-run (`queued`/`in_progress`) voor een open PR. */
    val prBuildActive: Boolean,
    val syncStatus: BuildSyncStatus,
)

/**
 * Live-status van één OpenShift-deployment (zie [nl.vdzon.softwarefactory.config.LiveComponentConfig])
 * op het Projects-scherm: welk image er nu draait en sinds wanneer, en of dat de laatste main-build is.
 */
data class LiveComponentStatus(
    val label: String,
    /** Korte commit-sha uit de image-tag (bv. `sha-66d1019` → `66d1019`), of null als niet te bepalen. */
    val shortSha: String?,
    /** `status.startTime` van de draaiende pod (RFC3339), of null als niet opvraagbaar. */
    val podStartedAt: String?,
    /** Afgeleid van [podStartedAt] t.o.v. nu; null als [podStartedAt] onbekend is. */
    val uptimeSeconds: Long?,
    val syncStatus: BuildSyncStatus,
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
    val buildStatus: ProjectBuildStatus,
    /** OpenShift-live-componenten van dit project (zie [LiveComponentStatus]); leeg = niet geconfigureerd. */
    val liveComponents: List<LiveComponentStatus> = emptyList(),
)

data class ProjectsPageData(
    val projects: List<ProjectOverviewItem>,
    val errors: List<String>,
)

/** Eén `.apk`-download uit een GitHub-release (zie [nl.vdzon.softwarefactory.dashboard.services.GitHubReleaseClient]). */
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

/** Laatste run van één workflow op [repository] (zie [nl.vdzon.softwarefactory.dashboard.services.GitHubActionsClient]). */
data class WorkflowRunInfo(
    val repository: String,
    val projectKey: String,
    val workflowName: String,
    val status: String,
    val conclusion: String?,
    val branch: String,
    val event: String,
    val durationSeconds: Long?,
    val updatedAt: String?,
    val htmlUrl: String,
    /** Commit-sha van de run (`head_sha`); leeg als de GitHub-response 'm niet meegeeft. */
    val headSha: String = "",
    /** `run_started_at`/`created_at` van de run, gebruikt voor "laatste main-build"-vergelijkingen. */
    val runStartedAt: String? = null,
)

data class RepoBuildsView(
    val projectKey: String,
    val repository: String,
    val runs: List<WorkflowRunInfo>,
)

data class BuildsPageData(
    val repos: List<RepoBuildsView>,
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
