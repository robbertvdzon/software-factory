package nl.vdzon.softwarefactory.web

import nl.vdzon.softwarefactory.core.contracts.FactoryCommand
import nl.vdzon.softwarefactory.core.contracts.FactoryOperations
import nl.vdzon.softwarefactory.core.contracts.MergeReadyInfo
import nl.vdzon.softwarefactory.core.contracts.TrackerAttachment
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.dashboard.DashboardCommands
import nl.vdzon.softwarefactory.dashboard.DashboardQueries
import nl.vdzon.softwarefactory.dashboard.FactoryVersionQuery
import nl.vdzon.softwarefactory.dashboard.models.AgentLogPageData
import nl.vdzon.softwarefactory.dashboard.models.AgentsPageData
import nl.vdzon.softwarefactory.dashboard.models.AuditMemoryPageData
import nl.vdzon.softwarefactory.dashboard.models.AuditOverviewPageData
import nl.vdzon.softwarefactory.dashboard.models.AuditProjectSettingsSaveInput
import nl.vdzon.softwarefactory.dashboard.models.AuditQuestionsPageData
import nl.vdzon.softwarefactory.dashboard.models.AuditReportDetailView
import nl.vdzon.softwarefactory.dashboard.models.AuditReportListPageData
import nl.vdzon.softwarefactory.dashboard.models.AuditRunNowResult
import nl.vdzon.softwarefactory.dashboard.models.BranchTimelinePageData
import nl.vdzon.softwarefactory.dashboard.models.BuildHistoryPageData
import nl.vdzon.softwarefactory.dashboard.models.BuildsPageData
import nl.vdzon.softwarefactory.dashboard.models.ChangelogPageData
import nl.vdzon.softwarefactory.dashboard.models.CleanupRunNowResult
import nl.vdzon.softwarefactory.dashboard.models.CreateStoryCommand
import nl.vdzon.softwarefactory.dashboard.models.DashboardPageData
import nl.vdzon.softwarefactory.dashboard.models.DownloadsPageData
import nl.vdzon.softwarefactory.dashboard.models.FactoryVersionInfo
import nl.vdzon.softwarefactory.dashboard.models.MaintenanceCleanupListPageData
import nl.vdzon.softwarefactory.dashboard.models.MaintenanceCleanupRunDetailView
import nl.vdzon.softwarefactory.dashboard.models.MyActionsPageData
import nl.vdzon.softwarefactory.dashboard.models.ProductFactoryStoriesPageData
import nl.vdzon.softwarefactory.dashboard.models.ProductFactoryStoryFilter
import nl.vdzon.softwarefactory.dashboard.models.ProjectsPageData
import nl.vdzon.softwarefactory.dashboard.models.RecentCommitsPageData
import nl.vdzon.softwarefactory.dashboard.models.SettingsPageData
import nl.vdzon.softwarefactory.dashboard.models.StoriesPageData
import nl.vdzon.softwarefactory.dashboard.models.StoryDetailPageData
import nl.vdzon.softwarefactory.dashboard.models.WorkflowRunInfo
import nl.vdzon.softwarefactory.tracker.AttachmentPort
import java.time.OffsetDateTime

/**
 * Handgeschreven stubs voor de dashboard-poorten: alles gooit standaard, een test overschrijft
 * alleen wat het pad nodig heeft. Zo blijft zichtbaar welke poorten een endpoint écht raakt.
 */
internal open class StubDashboardQueries : DashboardQueries {
    private fun unexpected(name: String): Nothing = error("niet verwacht in deze test: $name")
    override fun dashboard(): DashboardPageData = unexpected("dashboard")
    override fun stories(): StoriesPageData = unexpected("stories")
    override fun storyDetail(storyKey: String): StoryDetailPageData = unexpected("storyDetail")
    override fun productFactoryStories(filter: ProductFactoryStoryFilter): ProductFactoryStoriesPageData = unexpected("productFactoryStories")
    override fun myActions(): MyActionsPageData = unexpected("myActions")
    override fun myActionsCount(): Int = unexpected("myActionsCount")
    override fun agents(): AgentsPageData = unexpected("agents")
    override fun agentLog(agentRunId: Long): AgentLogPageData = unexpected("agentLog")
    override fun projectsOverview(force: Boolean): ProjectsPageData = unexpected("projectsOverview")
    override fun auditQuestions(): AuditQuestionsPageData = unexpected("auditQuestions")
    override fun auditMemory(): AuditMemoryPageData = unexpected("auditMemory")
    override fun auditOverview(): AuditOverviewPageData = unexpected("auditOverview")
    override fun auditReportsFor(project: String, auditType: String): AuditReportListPageData = unexpected("auditReportsFor")
    override fun auditReportDetail(reportId: Long): AuditReportDetailView = unexpected("auditReportDetail")
    override fun maintenanceCleanups(project: String?, kind: String?): MaintenanceCleanupListPageData = unexpected("maintenanceCleanups")
    override fun maintenanceCleanupDetail(runId: Long): MaintenanceCleanupRunDetailView? = unexpected("maintenanceCleanupDetail")
    override fun settings(username: String): SettingsPageData = unexpected("settings")
    override fun downloads(force: Boolean): DownloadsPageData = unexpected("downloads")
    override fun builds(force: Boolean): BuildsPageData = unexpected("builds")
    override fun buildsFor(owner: String, repo: String): List<WorkflowRunInfo> = unexpected("buildsFor")
    override fun branchTimelineFor(name: String): BranchTimelinePageData = unexpected("branchTimelineFor")
    override fun branchTimelineForMergedPr(name: String, prNumber: Int): BranchTimelinePageData = unexpected("branchTimelineForMergedPr")
    override fun buildHistoryFor(name: String, branch: String, page: Int, perPage: Int): BuildHistoryPageData = unexpected("buildHistoryFor")
    override fun recentCommits(): RecentCommitsPageData = unexpected("recentCommits")
    override fun changelogFor(name: String): ChangelogPageData = unexpected("changelogFor")
}

