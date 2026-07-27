package nl.vdzon.softwarefactory.dashboard.models

import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.contracts.TrackerProject
import nl.vdzon.softwarefactory.dashboard.types.BuildSyncStatus
import nl.vdzon.softwarefactory.dashboard.types.DeployRolloutStage
import nl.vdzon.softwarefactory.dashboard.types.DeployTargetRuntimeStatus
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
    // Story 5 (deployedAt/StoryDeployReconciler): apart van het story-afrondingsproces gezet.
    val deployedAt: OffsetDateTime? = null,
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
    /**
     * Door deze story geraakte deploy-doelen (Story 4, multi-deployment-rollout), alleen gevuld
     * voor een STORY-detail met een DEPLOY-subtaak. Leeg + [deployRolloutStage] `null` betekent: geen
     * DEPLOY-subtaak (subtask-detail, of een (heel oude) story zonder deploy-subtaak). Leeg + niet-
     * `null` betekent: DEPLOY-subtaak bestaat, maar raakt geen enkel deploy-doel (bv. een docs-only
     * wijziging) — de UI moet dat als "geen deploy-doelen geraakt" tonen, niet als lege/kapotte sectie.
     */
    val deployTargets: List<DeployTargetStatusView> = emptyList(),
    /**
     * PR-vs-gemerged-onderscheid voor de DEPLOY-subtaak (Story 4): zie [DeployRolloutStage]. `null`
     * wanneer er geen DEPLOY-subtaak is.
     */
    val deployRolloutStage: DeployRolloutStage? = null,
)

/**
 * Eén door de story geraakt deploy-doel (naam) + zijn actuele [DeployTargetRuntimeStatus] (Story
 * 4 — story-detail per-onderdeel build-status). [DeployTargetRuntimeStatus] en [DeployRolloutStage]
 * staan als enums in `dashboard.types` (net als [BuildSyncStatus]) — deze `models`-named-interface
 * bevat alleen immutable data classes (zie `ModuleApiConventionTest`).
 */
data class DeployTargetStatusView(
    val name: String,
    val status: DeployTargetRuntimeStatus,
)

data class AgentsPageData(
    val activeAgentRuns: List<UiAgentRun>,
    val recentAgentRuns: List<UiAgentRun>,
    val errors: List<String>,
)

/** Chronologisch geordende (oudste eerst) logfeed van één agent-run (SF-1038). */
data class AgentLogPageData(
    val agentRunId: Long,
    val lines: List<AgentLogLine>,
    val errors: List<String>,
)

data class SettingsPageData(
    val username: String,
    val configuration: Map<String, String>,
    val version: FactoryVersionInfo,
    /** Globale aan/uit-schakelaar voor de audit-scheduler ([nl.vdzon.softwarefactory.audit.repositories.AuditSettings.enabled]). */
    val auditEnabled: Boolean,
    val auditProjectSettings: List<AuditProjectSettingsView>,
)

/** Per-project audit-instelling zoals getoond/bewerkt in Settings; `startTime` altijd opgelost
 * ("HH:MM" — per-project override, anders de globale default). */
data class AuditProjectSettingsView(
    val project: String,
    val startTime: String,
    val auditCount: Int,
)

