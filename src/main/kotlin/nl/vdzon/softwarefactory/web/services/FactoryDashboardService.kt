package nl.vdzon.softwarefactory.web.services

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.web.models.AgentsPageData
import nl.vdzon.softwarefactory.web.models.DashboardPageData
import nl.vdzon.softwarefactory.web.models.MergedPageData
import nl.vdzon.softwarefactory.web.models.SettingsPageData
import nl.vdzon.softwarefactory.web.models.StoriesPageData
import nl.vdzon.softwarefactory.web.models.StoryDetailPageData
import nl.vdzon.softwarefactory.web.models.UiStoryRun
import nl.vdzon.softwarefactory.web.repositories.FactoryDashboardRepository
import nl.vdzon.softwarefactory.youtrack.parsers.FactoryCommand
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.youtrack.TrackerIssue
import org.springframework.stereotype.Service

@Service
class FactoryDashboardService(
    private val issueTrackerClient: YouTrackApi,
    private val orchestratorApi: OrchestratorApi,
    private val repository: FactoryDashboardRepository,
    private val factorySecrets: FactorySecrets,
    private val previewApi: PreviewApi,
) {
    fun dashboard(): DashboardPageData {
        val errors = mutableListOf<String>()
        val issues = loadWorkIssues(errors, limit = 20)
        val activeRuns = load(errors, emptyList()) { repository.activeStoryRuns(limit = 20) }
        val recentRuns = load(errors, emptyList()) { repository.recentStoryRuns(limit = 10) }
        val activeAgents = load(errors, emptyList()) { repository.activeAgentRuns(limit = 10) }
        return DashboardPageData(issues, activeRuns, recentRuns, activeAgents, errors)
    }

    fun stories(): StoriesPageData {
        val errors = mutableListOf<String>()
        val issues = loadWorkIssues(errors, limit = 100)
        val runsByStory = load(errors, emptyMap()) { repository.activeStoryRuns(limit = 200).associateBy { it.storyKey } }
        return StoriesPageData(issues, runsByStory, errors)
    }

    fun storyDetail(storyKey: String): StoryDetailPageData {
        val errors = mutableListOf<String>()
        val issue = load(errors) { issueTrackerClient.getIssue(storyKey) }
        val run = load(errors) { repository.latestStoryRun(storyKey) }
        val agentRuns = run?.let { load(errors) { repository.agentRunsForStory(it.id) } } ?: emptyList()
        val events = run?.let { load(errors) { repository.eventsForStory(it.id) } } ?: emptyList()
        return StoryDetailPageData(
            issue = issue,
            storyKey = storyKey,
            run = run,
            agentRuns = agentRuns,
            events = events,
            youTrackUrl = "${factorySecrets.youTrackBaseUrl.trimEnd('/')}/issue/$storyKey",
            previewUrl = run?.previewUrl(),
            errors = errors,
        )
    }

    fun screenshots(storyKey: String): StoryDetailPageData {
        val page = storyDetail(storyKey)
        val screenshotEvents = page.run?.let { run ->
            runCatching { repository.screenshotEventsForStory(run.id) }.getOrElse {
                return page.copy(errors = page.errors + errorMessage(it))
            }
        } ?: emptyList()
        return page.copy(events = screenshotEvents)
    }

    fun agents(): AgentsPageData {
        val errors = mutableListOf<String>()
        return AgentsPageData(
            activeAgentRuns = load(errors, emptyList()) { repository.activeAgentRuns(limit = 50) },
            recentAgentRuns = load(errors, emptyList()) { repository.recentAgentRuns(limit = 50) },
            errors = errors,
        )
    }

    fun merged(): MergedPageData {
        val errors = mutableListOf<String>()
        return MergedPageData(
            mergedRuns = load(errors, emptyList()) { repository.mergedStoryRuns(limit = 50) },
            errors = errors,
        )
    }

    fun settings(username: String): SettingsPageData =
        SettingsPageData(
            username = username,
            configuration = factorySecrets.redactedSummary(),
        )

    fun queueCommand(storyKey: String, command: FactoryCommand) {
        orchestratorApi.queueCommand(storyKey, command)
    }

    private fun <T> load(errors: MutableList<String>, loader: () -> T): T? =
        runCatching(loader).getOrElse {
            errors += errorMessage(it)
            null
        }

    private fun <T> load(errors: MutableList<String>, default: T, loader: () -> T): T =
        runCatching(loader).getOrElse {
            errors += errorMessage(it)
            default
        }

    private fun UiStoryRun.previewUrl(): String? =
        previewApi.render(previewUrlTemplate, prNumber)

    private fun errorMessage(exception: Throwable): String =
        exception.message?.takeIf { it.isNotBlank() } ?: exception::class.simpleName ?: "Onbekende fout"

    private fun loadWorkIssues(errors: MutableList<String>, limit: Int): List<TrackerIssue> =
        load(errors, emptyList()) { issueTrackerClient.findWorkIssues(maxResults = limit) }
}