/** Registreert elke schrijfaanroep in [calls] als "naam(argumenten)", in volgorde. */
internal open class StubDashboardCommands : DashboardCommands {
    val calls = mutableListOf<String>()
    val createdStories = mutableListOf<CreateStoryCommand>()
    var nextStoryKey = "SF-3001"

    private fun unexpected(name: String): Nothing = error("niet verwacht in deze test: $name")
    override fun createStory(command: CreateStoryCommand): TrackerIssue {
        calls += "createStory"
        createdStories += command
        return TrackerIssue(key = nextStoryKey, summary = command.title, status = "open", description = command.description, comments = emptyList(), fields = nl.vdzon.softwarefactory.core.contracts.TrackerIssueFields(targetRepo = null, aiPhase = null, aiTokenBudget = null, aiTokensUsed = null, agentStartedAt = null, paused = false, error = null))
    }
    override fun setQuestionsAllowedFlag(storyKey: String, enabled: Boolean) = unexpected("setQuestionsAllowedFlag")
    override fun setApprovalMode(storyKey: String, mode: String) = unexpected("setApprovalMode")
    override fun setNotificationEvents(storyKey: String, events: Set<String>) = unexpected("setNotificationEvents")
    override fun editStory(storyKey: String, description: String?, descriptionSummary: String?, aiSupplier: String?, aiModel: String?) = unexpected("editStory")
    override fun forceProjectDeploy(projectName: String) = unexpected("forceProjectDeploy")
    override fun purgeStory(storyKey: String) = unexpected("purgeStory")
    override fun startRefining(storyKey: String) = unexpected("startRefining")
    override fun queueStory(storyKey: String) { calls += "queueStory($storyKey)" }
    override fun updateAuditMemoryNote(project: String, auditType: String, key: String, content: String) = unexpected("updateAuditMemoryNote")
    override fun deleteAuditMemoryNote(project: String, auditType: String, key: String) = unexpected("deleteAuditMemoryNote")
    override fun runAuditNow(project: String, auditType: String): AuditRunNowResult = unexpected("runAuditNow")
    override fun runCleanupNow(kind: String): CleanupRunNowResult = unexpected("runCleanupNow")
    override fun answerAuditQuestion(questionId: Long, answer: String): Boolean = unexpected("answerAuditQuestion")
    override fun saveAuditSettings(enabled: Boolean, projects: List<AuditProjectSettingsSaveInput>) = unexpected("saveAuditSettings")
    override fun startDeveloping(storyKey: String) = unexpected("startDeveloping")
    override fun saveProjectCatalog(yaml: String, updatedBy: String): Unit = unexpected("saveProjectCatalog")
}

internal open class StubFactoryOperations : FactoryOperations {
    val calls = mutableListOf<String>()
    private fun unexpected(name: String): Nothing = error("niet verwacht in deze test: $name")
    override fun questionFor(issue: TrackerIssue): String? = unexpected("questionFor")
    override fun autoApproveActive(issue: TrackerIssue): Boolean = unexpected("autoApproveActive")
    override fun mergeReady(storyKey: String): MergeReadyInfo? = unexpected("mergeReady")
    override fun mergeReadyForSubtask(subtask: TrackerIssue): MergeReadyInfo? = unexpected("mergeReadyForSubtask")
    override fun testerReportFor(storyKey: String): String? = unexpected("testerReportFor")
    override fun previewUrlFor(storyKey: String): String? = unexpected("previewUrlFor")
    override fun setStoryPhase(storyKey: String, phase: String, comment: String?) { calls += "setStoryPhase($storyKey,$phase,$comment)" }
    override fun setSubtaskPhase(subtaskKey: String, phase: String, comment: String?) { calls += "setSubtaskPhase($subtaskKey,$phase,$comment)" }
    override fun queueCommand(storyKey: String, command: FactoryCommand, reason: String?) { calls += "queueCommand($storyKey,${command.token},$reason)" }
}

/** In-memory bijlagen per story; registreert uploads in [uploads]. */
internal class StubAttachmentPort(
    private val existing: Map<String, List<TrackerAttachment>> = emptyMap(),
    private val bytes: Map<String, ByteArray> = emptyMap(),
) : AttachmentPort {
    val uploads = mutableListOf<Triple<String, String, ByteArray>>()
    private val attachments = existing.mapValues { it.value.toMutableList() }.toMutableMap()
    private val contents = bytes.toMutableMap()

    override fun listIssueAttachments(issueKey: String): List<TrackerAttachment> = attachments[issueKey].orEmpty()
    override fun downloadAttachmentBytes(attachment: TrackerAttachment): ByteArray? = contents[attachment.id]
    override fun uploadIssueAttachment(issueKey: String, name: String, mimeType: String, bytes: ByteArray): TrackerAttachment {
        uploads += Triple(issueKey, name, bytes)
        val attachment = TrackerAttachment(id = "up-${uploads.size}", name = name, url = null, mimeType = mimeType, size = bytes.size.toLong(), created = 1L)
        attachments.getOrPut(issueKey) { mutableListOf() } += attachment
        contents[attachment.id] = bytes
        return attachment
    }
    override fun deleteIssueAttachment(issueKey: String, attachmentId: String) = error("niet verwacht")
}

internal object StubVersion : FactoryVersionQuery {
    override fun info() = FactoryVersionInfo(OffsetDateTime.parse("2026-01-01T10:00:00Z"), "main", "abc1234", "test", "2026-01-01", false)
    override fun commitShort() = "abc1234"
}
