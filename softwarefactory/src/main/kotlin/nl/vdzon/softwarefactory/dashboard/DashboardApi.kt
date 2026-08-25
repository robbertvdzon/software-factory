package nl.vdzon.softwarefactory.dashboard

import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.dashboard.models.*

interface DashboardQueries {
    fun dashboard(): DashboardPageData
    fun stories(): StoriesPageData
    fun storyDetail(storyKey: String): StoryDetailPageData
    fun productFactoryStories(filter: ProductFactoryStoryFilter): ProductFactoryStoriesPageData
    fun myActions(): MyActionsPageData
    fun myActionsCount(): Int
    fun agents(): AgentsPageData
    fun agentLog(agentRunId: Long): AgentLogPageData
    fun projectsOverview(force: Boolean = false): ProjectsPageData
    fun auditQuestions(): AuditQuestionsPageData
    fun auditMemory(): AuditMemoryPageData
    fun auditOverview(): AuditOverviewPageData
    fun auditReportsFor(project: String, auditType: String): AuditReportListPageData
    fun auditReportDetail(reportId: Long): AuditReportDetailView
    fun maintenanceCleanups(project: String? = null, kind: String? = null): MaintenanceCleanupListPageData
    /** `null` = onbekende run; de bridge vertaalt dat naar NOT_FOUND (HTTP 404). */
    fun maintenanceCleanupDetail(runId: Long): MaintenanceCleanupRunDetailView?
    fun settings(username: String): SettingsPageData
    fun downloads(force: Boolean = false): DownloadsPageData
    fun builds(force: Boolean = false): BuildsPageData
    fun buildsFor(owner: String, repo: String): List<WorkflowRunInfo>
    fun branchTimelineFor(name: String): BranchTimelinePageData
    fun branchTimelineForMergedPr(name: String, prNumber: Int): BranchTimelinePageData
    fun buildHistoryFor(name: String, branch: String, page: Int, perPage: Int): BuildHistoryPageData
    fun recentCommits(): RecentCommitsPageData
    fun changelogFor(name: String): ChangelogPageData
}

interface DashboardCommands {
    fun createStory(command: CreateStoryCommand): TrackerIssue
    fun setQuestionsAllowedFlag(storyKey: String, enabled: Boolean)
    fun setApprovalMode(storyKey: String, mode: String)
    fun setNotificationEvents(storyKey: String, events: Set<String>)
    fun editStory(storyKey: String, description: String?, descriptionSummary: String?, aiSupplier: String?, aiModel: String?)
    fun forceProjectDeploy(projectName: String)
    fun purgeStory(storyKey: String)
    fun startRefining(storyKey: String)
    fun queueStory(storyKey: String)
    fun updateAuditMemoryNote(project: String, auditType: String, key: String, content: String)
    fun deleteAuditMemoryNote(project: String, auditType: String, key: String)
    fun runAuditNow(project: String, auditType: String): AuditRunNowResult

    /**
     * Start één opruimronde nu (`kind` = een `CleanupKinds`-waarde of `CleanupKinds.ALL_KINDS`).
     * Niet-blokkerend; een weigering (draait al / uitgezet / onbekend) komt terug als status.
     */
    fun runCleanupNow(kind: String): CleanupRunNowResult

    /** Beantwoordt een auditvraag en plant meteen de vervolgrun in; false als 'ie al beantwoord was. */
    fun answerAuditQuestion(questionId: Long, answer: String): Boolean
    fun saveAuditSettings(enabled: Boolean, projects: List<AuditProjectSettingsSaveInput>)
    fun startDeveloping(storyKey: String)
    fun openWorkspaceInIntellij(storyKey: String): String
}

interface FactoryProcessControl { fun requestRestart(); fun requestStop() }
interface DashboardChangeSource { fun addListener(listener: () -> Unit) }
interface FactoryVersionQuery {
    fun info(): FactoryVersionInfo
    fun commitShort(): String
}
