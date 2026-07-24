package nl.vdzon.softwarefactory.dashboard.services

import nl.vdzon.softwarefactory.config.DeployConfig
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectDashboardSettings
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AiRouting
import nl.vdzon.softwarefactory.core.contracts.ApprovalMode
import nl.vdzon.softwarefactory.core.contracts.NotifyMode
import nl.vdzon.softwarefactory.core.contracts.StoryPhase
import nl.vdzon.softwarefactory.core.contracts.SubtaskPhase
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.dashboard.models.CreateStoryCommand
import nl.vdzon.softwarefactory.dashboard.DashboardCommands
import nl.vdzon.softwarefactory.dashboard.repositories.FactoryDashboardRepository
import nl.vdzon.softwarefactory.nightly.services.NightlyJobsReader
import nl.vdzon.softwarefactory.nightly.repositories.NightlySettings
import nl.vdzon.softwarefactory.nightly.repositories.NightlySettingsRepository
import nl.vdzon.softwarefactory.nightly.services.NightlyTime
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.runtime.SubtaskMaterializationApi
import nl.vdzon.softwarefactory.tracker.TrackerCapabilities
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

/** Muterende dashboard-use-cases; query-assembly blijft buiten deze service. */
@Service
class DashboardCommandService(
    private val tracker: TrackerCapabilities,
    private val secrets: FactorySecrets,
    private val projects: ProjectDashboardSettings,
    private val nightlyJobs: NightlyJobsReader,
    private val materializer: SubtaskMaterializationApi,
    private val nightlySettings: NightlySettingsRepository,
    private val orchestrator: OrchestratorApi,
    private val deployClient: ProjectDeployClient,
    private val repository: FactoryDashboardRepository,
    private val workspaceLauncher: WorkspaceDesktopLauncher,
) : DashboardCommands {
    override fun createStory(command: CreateStoryCommand): TrackerIssue {
        require(command.title.isNotBlank()) { "Titel is verplicht." }
        val supplier = command.aiSupplier?.takeIf(String::isNotBlank)
        val model = command.aiModel?.takeIf(String::isNotBlank)
            ?: AiRouting.resolve(null, supplier, AgentRole.DEVELOPER).model
        val story = tracker.createStory(
            projectKey = projectKey(command.projectKey),
            title = command.title,
            description = command.description?.takeIf(String::isNotBlank),
            repo = command.repo?.takeIf(String::isNotBlank),
            aiSupplier = supplier,
            aiModel = model,
            start = command.start,
            questionsAllowed = command.questionsAllowed,
        )
        if (command.approvalMode != ApprovalMode.AUTOMATIC.trackerValue) setApprovalMode(story.key, command.approvalMode)
        if (command.notifyMode != NotifyMode.WHEN_DONE.trackerValue) setNotifyMode(story.key, command.notifyMode)
        return story
    }

    override fun createNightlyStory(project: String, jobName: String): TrackerIssue {
        val repo = projects.repoFor(project) ?: error("Onbekend project: $project")
        val detail = nightlyJobs.readJob(repo, project, jobName)
            ?: error("Nachtelijke job niet gevonden: $project/$jobName")
        val specs = detail.subtasks
        val story = createStory(CreateStoryCommand(
            projectKey = null, title = detail.job.title, description = detail.story, repo = project,
            aiSupplier = detail.job.aiSupplier, aiModel = detail.job.aiModel,
            start = specs.isNullOrEmpty(), questionsAllowed = false,
            approvalMode = ApprovalMode.AUTOMATIC.trackerValue, notifyMode = NotifyMode.NONE.trackerValue,
        ))
        if (!specs.isNullOrEmpty()) {
            materializer.materializeFromSpecs(story.key, specs)
            tracker.updateIssueFields(story.key, TrackerFieldUpdate.of(
                TrackerField.STORY_PHASE to StoryPhase.PLANNING_APPROVED.trackerValue,
            ))
        }
        return story
    }

    override fun setQuestionsAllowedFlag(storyKey: String, enabled: Boolean) = tracker.updateIssueFields(
        storyKey, TrackerFieldUpdate.of(TrackerField.QUESTIONS_ALLOWED to if (enabled) "on" else "off"),
    )

    override fun setApprovalMode(storyKey: String, mode: String) = tracker.updateIssueFields(
        storyKey, TrackerFieldUpdate.of(TrackerField.APPROVAL_MODE to ApprovalMode.fromTracker(mode).trackerValue),
    )

    override fun setNotifyMode(storyKey: String, mode: String) = tracker.updateIssueFields(
        storyKey, TrackerFieldUpdate.of(TrackerField.NOTIFY_MODE to NotifyMode.fromTracker(mode).trackerValue),
    )

    /** Partial update — alleen de meegegeven (niet-null) velden worden gewijzigd, zie de bridge-operatie `story.edit`. */
    override fun editStory(storyKey: String, description: String?, aiSupplier: String?, aiModel: String?) {
        description?.let { tracker.updateIssueDescription(storyKey, it) }
        aiSupplier?.let { tracker.updateIssueFields(storyKey, TrackerFieldUpdate.of(TrackerField.AI_SUPPLIER to it)) }
        aiModel?.let { tracker.updateIssueFields(storyKey, TrackerFieldUpdate.of(TrackerField.AI_MODEL to it)) }
    }

    override fun forceProjectDeploy(projectName: String) {
        val config = projects.deployConfigFor(projectName)
        require(config is DeployConfig.RestRestart) { "Geen RestRestart deploy-config voor project $projectName" }
        deployClient.forceRestart(config)
    }

    override fun saveNightlySettings(enabled: Boolean, startTime: String, summaryTime: String) {
        val value = runCatching { NightlySettings(enabled, NightlyTime.parseHhMm(startTime), NightlyTime.parseHhMm(summaryTime)) }
            .getOrElse { throw IllegalArgumentException("Ongeldige tijd (verwacht HH:MM): ${it.message}") }
        nightlySettings.save(value)
    }

    override fun purgeStory(storyKey: String) = orchestrator.purgeStory(storyKey)

    override fun startRefining(storyKey: String) = tracker.updateIssueFields(
        storyKey, TrackerFieldUpdate.of(TrackerField.STORY_PHASE to StoryPhase.START.trackerValue),
    )

    override fun startDeveloping(storyKey: String) {
        val subtasks = tracker.subtasksOf(storyKey)
        if (subtasks.any { !it.fields.subtaskPhase.isNullOrBlank() }) return
        val first = subtasks.firstOrNull { SubtaskPhase.fromTracker(it.fields.subtaskPhase)?.isTerminal != true }
            ?: error("Geen open subtask gevonden om te starten.")
        tracker.updateIssueFields(first.key, TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.START.trackerValue))
        tracker.updateIssueFields(storyKey, TrackerFieldUpdate.of(TrackerField.STORY_PHASE to StoryPhase.IN_PROGRESS.trackerValue))
    }

    override fun openWorkspaceInIntellij(storyKey: String): String {
        val root = repository.latestStoryRun(storyKey)?.workspacePath?.takeIf(String::isNotBlank)
            ?.let { Path.of(it).toAbsolutePath().normalize() }
            ?: error("Geen workspace-pad gevonden voor $storyKey")
        val repo = root.resolve("repo").normalize()
        require(repo.startsWith(root) && Files.isDirectory(repo)) { "Repo folder bestaat niet voor $storyKey: $repo" }
        workspaceLauncher.openInIntellij(repo)
        return repo.toString()
    }

    private fun projectKey(explicit: String?): String = explicit?.takeIf(String::isNotBlank)
        ?: runCatching { tracker.ensureConfiguredProjects().firstOrNull()?.key }.getOrNull()
        ?: secrets.trackerProjects.firstOrNull()
        ?: error("Geen project geconfigureerd; stel SF_TRACKER_PROJECTS in of maak eerst een story aan.")
}
