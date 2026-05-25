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
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

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
            githubClient.latestReleaseDownloads("${repository.owner}/${repository.repo}", repository.projectKey)
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
        )
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

    private fun loadRepositories(): List<ManagedRepositoryDto> =
        managedProjects()
            .mapNotNull { project -> repositorySummary(project) }
            .sortedWith(compareByDescending<ManagedRepositoryDto> { it.blockedStories }.thenBy { it.projectKey })

    private fun managedProjects(): List<ProjectDto> =
        youTrackClient.listManagedProjects()
            .filter { secrets.youTrackProjects.isEmpty() || it.key in secrets.youTrackProjects }
            .filter { !it.targetRepo.isNullOrBlank() }

    private fun repositorySummary(project: ProjectDto): ManagedRepositoryDto? {
        val slug = GitHubSlug.fromUrl(project.targetRepo) ?: return null
        val repoInfo = githubClient.repository(slug)
        val workflows = githubClient.workflows(slug)
        val latestRun = githubClient.runs(slug, 1).firstOrNull()
        val downloads = githubClient.latestReleaseDownloads(slug, project.key)
        val stories = storiesForSlug(slug)
        val latestApk = downloads
            .filter { it.name.endsWith(".apk", ignoreCase = true) }
            .sortedWith(compareBy<DownloadDto> { timestampedArtifactName(it.name) }.thenBy { it.name })
            .firstOrNull()
        return ManagedRepositoryDto(
            projectKey = project.key,
            projectName = project.name,
            repoUrl = project.targetRepo.orEmpty(),
            owner = slug.substringBefore('/'),
            repo = slug.substringAfter('/'),
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
}
