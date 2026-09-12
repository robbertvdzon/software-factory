package nl.vdzon.softwarefactory.web.controllers

import nl.vdzon.softwarefactory.core.contracts.ProductFactoryAttachmentNames
import nl.vdzon.softwarefactory.core.contracts.TelegramAssistantApi
import nl.vdzon.softwarefactory.core.contracts.TesterScreenshots
import nl.vdzon.softwarefactory.core.contracts.TrackerAttachment
import nl.vdzon.softwarefactory.dashboard.DashboardQueries
import nl.vdzon.softwarefactory.tracker.AttachmentPort
import nl.vdzon.softwarefactory.web.services.DashboardAuthInterceptor
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Leesendpoints van het dashboard. Elke methode delegeert naar [DashboardQueries] en geeft de
 * pagina-data uit `dashboard :: models` ongewijzigd als JSON terug. Authenticatie gebeurt in de
 * interceptor (zie `DashboardWebConfiguration`).
 */
@RestController
@RequestMapping("/api/v1")
class DashboardQueryController(
    private val dashboard: DashboardQueries,
    private val attachments: AttachmentPort,
    private val assistant: TelegramAssistantApi,
) {
    @GetMapping("/dashboard")
    fun dashboard() = dashboard.dashboard()

    @GetMapping("/stories")
    fun stories() = dashboard.stories()

    @GetMapping("/stories/{storyKey}")
    fun storyDetail(@PathVariable storyKey: String) = dashboard.storyDetail(storyKey)

    @GetMapping("/stories/{storyKey}/screenshots")
    fun storyScreenshots(@PathVariable storyKey: String): ScreenshotListBody {
        val screenshots = attachments.listIssueAttachments(storyKey)
            .filter { it.name.startsWith(TesterScreenshots.ATTACHMENT_PREFIX) }
            .map { ScreenshotInfo(id = it.id, name = it.name, size = it.size, createdAt = it.created, mimeType = it.mimeType) }
        return ScreenshotListBody(screenshots)
    }

    @GetMapping("/stories/{storyKey}/screenshots/{attachmentId}/image")
    fun screenshotImage(@PathVariable storyKey: String, @PathVariable attachmentId: String): ResponseEntity<ByteArray> {
        val attachment = attachments.listIssueAttachments(storyKey).firstOrNull { it.id == attachmentId }
            ?: throw DashboardNotFoundException("Attachment $attachmentId niet gevonden op $storyKey.")
        return ResponseEntity.ok()
            .header("Cache-Control", "private, max-age=60")
            .contentType(mediaTypeOf(attachment))
            .body(bytesOf(attachment))
    }

    @GetMapping("/stories/{storyKey}/product-factory-attachments/{attachmentId}/content")
    fun productFactoryAttachmentContent(@PathVariable storyKey: String, @PathVariable attachmentId: String): ResponseEntity<ByteArray> {
        val attachment = attachments.listIssueAttachments(storyKey).firstOrNull {
            it.id == attachmentId && ProductFactoryAttachmentNames.parse(it.name) != null
        } ?: throw DashboardNotFoundException("Product Factory-attachment $attachmentId niet gevonden op $storyKey.")
        return ResponseEntity.ok().contentType(mediaTypeOf(attachment)).body(bytesOf(attachment))
    }

    @GetMapping("/my-actions")
    fun myActions() = dashboard.myActions()

    @GetMapping("/my-actions/count")
    fun myActionsCount() = CountBody(dashboard.myActionsCount())

    @GetMapping("/agents")
    fun agents() = dashboard.agents()

    @GetMapping("/agents/{agentRunId}/events")
    fun agentLog(@PathVariable agentRunId: Long) = dashboard.agentLog(agentRunId)

    @GetMapping("/assistant/status")
    fun assistantStatus() = assistant.status()

    @GetMapping("/projects")
    fun projects(@RequestParam("refresh", required = false) refresh: Boolean?) =
        dashboard.projectsOverview(force = refresh ?: false)

    /** Lazy: het Projects-scherm haalt dit pas op zodra de branch-timeline-sectie van dit project wordt uitgeklapt. */
    @GetMapping("/projects/{name}/branch-timeline")
    fun branchTimeline(@PathVariable name: String) = dashboard.branchTimelineFor(name)

    /**
     * Fallback voor de Buildstraat-pagina zodra een story-branch al gemerged is (dan levert
     * [branchTimeline] hierboven geen rij meer voor die branch/PR): build-/deploystatus van precies
     * het merge-commit van deze PR, i.p.v. de dan allang doorgeschoven main-tip.
     */
    @GetMapping("/projects/{name}/branch-timeline/pr/{prNumber}")
    fun branchTimelineForMergedPr(@PathVariable name: String, @PathVariable prNumber: Int) =
        dashboard.branchTimelineForMergedPr(name, prNumber)

    /** Builds-tab: commit-historie van [branch] van [name], [perPage] commits vanaf [page] (1-based). */
    @GetMapping("/projects/{name}/build-history")
    fun buildHistory(
        @PathVariable name: String,
        @RequestParam("branch") branch: String,
        @RequestParam("page", required = false) page: Int?,
        @RequestParam("perPage", required = false) perPage: Int?,
    ) = dashboard.buildHistoryFor(name, branch, page ?: 1, perPage ?: DEFAULT_BUILD_HISTORY_PAGE_SIZE)

    /** Builds-tab: elke minuut ververste snapshot van de laatste commits per project (zie `RecentCommitsPoller`). */
    @GetMapping("/projects/recent-commits")
    fun recentCommits() = dashboard.recentCommits()

    /** Zelfde data als de publieke `GET /api/v1/public/changelog/{name}`, maar geauthenticeerd. */
    @GetMapping("/changelog/{name}")
    fun changelog(@PathVariable name: String) = dashboard.changelogFor(name)

    @GetMapping("/settings")
    fun settings(@RequestAttribute(DashboardAuthInterceptor.USER_ATTRIBUTE) user: String) = dashboard.settings(user)

    @GetMapping("/downloads")
    fun downloads(@RequestParam("refresh", required = false) refresh: Boolean?) = dashboard.downloads(force = refresh ?: false)

    @GetMapping("/builds")
    fun builds(@RequestParam("refresh", required = false) refresh: Boolean?) = dashboard.builds(force = refresh ?: false)

    @GetMapping("/repositories/{owner}/{repo}/workflows")
    fun repositoryWorkflows(@PathVariable owner: String, @PathVariable repo: String) = repositoryRuns(owner, repo)

    @GetMapping("/repositories/{owner}/{repo}/runs")
    fun repositoryRuns(@PathVariable owner: String, @PathVariable repo: String) = RunsBody(dashboard.buildsFor(owner, repo))

    @GetMapping("/audits/reports")
    fun auditReportsList(@RequestParam("project") project: String, @RequestParam("auditType") auditType: String) =
        dashboard.auditReportsFor(project, auditType)

    @GetMapping("/audits/reports/{id}")
    fun auditReportDetail(@PathVariable id: Long) = dashboard.auditReportDetail(id)

    @GetMapping("/audit-memory")
    fun auditMemory() = dashboard.auditMemory()

    @GetMapping("/audits/overview")
    fun auditOverview() = dashboard.auditOverview()

    @GetMapping("/audits/questions")
    fun auditQuestions() = dashboard.auditQuestions()

    /** Aantal openstaande auditvragen — voedt het badge-bolletje op het Audits-nav-item. */
    @GetMapping("/audits/questions/count")
    fun auditQuestionCount() = CountBody(dashboard.auditQuestions().questions.size)

    @GetMapping("/maintenance/cleanups")
    fun maintenanceCleanups(
        @RequestParam("project", required = false) project: String?,
        @RequestParam("kind", required = false) kind: String?,
    ) = dashboard.maintenanceCleanups(project, kind)

    @GetMapping("/maintenance/cleanups/{id}")
    fun maintenanceCleanupDetail(@PathVariable id: Long) =
        dashboard.maintenanceCleanupDetail(id) ?: throw DashboardNotFoundException("Maintenance-cleanup-run $id niet gevonden.")

    private fun bytesOf(attachment: TrackerAttachment): ByteArray =
        attachments.downloadAttachmentBytes(attachment)
            ?: throw DashboardNotFoundException("Kon attachment ${attachment.id} niet downloaden.")

    private fun mediaTypeOf(attachment: TrackerAttachment): MediaType =
        attachment.mimeType?.takeIf { it.isNotBlank() }?.let { MediaType.parseMediaType(it) } ?: MediaType.APPLICATION_OCTET_STREAM

    private companion object {
        const val DEFAULT_BUILD_HISTORY_PAGE_SIZE = 4
    }
}

data class CountBody(val count: Int)
data class ScreenshotInfo(val id: String, val name: String, val size: Long?, val createdAt: Long?, val mimeType: String?)
data class ScreenshotListBody(val screenshots: List<ScreenshotInfo>)
data class RunsBody(val runs: List<nl.vdzon.softwarefactory.dashboard.models.WorkflowRunInfo>)
