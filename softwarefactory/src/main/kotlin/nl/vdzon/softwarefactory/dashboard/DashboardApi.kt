package nl.vdzon.softwarefactory.dashboard

import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.dashboard.models.*

interface DashboardQueries {
    fun dashboard(): DashboardPageData
    fun stories(): StoriesPageData
    fun storyDetail(storyKey: String): StoryDetailPageData
    fun myActions(): MyActionsPageData
    fun myActionsCount(): Int
    fun agents(): AgentsPageData
    fun agentLog(agentRunId: Long): AgentLogPageData
    fun merged(): MergedPageData
    fun projectsOverview(force: Boolean = false): ProjectsPageData
    fun nightlyJobs(runNotice: String? = null): NightlyJobsPageData
    fun settings(username: String, nightlySaveResult: String? = null): SettingsPageData
    fun downloads(force: Boolean = false): DownloadsPageData
    fun builds(force: Boolean = false): BuildsPageData
    fun buildsFor(owner: String, repo: String): List<WorkflowRunInfo>
}

interface DashboardCommands {
    fun createStory(command: CreateStoryCommand): TrackerIssue
    fun createNightlyStory(project: String, jobName: String): TrackerIssue
    fun setQuestionsAllowedFlag(storyKey: String, enabled: Boolean)
    fun setApprovalMode(storyKey: String, mode: String)
    fun setNotifyMode(storyKey: String, mode: String)
    fun editStory(storyKey: String, description: String?, aiSupplier: String?, aiModel: String?)
    fun forceProjectDeploy(projectName: String)
    fun saveNightlySettings(enabled: Boolean, startTime: String, summaryTime: String)
    fun purgeStory(storyKey: String)
    fun startRefining(storyKey: String)
    fun startDeveloping(storyKey: String)
    fun openWorkspaceInIntellij(storyKey: String): String
}

interface FactoryProcessControl { fun requestRestart(); fun requestStop() }
interface DashboardChangeSource { fun addListener(listener: () -> Unit) }
interface FactoryVersionQuery {
    fun info(): FactoryVersionInfo
    fun commitShort(): String
}
