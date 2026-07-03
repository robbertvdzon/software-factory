package nl.vdzon.softwarefactory.web.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.DeployConfig
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.core.IssueType
import nl.vdzon.softwarefactory.core.StoryPhase
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.nightly.NightlyJobsReader
import nl.vdzon.softwarefactory.nightly.NightlyRunJobRepository
import nl.vdzon.softwarefactory.nightly.NightlyRunRepository
import nl.vdzon.softwarefactory.nightly.NightlySettings
import nl.vdzon.softwarefactory.nightly.NightlySettingsRepository
import nl.vdzon.softwarefactory.nightly.NightlyTime
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.web.models.AgentsPageData
import nl.vdzon.softwarefactory.web.models.DashboardPageData
import nl.vdzon.softwarefactory.web.models.MergedPageData
import nl.vdzon.softwarefactory.web.models.MyActionItem
import nl.vdzon.softwarefactory.web.models.MyActionsPageData
import nl.vdzon.softwarefactory.web.models.MyActionsStoryGroup
import nl.vdzon.softwarefactory.web.models.NightlyJobsPageData
import nl.vdzon.softwarefactory.web.models.NightlyRunJobView
import nl.vdzon.softwarefactory.web.models.NightlyRunProjectView
import nl.vdzon.softwarefactory.web.models.NightlyRunView
import nl.vdzon.softwarefactory.web.models.PrdVersionInfo
import nl.vdzon.softwarefactory.web.models.ProjectOverviewItem
import nl.vdzon.softwarefactory.web.models.ProjectsPageData
import nl.vdzon.softwarefactory.web.models.SettingsPageData
import nl.vdzon.softwarefactory.web.models.StoriesPageData
import nl.vdzon.softwarefactory.web.models.StoryDetailPageData
import nl.vdzon.softwarefactory.web.models.UiAgentRun
import nl.vdzon.softwarefactory.web.repositories.FactoryDashboardRepository
import nl.vdzon.softwarefactory.web.views.FactoryDashboardViews
import nl.vdzon.softwarefactory.web.views.shared.StoryStatusPresenter
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

/**
 * Page-data-assembler voor de dashboard-views. De [nl.vdzon.softwarefactory.core.FactoryOperations]-
 * poort-implementatie (voor o.a. `telegram`) leeft apart in [FactoryOperationsService]; het
 * host-specifieke IntelliJ-openen in [WorkspaceDesktopLauncher] en de deploy-REST-calls in
 * [ProjectDeployClient]. Deze service assembleert alleen nog paginadata en dashboard-acties.
 */
