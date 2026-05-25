package nl.vdzon.softwarefactory.dashboard.api

import nl.vdzon.softwarefactory.dashboard.config.DashboardSecrets
import nl.vdzon.softwarefactory.dashboard.database.DashboardRepository
import nl.vdzon.softwarefactory.dashboard.youtrack.FactoryCommand
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
}
