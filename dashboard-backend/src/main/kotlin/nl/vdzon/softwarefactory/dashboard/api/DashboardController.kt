package nl.vdzon.softwarefactory.dashboard.api

import nl.vdzon.softwarefactory.dashboard.config.DashboardSecrets
import nl.vdzon.softwarefactory.dashboard.database.DashboardRepository
import nl.vdzon.softwarefactory.dashboard.github.GitHubClient
import nl.vdzon.softwarefactory.dashboard.github.GitHubSlug
import nl.vdzon.softwarefactory.dashboard.youtrack.FactoryCommand
import nl.vdzon.softwarefactory.dashboard.youtrack.ProjectDto
import nl.vdzon.softwarefactory.dashboard.youtrack.YouTrackClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@RestController
class DashboardController(
    private val authService: AuthService,
    private val youTrackClient: YouTrackClient,
    private val githubClient: GitHubClient,
    private val repository: DashboardRepository,
    private val secrets: DashboardSecrets,
) {
    @GetMapping("/healthz")
    fun health(): Map<String, String> = mapOf("status" to "ok")

    @GetMapping("/api/v1/state")
    fun state(@RequestHeader("Authorization", required = false) authorization: String?): StateResponse {
        authService.requireAuthorization(authorization)
        val recentStories = repository.recentStoryRuns(10)
        val activeAgents = repository.activeAgentRuns(10)
        return StateResponse(
            activeStories = repository.activeStoryRuns(200).size,
            activeAgents = activeAgents.size,
            recentStories = recentStories,
            activeAgentRuns = activeAgents,
            configuration = secrets.redactedSummary,
        )
    }

    @GetMapping("/api/v1/stories")
    fun stories(@RequestHeader("Authorization", required = false) authorization: String?): StoriesResponse {
        authService.requireAuthorization(authorization)
        return StoriesResponse(youTrackClient.findWorkIssues(100))
    }

    @PostMapping("/api/v1/stories")
    fun createStory(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestBody request: CreateStoryRequest,
    ): StoryDto {
        authService.requireAuthorization(authorization)
        require(request.projectKey.isNotBlank()) { "projectKey is verplicht" }
        require(request.title.isNotBlank()) { "title is verplicht" }
        return youTrackClient.createIssue(
            projectKey = request.projectKey,
            targetRepo = request.targetRepo,
            aiSupplier = request.aiSupplier,
            aiModel = request.aiModel,
            budget = request.budget,
            title = request.title,
            description = request.description,
        )
    }

    @GetMapping("/api/v1/projects")
    fun projects(@RequestHeader("Authorization", required = false) authorization: String?): ProjectsResponse {
        authService.requireAuthorization(authorization)
        val options = managedProjects()
            .map { ProjectOptionDto(key = it.key, name = it.name) }
            .sortedBy { it.key }
        return ProjectsResponse(options)
    }

    @GetMapping("/api/v1/repositories")
    fun repositories(@RequestHeader("Authorization", required = false) authorization: String?): RepositoriesResponse {
        authService.requireAuthorization(authorization)
        return RepositoriesResponse(loadRepositories())
    }

    @GetMapping("/api/v1/repositories/{owner}/{repo}")
    fun repositoryDetail(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable owner: String,
        @PathVariable repo: String,
    ): RepositoryDetailResponse {
        authService.requireAuthorization(authorization)
        val slug = "$owner/$repo"
        val project = managedProjects().firstOrNull { GitHubSlug.fromUrl(it.targetRepo) == slug }
            ?: ProjectDto(key = "", name = repo, targetRepo = "https://github.com/$slug")
        val summary = repositorySummary(project)
            ?: throw IllegalArgumentException("Unsupported repository: $slug")
        val stories = storiesForSlug(slug)
        return RepositoryDetailResponse(
            repository = summary,
            workflows = githubClient.workflows(slug),
            runs = githubClient.runs(slug, 30),
            downloads = githubClient.latestReleaseDownloads(slug, project.key.takeIf { it.isNotBlank() }),
            stories = stories,
        )
    }

    @GetMapping("/api/v1/repositories/{owner}/{repo}/workflows")
    fun workflows(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable owner: String,
        @PathVariable repo: String,
    ): WorkflowsResponse {
        authService.requireAuthorization(authorization)
        val slug = "$owner/$repo"
        return WorkflowsResponse(githubClient.workflows(slug), githubClient.runs(slug, 50))
    }

    @GetMapping("/api/v1/repositories/{owner}/{repo}/releases")
    fun releases(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable owner: String,
        @PathVariable repo: String,
    ): ReleasesResponse {
        authService.requireAuthorization(authorization)
        val slug = "$owner/$repo"
        val projectKey = managedProjects().firstOrNull { GitHubSlug.fromUrl(it.targetRepo) == slug }?.key
        return ReleasesResponse(githubClient.latestReleaseDownloads(slug, projectKey))
    }

    @GetMapping("/api/v1/downloads")
    fun downloads(@RequestHeader("Authorization", required = false) authorization: String?): DownloadsResponse {
        authService.requireAuthorization(authorization)
        val repositories = loadRepositories()
        val downloads = repositories.flatMap { repository ->
            repository.githubSlug()?.let { githubClient.latestReleaseDownloads(it, repository.projectKey) }.orEmpty()
        }.sortedWith(compareByDescending<DownloadDto> { it.createdAt ?: "" }.thenBy { it.name })
        return DownloadsResponse(downloads = downloads, repositories = repositories)
    }

    @GetMapping("/api/v1/stories/{storyKey}")
    fun story(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
    ): StoryDetailResponse {
        authService.requireAuthorization(authorization)
        val issue = youTrackClient.getIssue(storyKey)
        val run = repository.latestStoryRun(storyKey)
        return StoryDetailResponse(
            issue = issue,
            run = run,
            agentRuns = run?.let { repository.agentRunsForStory(it.id) } ?: emptyList(),
            events = run?.let { repository.eventsForStory(it.id) } ?: emptyList(),
            screenshots = screenshotDtos(storyKey),
        )
    }

    @GetMapping("/api/v1/stories/{storyKey}/screenshots")
    fun screenshots(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
    ): ScreenshotsResponse {
        authService.requireAuthorization(authorization)
        return ScreenshotsResponse(screenshotDtos(storyKey))
    }

    @GetMapping("/api/v1/stories/{storyKey}/screenshots/{attachmentId}/image")
    fun screenshotImage(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
        @PathVariable attachmentId: String,
    ): ResponseEntity<ByteArray> {
        authService.requireAuthorization(authorization)
        val attachment = youTrackClient.testerScreenshots(storyKey).firstOrNull { it.id == attachmentId }
            ?: return ResponseEntity.notFound().build()
        val url = attachment.url ?: return ResponseEntity.notFound().build()
        val download = youTrackClient.downloadAttachment(url)
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
            .contentType(MediaType.parseMediaType(attachment.mimeType ?: download.contentType))
            .body(download.bytes)
    }

    @PostMapping("/api/v1/stories/{storyKey}/cmd/{command}")
    fun command(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
        @PathVariable command: String,
    ): CommandResponse {
        authService.requireAuthorization(authorization)
        val factoryCommand = FactoryCommand.entries.firstOrNull { it.token == command }
            ?: throw IllegalArgumentException("Unknown command: $command")
        youTrackClient.postCommand(storyKey, factoryCommand)
        return CommandResponse(queued = true)
    }

    @PostMapping("/api/v1/stories/{storyKey}/open-workspace")
    fun openWorkspace(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable storyKey: String,
    ): OpenWorkspaceResponse {
        authService.requireAuthorization(authorization)
        val run = repository.latestStoryRun(storyKey)
            ?: throw IllegalArgumentException("Geen story-run gevonden voor $storyKey")
        val path = openWorkspaceInIntellij(storyKey, run)
        return OpenWorkspaceResponse(opened = true, path = path)
    }

    private fun loadRepositories(): List<ManagedRepositoryDto> =
        managedProjects()
            .mapNotNull { project -> repositorySummary(project) }
            .sortedWith(compareByDescending<ManagedRepositoryDto> { it.blockedStories }.thenBy { it.projectKey })

    private fun managedProjects(): List<ProjectDto> =
        youTrackClient.listManagedProjects()
            .filter { secrets.youTrackProjects.isEmpty() || it.key in secrets.youTrackProjects }
            .filter { !it.targetRepo.isNullOrBlank() }

    private fun repositorySummary(project: ProjectDto): ManagedRepositoryDto? {
        val targetRepo = project.targetRepo?.takeIf { it.isNotBlank() } ?: return null
        val slug = GitHubSlug.fromUrl(targetRepo)
        val repoInfo = slug?.let { githubClient.repository(it) }
        val workflows = slug?.let { githubClient.workflows(it) }.orEmpty()
        val latestRun = slug?.let { githubClient.runs(it, 1).firstOrNull() }
        val downloads = slug?.let { githubClient.latestReleaseDownloads(it, project.key) }.orEmpty()
        val stories = if (slug == null) storiesForProjectKey(project.key) else storiesForSlug(slug)
        val latestApk = downloads
            .filter { it.name.endsWith(".apk", ignoreCase = true) }
            .sortedWith(compareBy<DownloadDto> { timestampedArtifactName(it.name) }.thenBy { it.name })
            .firstOrNull()
        val display = repositoryDisplay(targetRepo, project.key)
        return ManagedRepositoryDto(
            projectKey = project.key,
            projectName = project.name,
            repoUrl = targetRepo,
            owner = slug?.substringBefore('/') ?: "",
            repo = slug?.substringAfter('/') ?: display,
            defaultBranch = repoInfo?.defaultBranch,
            workflowCount = workflows.size,
            latestWorkflow = latestRun?.name,
            latestConclusion = latestRun?.conclusion ?: latestRun?.status,
            latestRunTitle = latestRun?.displayTitle,
            latestRunUrl = latestRun?.url,
            latestRunCreatedAt = latestRun?.createdAt,
            apkAvailable = latestApk != null,
            latestApkName = latestApk?.name,
            latestApkUrl = latestApk?.downloadUrl,
            latestApkSize = latestApk?.size,
            latestReleaseTag = latestApk?.releaseTag,
            activeStories = stories.count { it.status.equals("Develop", ignoreCase = true) },
            blockedStories = stories.count { !it.error.isNullOrBlank() },
        )
    }

    private fun timestampedArtifactName(name: String): Boolean =
        Regex("""\d{8}-\d{6}-[0-9a-fA-F]{7}""").containsMatchIn(name)

    private fun storiesForSlug(slug: String): List<StoryDto> =
        youTrackClient.findWorkIssues(200)
            .filter { GitHubSlug.fromUrl(it.targetRepo) == slug }

    private fun storiesForProjectKey(projectKey: String): List<StoryDto> =
        youTrackClient.findWorkIssues(200)
            .filter { it.key.substringBefore('-', missingDelimiterValue = "") == projectKey }

    private fun repositoryDisplay(repoUrl: String, projectKey: String): String {
        val trimmed = repoUrl.trim().trim('<', '>').removeSuffix(".git").trimEnd('/')
        if (trimmed.isBlank()) return projectKey
        Regex("""/_git/([^/?#]+)$""").find(trimmed)?.let { return it.groupValues[1] }
        Regex("""[:/]([^/:/?#]+)$""").find(trimmed)?.let { return it.groupValues[1] }
        return projectKey
    }

    private fun ManagedRepositoryDto.githubSlug(): String? =
        if (owner.isBlank() || repo.isBlank()) null else "$owner/$repo"

    private fun openWorkspaceInIntellij(storyKey: String, run: StoryRunDto): String {
        val workspaceRoot = run.workspacePath?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it).toAbsolutePath().normalize() }
            ?: throw IllegalArgumentException("Geen workspace-pad gevonden voor $storyKey")
        val repoRoot = workspaceRoot.resolve("repo").toAbsolutePath().normalize()
        require(repoRoot.startsWith(workspaceRoot)) {
            "Ongeldig repo-pad voor $storyKey: $repoRoot"
        }
        require(Files.isDirectory(repoRoot)) {
            "Repo folder bestaat niet voor $storyKey: $repoRoot"
        }
        val process = ProcessBuilder("open", "-a", "IntelliJ IDEA", repoRoot.toString()).start()
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IllegalStateException("IntelliJ openen duurde langer dan 10 seconden")
        }
        if (process.exitValue() != 0) {
            val message = process.errorStream.bufferedReader().readText()
                .ifBlank { process.inputStream.bufferedReader().readText() }
                .ifBlank { "exit code ${process.exitValue()}" }
            throw IllegalStateException("IntelliJ openen faalde: $message")
        }
        return repoRoot.toString()
    }

    private fun screenshotDtos(storyKey: String): List<ScreenshotDto> =
        youTrackClient.testerScreenshots(storyKey).map { attachment ->
            ScreenshotDto(
                id = attachment.id,
                name = attachment.name,
                size = attachment.size,
                createdAt = attachment.created?.let { millis ->
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                        Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC),
                    )
                },
                mimeType = attachment.mimeType,
                imageUrl = "/api/v1/stories/$storyKey/screenshots/${attachment.id}/image",
            )
        }
}