@Service
class FactoryDashboardService(
    private val issueTrackerClient: YouTrackApi,
    private val orchestratorApi: OrchestratorApi,
    private val repository: FactoryDashboardRepository,
    private val factorySecrets: FactorySecrets,
    private val operations: FactoryOperationsService,
    private val projectRepoResolver: ProjectRepoResolver,
    private val versionService: FactoryVersionService,
    private val nightlySettingsRepository: NightlySettingsRepository,
    private val nightlyRunRepository: NightlyRunRepository,
    private val nightlyRunJobRepository: NightlyRunJobRepository,
    // Verplicht: dit zijn Spring-beans (@Component/@Service); de vroegere defaults construeerden
    // stil een tweede instantie buiten de context om (o.a. zonder FactorySecrets), waardoor een
    // vergeten bean onopgemerkt verkeerd geconfigureerd zou zijn.
    private val nightlyJobsReader: NightlyJobsReader,
    private val deployClient: ProjectDeployClient,
    private val workspaceLauncher: WorkspaceDesktopLauncher,
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
        val mergedStoryKeys = load(errors, emptySet()) { repository.mergedStoryKeys() }
        // Keuzelijsten voor het "Nieuwe story"-formulier.
        val projects = load(errors, emptyList()) { issueTrackerClient.ensureConfiguredProjects() }
        return StoriesPageData(
            issues,
            runsByStory,
            errors,
            mergedStoryKeys = mergedStoryKeys,
            projects = projects,
            repoNames = projectRepoResolver.projectNames(),
        )
    }

    // Korte cache op het badge-getal: het wordt door elke open tab bij elk SSE-event opgevraagd
    // en de onderliggende findWorkIssues is zwaar. Met deze TTL rekent de server het hooguit eens
    // per paar seconden uit, ongeacht het aantal tabs/requests.
    @Volatile
    private var myActionsCountCache: Pair<Long, Int>? = null

    /** Aantal (sub)taken dat op een mens-actie wacht — voor het badge-bolletje in de nav (gecached). */
    fun myActionsCount(): Int {
        val now = System.currentTimeMillis()
        myActionsCountCache?.let { (at, value) -> if (now - at < MY_ACTIONS_COUNT_TTL_MS) return value }
        val value = runCatching { issueTrackerClient.findWorkIssues(maxResults = 200).count { awaitsHuman(it) } }
            .getOrDefault(0)
        myActionsCountCache = now to value
        return value
    }

    /** "My actions"-inbox: alle wachtende (sub)taken over alle stories, gegroepeerd per story. */
    fun myActions(): MyActionsPageData {
        val errors = mutableListOf<String>()
        val allIssues = loadWorkIssues(errors, limit = 200)
        val byKey = allIssues.associateBy { it.key }
        val awaiting = allIssues.filter { awaitsHuman(it) }

        // Groepeer op owner-story: een story is z'n eigen owner; een subtaak hoort bij z'n parent.
        val byStory = LinkedHashMap<String, MutableList<TrackerIssue>>()
        awaiting.forEach { issue ->
            val ownerKey = if (issue.issueType == IssueType.SUBTASK) {
                load(errors) { issueTrackerClient.parentStoryKey(issue.key) } ?: issue.key
            } else {
                issue.key
            }
            byStory.getOrPut(ownerKey) { mutableListOf() } += issue
        }

        val groups = byStory.map { (ownerKey, issues) ->
            val ownerSummary = byKey[ownerKey]?.summary
                ?: load(errors) { issueTrackerClient.getIssue(ownerKey) }?.summary
                ?: ownerKey
            val run = load(errors) { repository.latestStoryRun(ownerKey) }
            val runs = run?.let { load(errors, emptyList()) { repository.agentRunsForStory(it.id) } } ?: emptyList()
            val questions = latestAgentQuestions(runs, ownerKey)
            MyActionsStoryGroup(
                storyKey = ownerKey,
                storySummary = ownerSummary,
                prUrl = run?.prUrl,
                runs = runs,
                items = issues.map { iss ->
                    MyActionItem(
                        issue = iss,
                        isSubtask = iss.issueType == IssueType.SUBTASK,
                        question = questions[iss.key],
                    )
                },
            )
        }.sortedBy { it.storyKey }

        return MyActionsPageData(groups, errors)
    }

    /** Wacht deze (sub)taak op een mens? Beslissing leeft in [FactoryOperationsService.awaitsHuman]. */
    internal fun awaitsHuman(issue: TrackerIssue): Boolean =
        operations.awaitsHuman(issue)

    /** Maakt een nieuwe story aan vanuit het dashboard. */
    fun createStory(
        projectKey: String,
        title: String,
        description: String?,
        repo: String?,
        aiSupplier: String?,
        aiModel: String?,
        start: Boolean,
        autoApprove: Boolean = false,
        silent: Boolean = false,
    ): TrackerIssue {
        require(projectKey.isNotBlank()) { "Project is verplicht." }
        require(title.isNotBlank()) { "Titel is verplicht." }
        val created = issueTrackerClient.createStory(
            projectKey = projectKey,
            title = title,
            description = description?.takeIf { it.isNotBlank() },
            repo = repo?.takeIf { it.isNotBlank() },
            aiSupplier = aiSupplier?.takeIf { it.isNotBlank() },
            aiModel = aiModel?.takeIf { it.isNotBlank() },
            start = start,
            silent = silent,
        )
        if (autoApprove) {
            setAutoApproveFlag(created.key, true)
        }
        return created
    }

    /** Overzicht van alle nachtelijke jobs van alle projecten (gelezen uit `.factory/nightly/`). */
    fun nightlyJobs(runNotice: String? = null): NightlyJobsPageData {
        val projects = projectRepoResolver.projectNames().mapNotNull { name ->
            projectRepoResolver.repoFor(name)?.let { name to it }
        }
        val result = nightlyJobsReader.readAll(projects)
        return NightlyJobsPageData(result.jobs, result.errors, run = latestNightlyRunView(), runNotice = runNotice)
    }

    /** Bouwt de statusweergave van de huidige/laatste automatische run, per project gescheiden. */
    private fun latestNightlyRunView(): NightlyRunView? {
        val run = runCatching { nightlyRunRepository.latestRun() }.getOrNull() ?: return null
        val jobs = runCatching { nightlyRunJobRepository.forRun(run.id) }.getOrDefault(emptyList())
        val projects = jobs.groupBy { it.project }.entries
            .sortedBy { it.key.lowercase() }
            .map { (project, projectJobs) ->
                NightlyRunProjectView(
                    project = project,
                    jobs = projectJobs.sortedBy { it.jobName }.map { job ->
                        NightlyRunJobView(job.jobName, job.title, job.status, job.storyKey, job.startedAt)
                    },
                )
            }
        return NightlyRunView(
            runDate = run.runDate,
            status = run.status,
            kind = run.kind,
            startedAt = run.startedAt,
            endedAt = run.endedAt,
            summarySentAt = run.summarySentAt,
            summaryText = run.summaryText,
            projects = projects,
        )
    }

    /** Maakt vanuit een nachtelijke job-declaratie een silent story aan en start die meteen. */
    fun createNightlyStory(project: String, jobName: String): TrackerIssue {
        val repoUrl = projectRepoResolver.repoFor(project)
            ?: error("Onbekend project: $project")
        val detail = nightlyJobsReader.readJob(repoUrl, project, jobName)
            ?: error("Nachtelijke job niet gevonden: $project/$jobName")
        val projectKey = runCatching { issueTrackerClient.ensureConfiguredProjects().firstOrNull()?.key }
            .getOrNull()
            ?: factorySecrets.youTrackProjects.firstOrNull()
            ?: "SF"
        return createStory(
            projectKey = projectKey,
            title = detail.job.title,
            description = detail.story,
            repo = project,
            aiSupplier = detail.job.aiSupplier,
            aiModel = detail.job.aiModel,
            start = true,
            silent = true,
        )
    }

    /** Stelt de auto-approve vlag in via YouTrack. */
    fun setAutoApproveFlag(storyKey: String, enabled: Boolean) {
        issueTrackerClient.updateIssueFields(
            storyKey,
            TrackerFieldUpdate.of(TrackerField.AUTO_APPROVE to if (enabled) "on" else "off"),
        )
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
            allAgentRuns = allRuns,
            events = events,
            youTrackUrl = "${factorySecrets.youTrackPublicUrl.trimEnd('/')}/issue/$storyKey",
            previewUrl = run?.let { operations.previewUrlOf(it) },
            errors = errors,
            subtasks = subtasks,
            parentKey = parentKey,
            agentQuestions = agentQuestions,
        )
    }

    fun projectsOverview(): ProjectsPageData {
        val errors = mutableListOf<String>()
        val allIssues = load(errors, emptyList()) { issueTrackerClient.findWorkIssues(maxResults = 500) }
        val costByRepo = load(errors, emptyMap()) { repository.totalCostByTargetRepo() }
        val agentCountByRepo = load(errors, emptyMap()) { repository.activeAgentCountByTargetRepo() }
        val projects = projectRepoResolver.projectNames().map { name ->
            val repoUrl = projectRepoResolver.repoFor(name) ?: ""
            val stories = allIssues.filter { it.fields.repo?.trim()?.lowercase() == name.trim().lowercase() }
            var todo = 0; var inProgress = 0; var done = 0
            stories.forEach { when (storyStatusBucket(it.status)) {
                "todo" -> todo++
                "in-progress" -> inProgress++
                "done" -> done++
            }}
            val totalCost = costByRepo.entries
                .filter { (repo, _) -> repoMatchesProject(repo, repoUrl) }
                .sumOf { it.value }
            val activeAgents = agentCountByRepo.entries
                .filter { (repo, _) -> repoMatchesProject(repo, repoUrl) }
                .sumOf { it.value }
            val deployConfig = projectRepoResolver.deployConfigFor(name)
            val prdVersion = when (deployConfig) {
                is DeployConfig.RestRestart -> fetchPrdVersion(deployConfig.versionUrl)
                else -> null
            }
            ProjectOverviewItem(
                name = name,
                repoUrl = repoUrl,
                storiesTodo = todo,
                storiesInProgress = inProgress,
                storiesDone = done,
                totalCostUsd = totalCost,
                activeAgentCount = activeAgents,
                prdVersion = prdVersion,
                hasDeployConfig = deployConfig is DeployConfig.RestRestart,
            )
        }
        return ProjectsPageData(projects, errors)
    }

    fun forceProjectDeploy(projectName: String) {
        val deployConfig = projectRepoResolver.deployConfigFor(projectName)
        require(deployConfig is DeployConfig.RestRestart) { "Geen RestRestart deploy-config voor project $projectName" }
        deployClient.forceRestart(deployConfig)
    }

    /**
     * Bucket-naam voor de projecten-tellers. De classificatie zelf leeft — gedeeld met de
     * views — in [StoryStatusPresenter]; hier alleen nog de vertaling naar de tellervelden
     * ("done" i.p.v. "finished", historisch zo in de projecten-overzicht-API).
     */
    internal fun storyStatusBucket(status: String?): String =
        when (StoryStatusPresenter.classifyStatus(status)) {
            FactoryDashboardViews.StatusBucket.FINISHED -> "done"
            FactoryDashboardViews.StatusBucket.IN_PROGRESS -> "in-progress"
            FactoryDashboardViews.StatusBucket.TODO -> "todo"
        }

    internal fun repoMatchesProject(dbTargetRepo: String, resolvedUrl: String): Boolean {
        if (resolvedUrl.isBlank() || dbTargetRepo.isBlank()) return false
        val a = dbTargetRepo.trim().lowercase()
        val b = resolvedUrl.trim().lowercase()
        return a == b || a.contains(b) || b.contains(a)
    }

    /**
     * Parseert de JSON van het `/version`-endpoint van een project met Jackson (i.p.v. losse
     * regexen). Zelfde contract als voorheen: geen (geldige) commit-hash → null; overige velden
     * vallen terug op ""; malformed JSON is een soft-fail (null), de projectenpagina toont dan
     * simpelweg geen prd-versie.
     */
    internal fun parsePrdVersionJson(json: String): PrdVersionInfo? {
        val node = runCatching { versionMapper.readTree(json) }.getOrNull() ?: return null
        val commitHash = node.nonEmptyText("commitHash") ?: node.nonEmptyText("commit") ?: return null
        return PrdVersionInfo(
            commitShort = commitHash.take(7),
            commitDate = node.nonEmptyText("commitDate") ?: "",
            branch = node.nonEmptyText("branch") ?: "",
        )
    }

    private fun fetchPrdVersion(versionUrl: String): PrdVersionInfo? =
        deployClient.fetchVersionBody(versionUrl)?.let { parsePrdVersionJson(it) }

    companion object {
        private const val MY_ACTIONS_COUNT_TTL_MS = 5_000L
        private val versionMapper = jacksonObjectMapper()

        private fun JsonNode.nonEmptyText(field: String): String? =
            get(field)?.textValue()?.takeIf { it.isNotEmpty() }

        // De vraag-extractie is met de poort-implementatie meeverhuisd naar FactoryOperationsService
        // (questionFor gebruikt 'm daar); deze aliassen blijven voor de page-assembly hier en de
        // bestaande tests.
        internal fun latestAgentQuestions(runs: List<UiAgentRun>, fallbackKey: String): Map<String, String> =
            FactoryOperationsService.latestAgentQuestions(runs, fallbackKey)

        internal fun questionTextFrom(summary: String): String =
            FactoryOperationsService.questionTextFrom(summary)
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

    fun settings(username: String, nightlySaveResult: String? = null): SettingsPageData =
        SettingsPageData(
            username = username,
            configuration = factorySecrets.redactedSummary(),
            version = versionService.info(),
            nightly = nightlySettingsRepository.read(),
            nightlySaveResult = nightlySaveResult,
        )

    /**
     * Schrijft de nachtelijke-scheduler-settings weg. `startTime`/`summaryTime` zijn `HH:MM` in
     * lokale NL-tijd; ongeldige invoer geeft een [IllegalArgumentException] zodat de controller een
     * nette foutmelding kan tonen zonder de bestaande waarden te overschrijven.
     */
    fun saveNightlySettings(enabled: Boolean, startTime: String, summaryTime: String) {
        val parsed = runCatching {
            NightlySettings(
                enabled = enabled,
                startTime = NightlyTime.parseHhMm(startTime),
                summaryTime = NightlyTime.parseHhMm(summaryTime),
            )
        }.getOrElse { throw IllegalArgumentException("Ongeldige tijd (verwacht HH:MM): ${it.message}") }
        nightlySettingsRepository.save(parsed)
    }

    /** Hard opruimen van een hele story (issue + subtaken + branch + workfolder + run). Onomkeerbaar. */
    fun purgeStory(storyKey: String) {
        orchestratorApi.purgeStory(storyKey)
    }

    /** Start refining: zet de story-fase op `start` zodat de orchestrator de refiner oppakt. */
    fun startRefining(storyKey: String) {
        issueTrackerClient.updateIssueFields(
            storyKey,
            TrackerFieldUpdate.of(TrackerField.STORY_PHASE to StoryPhase.START.trackerValue),
        )
    }

    /** Start development: zet de fase van de eerste niet-afgeronde subtask op `start`. */
    fun startDeveloping(storyKey: String) {
        val subtasks = issueTrackerClient.subtasksOf(storyKey)
        // Al een subtaak gestart of bezig? Dan niets doen (alleen vanaf de begin-toestand starten).
        if (subtasks.any { !it.fields.subtaskPhase.isNullOrBlank() }) {
            return
        }
        val first = subtasks.firstOrNull { SubtaskPhase.fromTracker(it.fields.subtaskPhase)?.isTerminal != true }
            ?: error("Geen open subtask gevonden om te starten.")
        issueTrackerClient.updateIssueFields(
            first.key,
            TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.START.trackerValue),
        )
        // Story-status van `planning-approved` ("Klaar om te starten") naar `in-progress` zodra het
        // eerste werk wordt opgepakt — zo toont het overzicht de echte status zonder de subtaken te
        // hoeven kennen.
        issueTrackerClient.updateIssueFields(
            storyKey,
            TrackerFieldUpdate.of(TrackerField.STORY_PHASE to StoryPhase.IN_PROGRESS.trackerValue),
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
        workspaceLauncher.openInIntellij(repoRoot)
        return repoRoot.toString()
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

    private fun errorMessage(exception: Throwable): String =
        exception.message?.takeIf { it.isNotBlank() } ?: exception::class.simpleName ?: "Onbekende fout"

    private fun loadWorkIssues(errors: MutableList<String>, limit: Int): List<TrackerIssue> =
        load(errors, emptyList()) { issueTrackerClient.findWorkIssues(maxResults = limit) }
}
