package nl.vdzon.softwarefactory.web.services

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.nightly.NightlyChangeRef
import nl.vdzon.softwarefactory.nightly.NightlyGateway
import nl.vdzon.softwarefactory.nightly.NightlyJob
import nl.vdzon.softwarefactory.nightly.NightlyJobChanges
import nl.vdzon.softwarefactory.nightly.NightlyOutcomeStatus
import nl.vdzon.softwarefactory.nightly.NightlyStoryOutcome
import nl.vdzon.softwarefactory.telegram.TelegramClient
import nl.vdzon.softwarefactory.web.repositories.FactoryDashboardRepository
import nl.vdzon.softwarefactory.tracker.TrackerApi
import org.springframework.stereotype.Component

/**
 * Adapter die de [NightlyGateway]-poort van de scheduler-module invult met de bestaande factory-bouwstenen
 * (dashboard-service, tracker, story-run-repository, Telegram). Houdt de `nightly`-module los van `web`,
 * `tracker` en `telegram`.
 */
@Component
class NightlyGatewayAdapter(
    private val dashboardService: FactoryDashboardService,
    private val issueTrackerClient: TrackerApi,
    private val repository: FactoryDashboardRepository,
    private val telegramClient: TelegramClient,
    private val secrets: FactorySecrets,
    private val changeSummarizer: NightlyChangeSummarizer,
    private val projectRepoResolver: ProjectRepoResolver,
) : NightlyGateway {

    override fun allJobs(): List<NightlyJob> = dashboardService.nightlyJobs().jobs

    override fun startStory(project: String, jobName: String): String =
        dashboardService.createNightlyStory(project, jobName).key

    override fun storyOutcome(storyKey: String): NightlyStoryOutcome {
        val story = runCatching { issueTrackerClient.getIssue(storyKey) }.getOrNull()
        val subtasks = runCatching { issueTrackerClient.subtasksOf(storyKey) }.getOrNull() ?: emptyList()
        val run = runCatching { repository.latestStoryRun(storyKey) }.getOrNull()

        // Mislukt zodra het error-veld van de story (of een van haar subtaken) is gezet: een errored
        // subtaak wordt niet 'terminal' qua fase, dus zonder deze check zou de queue blijven hangen.
        val storyError = story?.fields?.error?.takeIf { it.isNotBlank() }
        val subtaskError = subtasks.firstNotNullOfOrNull { it.fields.error?.takeIf { e -> e.isNotBlank() } }
        val error = storyError ?: subtaskError

        val allDone = subtasks.isNotEmpty() &&
            subtasks.all { SubtaskPhase.fromTracker(it.fields.subtaskPhase)?.isTerminal == true }

        val status = when {
            error != null -> NightlyOutcomeStatus.FAILED
            allDone -> NightlyOutcomeStatus.DONE
            else -> NightlyOutcomeStatus.RUNNING
        }
        return NightlyStoryOutcome(
            status = status,
            startedAt = run?.startedAt,
            endedAt = run?.endedAt,
            costUsd = run?.totalCostUsdEst ?: 0.0,
            error = error?.take(300),
        )
    }

    override fun storyLink(storyKey: String): String =
        secrets.dashboardBaseUrl?.takeIf { it.isNotBlank() }?.trimEnd('/')?.let { "$it/stories/$storyKey" }.orEmpty()

    override fun describeChanges(stories: List<NightlyChangeRef>): Map<String, NightlyJobChanges> =
        changeSummarizer.describe(stories)

    override fun sendDigest(project: String?, text: String): Boolean {
        val chatId = project?.let { projectRepoResolver.telegramChatIdFor(it) }
        return telegramClient.sendMessage(text, chatId = chatId) != null
    }
}
