package nl.vdzon.softwarefactory.dashboard.services

import nl.vdzon.softwarefactory.audit.repositories.AuditProjectSettingsRepository
import nl.vdzon.softwarefactory.audit.repositories.AuditSettingsRepository
import nl.vdzon.softwarefactory.audit.services.AuditScheduler
import nl.vdzon.softwarefactory.audit.types.ManualAuditResult
import nl.vdzon.softwarefactory.config.DeployConfig
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectDashboardSettings
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AiRouting
import nl.vdzon.softwarefactory.core.contracts.ApprovalMode
import nl.vdzon.softwarefactory.core.contracts.NotifyMode
import nl.vdzon.softwarefactory.core.contracts.StoryPhase
import nl.vdzon.softwarefactory.core.contracts.StoryRunRepository
import nl.vdzon.softwarefactory.core.contracts.SubtaskPhase
import nl.vdzon.softwarefactory.config.time.FactoryTime
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.dashboard.models.AuditProjectSettingsSaveInput
import nl.vdzon.softwarefactory.dashboard.models.AuditRunNowResult
import nl.vdzon.softwarefactory.dashboard.models.CreateStoryCommand
import nl.vdzon.softwarefactory.dashboard.DashboardCommands
import nl.vdzon.softwarefactory.dashboard.repositories.FactoryDashboardRepository
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import nl.vdzon.softwarefactory.knowledge.models.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.tracker.TrackerCapabilities
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.OffsetDateTime

/** Muterende dashboard-use-cases; query-assembly blijft buiten deze service. */
@Service
class DashboardCommandService(
    private val tracker: TrackerCapabilities,
    private val secrets: FactorySecrets,
    private val projects: ProjectDashboardSettings,
    private val orchestrator: OrchestratorApi,
    private val deployClient: ProjectDeployClient,
    private val repository: FactoryDashboardRepository,
    private val workspaceLauncher: WorkspaceDesktopLauncher,
    private val storyRunRepository: StoryRunRepository,
    private val knowledgeApi: KnowledgeApi,
    private val clock: Clock,
    private val auditScheduler: AuditScheduler,
    private val auditProjectSettingsRepository: AuditProjectSettingsRepository,
    private val auditSettingsRepository: AuditSettingsRepository,
) : DashboardCommands {
    override fun updateAuditMemoryNote(project: String, auditType: String, key: String, content: String) {
        val repo = projects.repoFor(project) ?: error("Onbekend project: $project")
        knowledgeApi.upsert(
            AgentKnowledgeUpdateRequest(
                targetRepo = repo,
                role = AgentRole.AUDITOR.markerKeyPart,
                category = auditType,
                key = key,
                content = content,
            ),
        )
    }

    override fun deleteAuditMemoryNote(project: String, auditType: String, key: String) {
        val repo = projects.repoFor(project) ?: error("Onbekend project: $project")
        knowledgeApi.delete(repo, AgentRole.AUDITOR.markerKeyPart, auditType, key)
    }

    override fun saveAuditSettings(enabled: Boolean, projects: List<AuditProjectSettingsSaveInput>) {
        projects.forEach { require(it.auditCount >= 0) { "Aantal audits mag niet negatief zijn." } }
        auditSettingsRepository.save(auditSettingsRepository.read().copy(enabled = enabled))
        projects.forEach { auditProjectSettingsRepository.save(it.project, FactoryTime.parseHhMm(it.startTime), it.auditCount) }
    }

    override fun runAuditNow(project: String, auditType: String): AuditRunNowResult =
        auditScheduler.startManualAudit(project, auditType)
            .let { AuditRunNowResult(accepted = it.accepted, status = it.name.lowercase()) }
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
            startPhase = if (command.start) StoryPhase.START else null,
            questionsAllowed = command.questionsAllowed,
        )
        if (command.approvalMode != ApprovalMode.AUTOMATIC.trackerValue) setApprovalMode(story.key, command.approvalMode)
        if (command.notifyMode != NotifyMode.WHEN_DONE.trackerValue) setNotifyMode(story.key, command.notifyMode)
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

    override fun purgeStory(storyKey: String) = orchestrator.purgeStory(storyKey)

    override fun startRefining(storyKey: String) {
        closeDanglingRun(storyKey)
        tracker.updateIssueFields(storyKey, TrackerFieldUpdate.of(TrackerField.STORY_PHASE to StoryPhase.START.trackerValue))
    }

    /**
     * "Queue story": wacht tot de target-repo vrij is (zie OrchestratorService — per-repo promotor).
     * `startRefining` blijft de override om de wachtrij te negeren en meteen te starten.
     */
    override fun queueStory(storyKey: String) {
        closeDanglingRun(storyKey)
        tracker.updateIssueFields(storyKey, TrackerFieldUpdate.of(TrackerField.STORY_PHASE to StoryPhase.START_NEXT.trackerValue))
    }

    /**
     * Deze twee acties zijn alleen zichtbaar/bruikbaar als de story-fase leeg is of `start-next` —
     * dus als er toch nog een open `story_runs`-rij voor deze story hangt, is dat een verweesde run
     * van een eerdere, handmatig teruggezette poging. Zonder deze sluiting zou zo'n rij de
     * per-repo-wachtrij voor altijd blokkeren (activeRunForRepo blijft 'm zien als bezig).
     */
    private fun closeDanglingRun(storyKey: String) {
        storyRunRepository.activeRuns()
            .filter { it.storyKey == storyKey }
            .forEach { storyRunRepository.close(it.id, "requeued", OffsetDateTime.now(clock)) }
    }

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