/** Eén rij van de "audits per project"-tabel zoals de Settings-pagina 'm in één keer opslaat. */
data class AuditProjectSettingsSaveInput(
    val project: String,
    val startTime: String,
    val auditCount: Int,
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
    /** Doorgekoppeld van [nl.vdzon.softwarefactory.config.LiveComponentConfig.consoleUrl]; null = geen link. */
    val consoleUrl: String? = null,
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
    /**
     * Commit-sha waarop deze release is gebaseerd, geëxtraheerd uit de release-body (de CI-workflows
     * van deze repo's zetten daar altijd "commit &lt;sha&gt;" in, zie
     * [nl.vdzon.softwarefactory.dashboard.services.GitHubReleaseClient.extractCommitSha]) — er is geen
     * betrouwbaar API-veld hiervoor (`target_commitish` is doorgaans gewoon de branchnaam). Null als
     * niet te herleiden.
     */
    val commitSha: String? = null,
    /**
     * Sync-status t.o.v. de laatst afgeronde main-build van hetzelfde project (zelfde soort
     * vergelijking als [LiveComponentStatus.syncStatus]/[ProjectBuildStatus.syncStatus]), gezet door
     * [nl.vdzon.softwarefactory.dashboard.services.DashboardQueryService.downloads]. Default
     * `UNAVAILABLE`: [nl.vdzon.softwarefactory.dashboard.services.GitHubReleaseClient] zelf kent de
     * main-build-sha niet.
     */
    val syncStatus: BuildSyncStatus = BuildSyncStatus.UNAVAILABLE,
    /**
     * Android `versionCode` van deze release, geëxtraheerd uit de release-body (CI-workflows zetten
     * daar "build &lt;run_number&gt;" in, zelfde tekst als [commitSha] gebruikt — zie
     * [nl.vdzon.softwarefactory.dashboard.services.GitHubReleaseClient.extractBuildNumber]). Null als
     * niet te herleiden (bv. deze CI-workflow schrijft geen "build N" in de body).
     */
    val latestBuildNumber: Int? = null,
    /**
     * Android `applicationId` van de app achter deze release, uit de `apkPackages`-config van het
     * project (`nl.vdzon.softwarefactory.config.ApkPackageMapping`, gematcht op [releaseTag]-prefix).
     * Null als niet geconfigureerd — het "App-updates"-scherm valt dan terug op de oude
     * package-naam-loze heuristiek voor deze app.
     */
    val packageName: String? = null,
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

/** Eén open pull request van een repo (zie [nl.vdzon.softwarefactory.dashboard.services.GitHubActionsClient.openPullRequests]). */
data class PullRequestInfo(
    val number: Int,
    val headRef: String,
    val headSha: String,
    val htmlUrl: String,
    val updatedAt: String?,
    val title: String,
)

/** Laatste commit op één branch (zie [nl.vdzon.softwarefactory.dashboard.services.GitHubActionsClient.latestCommitOn]). */
data class CommitInfo(
    val sha: String,
    val message: String,
    val date: String?,
)

/**
 * Eén pull request opgezocht via z'n nummer (zie
 * [nl.vdzon.softwarefactory.dashboard.services.GitHubActionsClient.pullRequestByNumber]) — werkt,
 * i.t.t. [PullRequestInfo]/[nl.vdzon.softwarefactory.dashboard.services.GitHubActionsClient.openPullRequests],
 * ook voor een inmiddels gemergede of gesloten PR. [mergeCommitSha] is alleen gezet als [merged] true
 * is; nodig voor de Buildstraat-pagina om na een merge de build-/deploystatus van het exacte
 * merge-commit te tonen i.p.v. van de dan allang doorgeschoven main-tip.
 */
data class PullRequestDetail(
    val number: Int,
    val headRef: String,
    val htmlUrl: String,
    val title: String,
    val merged: Boolean,
    val mergeCommitSha: String?,
)

/**
 * Eén dot in de build-kolom van de branch-timeline (zie [BranchTimelineRow]): status van één
 * geconfigureerde workflow voor één exacte commit. [status] is `null` wanneer er voor deze exacte
 * commit-sha helemaal geen run van deze workflow bestaat — dat is het "niet getriggerd" geval (bv.
 * een path-filter die niet matchte), en moet in de UI zichtbaar anders zijn dan "nog niet gestart".
 */
data class BranchJobStatus(
    val workflowName: String,
    val status: String?,
    val conclusion: String?,
    val htmlUrl: String?,
    /** `run_started_at`/`created_at` van de run (zie [WorkflowRunInfo.runStartedAt]); null zonder run. */
    val startedAt: String? = null,
    /** `updated_at` van de run (zie [WorkflowRunInfo.updatedAt]); null zonder run. */
    val finishedAt: String? = null,
)

/**
 * Eén rij in de branch-timeline van het Projects-scherm: de default branch, één open PR, of —
 * zie [nl.vdzon.softwarefactory.dashboard.services.DashboardQueryService.branchTimelineForMergedPr] —
 * het merge-commit van een inmiddels gemergede PR. Dat laatste geval hergebruikt bewust
 * `kind == "main"` (met [branchName]/[prNumber] alsnog op de oorspronkelijke feature-branch/PR) om
 * ook daar de deploystatus te tonen; `kind` bepaalt in de UI alleen "toon [liveComponents]", niet
 * "dit is letterlijk de default branch".
 */
data class BranchTimelineRow(
    val kind: String,
    val branchName: String,
    val commitShortSha: String,
    val commitMessage: String,
    val commitDate: String?,
    val prNumber: Int? = null,
    val prUrl: String? = null,
    val jobs: List<BranchJobStatus>,
    /** Alleen gevuld voor [kind] == "main"; PR's deployen niet (behalve het gemergede-PR-geval hierboven). */
    val liveComponents: List<LiveComponentStatus> = emptyList(),
)

data class BranchTimelinePageData(
    val rows: List<BranchTimelineRow>,
    val errors: List<String>,
)

/**
 * Eén commit in de commit-historie van de Builds-tab (project → branch → laatste N commits, zie
 * [nl.vdzon.softwarefactory.dashboard.services.DashboardQueryService.buildHistoryFor]). [deployed] is
 * alleen een echte `IN_SYNC`/`OUT_OF_SYNC`-vergelijking op de default branch; op een feature-/PR-branch
 * is er nog geen staand preview-per-branch-statusendpoint, dus dan altijd `UNAVAILABLE` ("n.a.").
 */
data class BuildHistoryCommitRow(
    val sha: String,
    val shortSha: String,
    val message: String,
    val date: String?,
    val jobs: List<BranchJobStatus>,
    val deployed: BuildSyncStatus,
)

data class BuildHistoryPageData(
    val branch: String,
    val commits: List<BuildHistoryCommitRow>,
    /** True als deze pagina volledig gevuld was (`commits.size == perPage`) — optimistisch, geen exacte GitHub-telling. */
    val hasMore: Boolean,
    val errors: List<String>,
)

/**
 * Laatste 4 commits op de default branch van één project, zoals bijgehouden door
 * [nl.vdzon.softwarefactory.dashboard.services.RecentCommitsPoller] (elke minuut ververst,
 * in-memory — geen lazy TTL-cache zoals de rest van dit bestand). Voedt de Builds-tab z'n
 * auto-select: welk project/branch je als eerste te zien krijgt.
 */
data class ProjectRecentCommits(
    val project: String,
    val branch: String,
    val commits: List<CommitInfo>,
)

data class RecentCommitsPageData(
    val projects: List<ProjectRecentCommits>,
    /** Project met de meest recente commit over alle projecten heen, of null zonder data. */
    val mostRecentProject: String?,
    val mostRecentBranch: String?,
    val errors: List<String>,
)

/** Eén regel in de "open reports"-lijst voor een audit — bewust minimaal, alleen wanneer. */
data class AuditReportSummaryView(
    val id: Long,
    val generatedAt: OffsetDateTime,
)

data class AuditReportListPageData(
    val project: String,
    val auditType: String,
    val reports: List<AuditReportSummaryView>,
    val errors: List<String>,
)

/** Volledige inhoud van één audit-rapport, pas opgehaald als een rapport in de lijst wordt aangeklikt. */
data class AuditReportDetailView(
    val id: Long,
    val project: String,
    val auditType: String,
    val generatedAt: OffsetDateTime,
    val content: String,
    val score: Double?,
    val scoreLabel: String?,
    val proposedStoryKey: String?,
    val durationMs: Long?,
)

data class AuditMemoryPageData(
    val notes: List<AuditMemoryNoteView>,
)

/** Alle geconfigureerde audits voor alle projecten (ook nooit-gedraaide), gegroepeerd per project. */
data class AuditOverviewPageData(
    val projects: List<AuditProjectOverviewView>,
    val errors: List<String>,
)

data class AuditProjectOverviewView(
    val project: String,
    val audits: List<AuditOverviewEntryView>,
)

/** Eén geconfigureerde audit (uit `.factory/nightly/<type>/job.yaml`), met laatste-run-info indien beschikbaar. */
data class AuditOverviewEntryView(
    val auditType: String,
    val title: String,
    val enabled: Boolean,
    val lastRunAt: OffsetDateTime?,
    val lastRunDurationMs: Long?,
    val score: Double?,
    val scoreLabel: String?,
    val proposedStoryKey: String?,
    /** `"pending"`/`"running"` als deze audit nu in de actieve run zit, anders null. */
    val runStatus: String?,
    /** Starttijd van de huidige job, alleen gezet als [runStatus] = `"running"` (voor "al X bezig"). */
    val runStartedAt: OffsetDateTime?,
)

/** Eén memory-tip (`knowledge`-domein, rol `auditor`) — `category` is het audit-type. */
data class AuditMemoryNoteView(
    val project: String,
    val auditType: String,
    val key: String,
    val content: String,
    val updatedAt: OffsetDateTime?,
)
