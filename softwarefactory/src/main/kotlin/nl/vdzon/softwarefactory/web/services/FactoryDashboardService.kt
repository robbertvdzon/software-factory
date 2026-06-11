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
import nl.vdzon.softwarefactory.web.models.UiAgentRun
import nl.vdzon.softwarefactory.web.models.UiStoryRun
import nl.vdzon.softwarefactory.web.repositories.FactoryDashboardRepository
import nl.vdzon.softwarefactory.orchestrator.StoryPhase
import nl.vdzon.softwarefactory.orchestrator.SubtaskPhase
import nl.vdzon.softwarefactory.youtrack.FactoryCommand
import nl.vdzon.softwarefactory.youtrack.IssueType
import nl.vdzon.softwarefactory.youtrack.TrackerField
import nl.vdzon.softwarefactory.youtrack.TrackerFieldUpdate
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.youtrack.TrackerIssue
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Service
class FactoryDashboardService(
    private val issueTrackerClient: YouTrackApi,
    private val orchestratorApi: OrchestratorApi,
    private val repository: FactoryDashboardRepository,
    private val factorySecrets: FactorySecrets,
    private val previewApi: PreviewApi,
) {
    private val developmentTag = "ai-development"

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
        val isSubtask = issue?.issueType == IssueType.SUBTASK
        // Subtaken delen de story-run van de parent; runs zijn gemerkt met subtask_key.
        val parentKey = if (isSubtask) load(errors) { issueTrackerClient.parentStoryKey(storyKey) } else null
        val runKey = parentKey ?: storyKey
        val run = load(errors) { repository.latestStoryRun(runKey) }
        val allRuns = run?.let { load(errors) { repository.agentRunsForStory(it.id) } } ?: emptyList()
        val agentRuns = if (isSubtask) {
            allRuns.filter { it.subtaskKey == storyKey }
        } else {
            allRuns.filter { it.subtaskKey == null }
        }
        val events = run?.let { load(errors) { repository.eventsForStory(it.id) } } ?: emptyList()
        val subtasks = if (issue != null && !isSubtask) {
            load(errors, emptyList()) { issueTrackerClient.subtasksOf(storyKey) }
        } else {
            emptyList()
        }
        // Laatste agent-bericht per issue-key (story = runKey, subtask = subtaskKey): de gestelde vraag.
        val agentQuestions = latestAgentQuestions(allRuns, runKey)
        return StoryDetailPageData(
            issue = issue,
            storyKey = storyKey,
            run = run,
            agentRuns = agentRuns,
            events = events,
            youTrackUrl = "${factorySecrets.youTrackBaseUrl.trimEnd('/')}/issue/$storyKey",
            previewUrl = run?.previewUrl(),
            errors = errors,
            subtasks = subtasks,
            parentKey = parentKey,
            agentQuestions = agentQuestions,
        )
    }

    companion object {
        /**
         * Per issue-key (story = [fallbackKey], subtask = subtask_key) de gestelde vraag: de meest
         * recente run MET een niet-lege samenvatting. Bewust niet "de laatste run" — een latere lege
         * of half-afgeronde run (bv. uit recovery-churn) mag de eerder gestelde vraag niet verbergen.
         */
        internal fun latestAgentQuestions(runs: List<UiAgentRun>, fallbackKey: String): Map<String, String> =
            runs.groupBy { it.subtaskKey ?: fallbackKey }
                .mapNotNull { (key, group) ->
                    group.sortedByDescending { it.startedAt }
                        .firstNotNullOfOrNull { it.summaryText?.takeIf { s -> s.isNotBlank() } }
                        ?.let { key to it }
                }
                .toMap()
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

    /** Hard opruimen van een hele story (issue + subtaken + branch + workfolder + run). Onomkeerbaar. */
    fun purgeStory(storyKey: String) {
        orchestratorApi.purgeStory(storyKey)
    }

    /**
     * Mens-actie vanuit de UI: zet de `Story Phase` (goedkeuren/afkeuren/antwoorden)
     * en post een optionele reden/antwoord als comment. Valideert tegen StoryPhase.
     */
    fun setStoryPhase(storyKey: String, phase: String, comment: String?) {
        val target = StoryPhase.fromTracker(phase) ?: error("Onbekende Story Phase: $phase")
        comment?.takeIf { it.isNotBlank() }?.let { issueTrackerClient.postComment(storyKey, it) }
        issueTrackerClient.updateIssueFields(
            storyKey,
            TrackerFieldUpdate.of(TrackerField.STORY_PHASE to target.trackerValue),
        )
    }

    /** Start development: zet de tag `ai-development` op de eerste niet-afgeronde subtask. */
    fun startDeveloping(storyKey: String) {
        val subtasks = issueTrackerClient.subtasksOf(storyKey)
        if (subtasks.any { developmentTag in it.tags }) {
            return
        }
        val first = subtasks.firstOrNull { SubtaskPhase.fromTracker(it.fields.subtaskPhase)?.isTerminal != true }
            ?: error("Geen open subtask gevonden om te starten.")
        issueTrackerClient.addTag(first.key, developmentTag)
    }

    /** Mens-actie op een subtask: zet de `Subtask Phase` + optionele reden/antwoord als comment. */
    fun setSubtaskPhase(subtaskKey: String, phase: String, comment: String?) {
        val target = SubtaskPhase.fromTracker(phase) ?: error("Onbekende Subtask Phase: $phase")
        comment?.takeIf { it.isNotBlank() }?.let { issueTrackerClient.postComment(subtaskKey, it) }
        issueTrackerClient.updateIssueFields(
            subtaskKey,
            TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to target.trackerValue),
        )
    }

    fun openWorkspaceInIntellij(storyKey: String): String {
        val run = repository.latestStoryRun(storyKey)
            ?: error("Geen story-run gevonden voor $storyKey")
        val workspaceRoot = run.workspacePath?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it).toAbsolutePath().normalize() }
            ?: error("Geen workspace-pad gevonden voor $storyKey")
        val repoRoot = workspaceRoot.resolve("repo").toAbsolutePath().normalize()
        require(repoRoot.startsWith(workspaceRoot)) {
            "Ongeldig repo-pad voor $storyKey: $repoRoot"
        }
        require(Files.isDirectory(repoRoot)) {
            "Repo folder bestaat niet voor $storyKey: $repoRoot"
        }
        openIntellij(repoRoot)
        return repoRoot.toString()
    }

    private fun openIntellij(repoRoot: Path) {
        val process = ProcessBuilder("open", "-a", "IntelliJ IDEA", repoRoot.toString()).start()
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("IntelliJ openen duurde langer dan 10 seconden")
        }
        check(process.exitValue() == 0) {
            val output = process.errorStream.bufferedReader().readText()
                .ifBlank { process.inputStream.bufferedReader().readText() }
                .ifBlank { "exit code ${process.exitValue()}" }
            "IntelliJ openen faalde: $output"
        }
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
