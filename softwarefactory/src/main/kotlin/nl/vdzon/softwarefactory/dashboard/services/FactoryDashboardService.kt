package nl.vdzon.softwarefactory.dashboard.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.DeployConfig
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.AiRouting
import nl.vdzon.softwarefactory.core.DeploymentStatusProbe
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
import nl.vdzon.softwarefactory.runtime.SubtaskMaterializationApi
import nl.vdzon.softwarefactory.dashboard.models.AgentsPageData
import nl.vdzon.softwarefactory.dashboard.models.BuildSyncStatus
import nl.vdzon.softwarefactory.dashboard.models.BuildsPageData
import nl.vdzon.softwarefactory.dashboard.models.DashboardPageData
import nl.vdzon.softwarefactory.dashboard.models.DownloadInfo
import nl.vdzon.softwarefactory.dashboard.models.DownloadsPageData
import nl.vdzon.softwarefactory.dashboard.models.LiveComponentStatus
import nl.vdzon.softwarefactory.dashboard.models.MergedPageData
import nl.vdzon.softwarefactory.dashboard.models.MyActionItem
import nl.vdzon.softwarefactory.dashboard.models.MyActionsPageData
import nl.vdzon.softwarefactory.dashboard.models.MyActionsStoryGroup
import nl.vdzon.softwarefactory.dashboard.models.NightlyJobsPageData
import nl.vdzon.softwarefactory.dashboard.models.NightlyRunJobView
import nl.vdzon.softwarefactory.dashboard.models.NightlyRunProjectView
import nl.vdzon.softwarefactory.dashboard.models.NightlyRunView
import nl.vdzon.softwarefactory.dashboard.models.PrdVersionInfo
import nl.vdzon.softwarefactory.dashboard.models.ProjectBuildStatus
import nl.vdzon.softwarefactory.dashboard.models.ProjectOverviewItem
import nl.vdzon.softwarefactory.dashboard.models.ProjectsPageData
import nl.vdzon.softwarefactory.dashboard.models.RepoBuildsView
import nl.vdzon.softwarefactory.dashboard.models.SettingsPageData
import nl.vdzon.softwarefactory.dashboard.models.StoriesPageData
import nl.vdzon.softwarefactory.dashboard.models.StoryDetailPageData
import nl.vdzon.softwarefactory.dashboard.models.UiAgentRun
import nl.vdzon.softwarefactory.dashboard.models.WorkflowRunInfo
import nl.vdzon.softwarefactory.dashboard.repositories.FactoryDashboardRepository
import nl.vdzon.softwarefactory.tracker.TrackerApi
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
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
    private val issueTrackerClient: TrackerApi,
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
    private val gitHubReleaseClient: GitHubReleaseClient,
    private val gitHubActionsClient: GitHubActionsClient,
    private val deploymentStatusProbe: DeploymentStatusProbe,
    // Config-pad voor nightly-jobs (SF-787): materialiseert de gedeclareerde subtaken direct.
    // Injecteert de geëxposeerde runtime-poort i.p.v. de concrete SubtaskPlanMaterializer, zodat de
    // web->runtime-afhankelijkheid binnen de Spring-Modulith module-grens blijft.
    private val subtaskPlanMaterializer: SubtaskMaterializationApi,
) {

    fun dashboard(): DashboardPageData {
        val errors = mutableListOf<String>()
        val issues = loadWorkIssues(errors, limit = 20, includeFinished = true)
        val activeRuns = load(errors, emptyList()) { repository.activeStoryRuns(limit = 20) }
        val recentRuns = load(errors, emptyList()) { repository.recentStoryRuns(limit = 10) }
        val activeAgents = load(errors, emptyList()) { repository.activeAgentRuns(limit = 10) }
        val attentionBuilds = load(errors, emptyList()) { failingDefaultBranchBuilds() }
        return DashboardPageData(issues, activeRuns, recentRuns, activeAgents, errors, attentionBuilds)
    }

    fun stories(): StoriesPageData {
        val errors = mutableListOf<String>()
        val issues = loadWorkIssues(errors, limit = 100, includeFinished = true)
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
            // `parentKey` komt al mee in findWorkIssues() (de tracker-mapping bevat de link-data)
            // — geen aparte tracker-call per subtaak meer nodig (was een N+1).
            // Fallback op de losse call blijft staan voor het randgeval dat de link-data ontbreekt.
            val ownerKey = if (issue.issueType == IssueType.SUBTASK) {
                issue.parentKey ?: load(errors) { issueTrackerClient.parentStoryKey(issue.key) } ?: issue.key
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

    /**
     * Maakt een nieuwe story aan vanuit het dashboard.
     *
     * SF-818 — [projectKey] is optioneel: het "Nieuwe story"-dialoog stuurt geen project meer mee
     * (het factory-model is single-project). Ontbreekt de key, dan valt de service terug op het enige
     * geconfigureerde project (zelfde bron als [createNightlyStory]), zodat de key-generatie (`SF-###`)
     * blijft werken.
     */
    fun createStory(
        projectKey: String?,
        title: String,
        description: String?,
        repo: String?,
        aiSupplier: String?,
        aiModel: String?,
        start: Boolean,
        autoApprove: Boolean = false,
        silent: Boolean = false,
    ): TrackerIssue {
        require(title.isNotBlank()) { "Titel is verplicht." }
        val resolvedProjectKey = resolveProjectKey(projectKey)
        val resolvedAiSupplier = aiSupplier?.takeIf { it.isNotBlank() }
        // Blanco AI-model bij aanmaken → meteen het echte default-model vastleggen (i.p.v. pas bij
        // dispatch via AiRouting op te lossen), zodat de storydetails altijd tonen welk model
        // gebruikt gaat worden, ook vóórdat er ooit een agent gedraaid heeft.
        val resolvedAiModel = aiModel?.takeIf { it.isNotBlank() }
            ?: AiRouting.resolve(level = null, supplier = resolvedAiSupplier, role = AgentRole.DEVELOPER).model
        val created = issueTrackerClient.createStory(
            projectKey = resolvedProjectKey,
            title = title,
            description = description?.takeIf { it.isNotBlank() },
            repo = repo?.takeIf { it.isNotBlank() },
            aiSupplier = resolvedAiSupplier,
            aiModel = resolvedAiModel,
            start = start,
            silent = silent,
        )
        if (autoApprove) {
            setAutoApproveFlag(created.key, true)
        }
        return created
    }

    /**
     * Bepaalt de projectkey voor een nieuwe story. Een expliciet meegegeven, niet-lege key wint;
     * anders valt de service terug op het enige geconfigureerde project (via `ensureConfiguredProjects()`
     * met terugval op `FactorySecrets.trackerProjects`). Bij geen enkel geconfigureerd project faalt
     * het aanmaken met een leesbare melding i.p.v. een verkeerde key te genereren.
     */
    private fun resolveProjectKey(projectKey: String?): String {
        projectKey?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching { issueTrackerClient.ensureConfiguredProjects().firstOrNull()?.key }.getOrNull()
            ?: factorySecrets.trackerProjects.firstOrNull()
            ?: error("Geen project geconfigureerd; stel SF_TRACKER_PROJECTS in of maak eerst een story aan.")
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

    /**
     * Maakt vanuit een nachtelijke job-declaratie een silent story aan.
     *
     * Config-pad (SF-787): heeft de job een geldige `subtasks.yaml`, dan wordt de story ZONDER
     * refiner/planner aangemaakt (`start = false`), worden precies de gedeclareerde subtaken
     * gematerialiseerd (geen factory-afgedwongen extra subtaken) en gaat de story-fase op
     * `planning-approved` — waarna de bestaande statemachine de keten start.
     *
     * Legacy-pad: zonder `subtasks.yaml` blijft het huidige gedrag (refine + plan) ongewijzigd.
     * Een ongeldige `subtasks.yaml` gooit ([nl.vdzon.softwarefactory.nightly.NightlySubtasksConfigException]
     * via de reader), zodat er GEEN story wordt aangemaakt en de fout in de nachtelijke digest belandt.
     */
    fun createNightlyStory(project: String, jobName: String): TrackerIssue {
        val repoUrl = projectRepoResolver.repoFor(project)
            ?: error("Onbekend project: $project")
        val detail = nightlyJobsReader.readJob(repoUrl, project, jobName)
            ?: error("Nachtelijke job niet gevonden: $project/$jobName")
        val projectKey = runCatching { issueTrackerClient.ensureConfiguredProjects().firstOrNull()?.key }
            .getOrNull()
            ?: factorySecrets.trackerProjects.firstOrNull()
            ?: "SF"

        val specs = detail.subtasks
        if (specs.isNullOrEmpty()) {
            // Legacy-pad: laat refiner + planner draaien (start = true).
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

        // Config-pad: story zonder refiner/planner, subtaken direct materialiseren, fase -> planning-approved.
        val story = createStory(
            projectKey = projectKey,
            title = detail.job.title,
            description = detail.story,
            repo = project,
            aiSupplier = detail.job.aiSupplier,
            aiModel = detail.job.aiModel,
            start = false,
            silent = true,
        )
        subtaskPlanMaterializer.materializeFromSpecs(story.key, specs)
        issueTrackerClient.updateIssueFields(
            story.key,
            TrackerFieldUpdate.of(TrackerField.STORY_PHASE to StoryPhase.PLANNING_APPROVED.trackerValue),
        )
        return story
    }

    /** Stelt de auto-approve vlag in via de tracker. */
    fun setAutoApproveFlag(storyKey: String, enabled: Boolean) {
        issueTrackerClient.updateIssueFields(
            storyKey,
            TrackerFieldUpdate.of(TrackerField.AUTO_APPROVE to if (enabled) "on" else "off"),
        )
    }

    /** Stelt de silent-vlag in via de tracker. */
    fun setSilentFlag(storyKey: String, enabled: Boolean) {
        issueTrackerClient.updateIssueFields(
            storyKey,
            TrackerFieldUpdate.of(TrackerField.SILENT to if (enabled) "on" else "off"),
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
            previewUrl = run?.let { operations.previewUrlOf(it) },
            errors = errors,
            subtasks = subtasks,
            parentKey = parentKey,
            agentQuestions = agentQuestions,
        )
    }

    // Korte cache op de hele pagina: `fetchPrdVersion` is een HTTP-call per project met deploy-config
    // en zonder cache betaalt elke tab/poll die latency opnieuw. Ook los daarvan al geparallelliseerd
    // (zie hieronder) zodat N projecten niet N keer de netwerk-latency optellen.
    @Volatile
    private var projectsOverviewCache: Pair<Long, ProjectsPageData>? = null

    fun projectsOverview(force: Boolean = false): ProjectsPageData {
        val now = System.currentTimeMillis()
        if (!force) {
            projectsOverviewCache?.let { (at, value) -> if (now - at < PAGE_CACHE_TTL_MS) return value }
        }

        val errors = mutableListOf<String>()
        val allIssues = load(errors, emptyList()) { issueTrackerClient.findWorkIssues(maxResults = 500, includeFinished = true) }
        val costByRepo = load(errors, emptyMap()) { repository.totalCostByTargetRepo() }
        val agentCountByRepo = load(errors, emptyMap()) { repository.activeAgentCountByTargetRepo() }
        val names = projectRepoResolver.projectNames()
        // De prdVersion- en build-HTTP-calls zijn de enige langzame stap (netwerk) en zijn onderling
        // onafhankelijk per project — parallel uitvoeren i.p.v. serieel in de .map hieronder.
        val prdVersionFutures = names.associateWith { name ->
            val deployConfig = projectRepoResolver.deployConfigFor(name)
            if (deployConfig is DeployConfig.RestRestart) {
                CompletableFuture.supplyAsync { fetchPrdVersion(deployConfig.versionUrl) }
            } else {
                CompletableFuture.completedFuture(null)
            }
        }
        val buildsFutures = names.associateWith { name ->
            val slug = GitHubSlug.fromUrl(projectRepoResolver.repoFor(name))
            if (slug != null) {
                CompletableFuture.supplyAsync {
                    ProjectBuildData(gitHubActionsClient.latestRunsPerWorkflow(slug, name), gitHubActionsClient.defaultBranch(slug))
                }
            } else {
                CompletableFuture.completedFuture(ProjectBuildData(emptyList(), null))
            }
        }
        // OpenShift-live-status hangt van de laatste main-build-sha af (voor de sync-vergelijking),
        // dus als vervolg op buildsFutures i.p.v. een losse future — blijft zo per project parallel
        // met de andere projecten, zonder de GitHub-call dubbel te doen.
        val liveComponentsFutures = names.associateWith { name ->
            buildsFutures.getValue(name).thenApplyAsync { buildData ->
                fetchLiveComponents(name, lastCompletedMainRun(buildData.runs, buildData.defaultBranch)?.headSha)
            }
        }
        val projects = names.map { name ->
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
            val hasDeployConfig = deployConfig is DeployConfig.RestRestart
            val prdVersion = runCatching { prdVersionFutures.getValue(name).get(PRD_VERSION_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                .getOrNull()
            val buildData = runCatching { buildsFutures.getValue(name).get(PRD_VERSION_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                .getOrDefault(ProjectBuildData(emptyList(), null))
            val liveComponents = runCatching {
                liveComponentsFutures.getValue(name).get(LIVE_COMPONENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }.getOrDefault(emptyList())
            ProjectOverviewItem(
                name = name,
                repoUrl = repoUrl,
                storiesTodo = todo,
                storiesInProgress = inProgress,
                storiesDone = done,
                totalCostUsd = totalCost,
                activeAgentCount = activeAgents,
                prdVersion = prdVersion,
                hasDeployConfig = hasDeployConfig,
                buildStatus = buildStatusFor(buildData.runs, buildData.defaultBranch, hasDeployConfig, prdVersion),
                liveComponents = liveComponents,
            )
        }
        val result = ProjectsPageData(projects, errors)
        projectsOverviewCache = now to result
        return result
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
            StatusBucket.FINISHED -> "done"
            StatusBucket.IN_PROGRESS -> "in-progress"
            StatusBucket.TODO -> "todo"
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

    /**
     * Live-status per geconfigureerde OpenShift-component van [name] (zie
     * [ProjectRepoResolver.liveComponentsFor]): het daadwerkelijk draaiende image + sinds wanneer,
     * vergeleken met [lastMainSha] (laatst afgeronde main-build, zie [lastCompletedMainRun]).
     * Een kubectl-fout op één component (bv. cluster tijdelijk onbereikbaar) faalt alleen dié
     * component (`UNAVAILABLE`), niet de rest van de projectenpagina.
     */
    private fun fetchLiveComponents(name: String, lastMainSha: String?): List<LiveComponentStatus> =
        projectRepoResolver.liveComponentsFor(name).map { component ->
            val pod = runCatching { deploymentStatusProbe.runningPod(component.namespace, component.deployment) }.getOrNull()
            val shortSha = pod?.image?.let(::shortShaFromImage)
            val uptimeSeconds = pod?.startedAt?.let(::uptimeSecondsSince)
            val syncStatus = when {
                shortSha == null || lastMainSha.isNullOrBlank() -> BuildSyncStatus.UNAVAILABLE
                shaPrefixMatch(shortSha, lastMainSha) -> BuildSyncStatus.IN_SYNC
                else -> BuildSyncStatus.OUT_OF_SYNC
            }
            LiveComponentStatus(
                label = component.label,
                shortSha = shortSha,
                podStartedAt = pod?.startedAt,
                uptimeSeconds = uptimeSeconds,
                syncStatus = syncStatus,
            )
        }

    /** Tussenresultaat van de per-project build-fetch (zie [projectsOverview]), geen API-model. */
    private data class ProjectBuildData(val runs: List<WorkflowRunInfo>, val defaultBranch: String?)

    companion object {
        private const val MY_ACTIONS_COUNT_TTL_MS = 5_000L
        private const val PAGE_CACHE_TTL_MS = 20_000L
        private const val PRD_VERSION_TIMEOUT_MS = 3_000L
        // Ruimer dan PRD_VERSION_TIMEOUT_MS: per component 2 kubectl-subprocessen (matchLabels
        // opzoeken, dan de pod), en sommige projecten hebben er meerdere (bv. softwarefactory:
        // backend + frontend).
        private const val LIVE_COMPONENT_TIMEOUT_MS = 5_000L
        private val versionMapper = jacksonObjectMapper()
        private val ACTIVE_RUN_STATUSES = setOf("queued", "in_progress")

        /** Laatst afgeronde workflow-run met `event == push` op de default branch (of null). */
        internal fun lastCompletedMainRun(runs: List<WorkflowRunInfo>, defaultBranch: String?): WorkflowRunInfo? =
            runs.filter { it.event == "push" && defaultBranch != null && it.branch == defaultBranch }
                .filter { it.status == "completed" }
                .maxByOrNull { it.runStartedAt ?: it.updatedAt ?: "" }

        /** Korte commit-sha uit een image-tag (bv. `ghcr.io/x/y:sha-66d1019` -> `66d1019`), of null. */
        internal fun shortShaFromImage(image: String): String? {
            val tag = image.substringAfterLast('/').substringAfter(':', missingDelimiterValue = "")
                .takeIf { it.isNotEmpty() } ?: return null
            return tag.removePrefix("sha-").takeIf { it.isNotEmpty() }
        }

        /** Aantal seconden sinds [startedAt] (RFC3339/ISO-8601), of null bij een onparseerbare waarde. */
        internal fun uptimeSecondsSince(startedAt: String): Long? =
            runCatching { Duration.between(Instant.parse(startedAt), Instant.now()).seconds.coerceAtLeast(0) }.getOrNull()

        private fun JsonNode.nonEmptyText(field: String): String? =
            get(field)?.textValue()?.takeIf { it.isNotEmpty() }

        /**
         * Leidt de build-/deploy-status van één project af uit de laatste run per workflow ([runs],
         * zie [GitHubActionsClient.latestRunsPerWorkflow]). "Main-build" = `event == push` op de
         * default branch; "PR-build" = `event == pull_request`. Sync-status vergelijkt de prd-versie
         * met de laatst afgeronde main-build-sha (prefix-tolerant, zelfde recept als
         * [nl.vdzon.softwarefactory.pipeline.service.DeploySubtaskHandler.shaPrefixMatch]); zonder
         * deploy-configuratie of zonder vergelijkbare data is de status `UNAVAILABLE`.
         */
        internal fun buildStatusFor(
            runs: List<WorkflowRunInfo>,
            defaultBranch: String?,
            hasDeployConfig: Boolean,
            prdVersion: PrdVersionInfo?,
        ): ProjectBuildStatus {
            val mainRuns = runs.filter { it.event == "push" && defaultBranch != null && it.branch == defaultBranch }
            val prRuns = runs.filter { it.event == "pull_request" }
            val lastCompletedMain = lastCompletedMainRun(runs, defaultBranch)
            val syncStatus = when {
                !hasDeployConfig -> BuildSyncStatus.UNAVAILABLE
                prdVersion == null || lastCompletedMain?.headSha.isNullOrBlank() -> BuildSyncStatus.UNAVAILABLE
                shaPrefixMatch(prdVersion.commitShort, lastCompletedMain.headSha) -> BuildSyncStatus.IN_SYNC
                else -> BuildSyncStatus.OUT_OF_SYNC
            }
            return ProjectBuildStatus(
                lastMainBuildAt = lastCompletedMain?.updatedAt,
                mainBuildActive = mainRuns.any { it.status in ACTIVE_RUN_STATUSES },
                prBuildActive = prRuns.any { it.status in ACTIVE_RUN_STATUSES },
                syncStatus = syncStatus,
            )
        }

        /** Prefix-tolerante SHA-vergelijking (short vs. full SHA), hoofdletter-ongevoelig. */
        internal fun shaPrefixMatch(a: String, b: String): Boolean {
            val x = a.trim().lowercase()
            val y = b.trim().lowercase()
            if (x.isEmpty() || y.isEmpty()) return false
            return x.startsWith(y) || y.startsWith(x)
        }

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

    @Volatile
    private var downloadsCache: Pair<Long, DownloadsPageData>? = null

    /**
     * `.apk`-downloads per geconfigureerde repo (projects.yaml), over de meest recente releases
     * (niet alleen "latest") — zie [GitHubReleaseClient.apkDownloads] voor waarom: een repo met
     * meerdere apps publiceert per app een eigen permanent-overschreven tag, dus die staan als
     * losse releases naast elkaar. Nieuwe operatie voor de bridge (§5 `downloads.list`) — het oude
     * Kotlin-dashboard toont dit nog niet. Zelfde parallel+cache-recept als [projectsOverview]: per
     * repo een onafhankelijke netwerk-call, dus parallel i.p.v. serieel, en 20s gecached zodat elke
     * tab/poll niet opnieuw betaalt.
     */
    fun downloads(force: Boolean = false): DownloadsPageData {
        val now = System.currentTimeMillis()
        if (!force) {
            downloadsCache?.let { (at, value) -> if (now - at < PAGE_CACHE_TTL_MS) return value }
        }
        val errors = mutableListOf<String>()
        val names = projectRepoResolver.projectNames()
        val futures = names.associateWith { name ->
            val slug = GitHubSlug.fromUrl(projectRepoResolver.repoFor(name))
            if (slug != null) {
                CompletableFuture.supplyAsync { gitHubReleaseClient.apkDownloads(slug, name) }
            } else {
                CompletableFuture.completedFuture(emptyList<DownloadInfo>())
            }
        }
        val downloads = names.flatMap { name ->
            runCatching { futures.getValue(name).get(PRD_VERSION_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                .getOrElse { errors += errorMessage(it); emptyList() }
        }
        val result = DownloadsPageData(downloads = downloads, errors = errors)
        downloadsCache = now to result
        return result
    }

    @Volatile
    private var buildsCache: Pair<Long, BuildsPageData>? = null

    /**
     * Laatste GitHub Actions-run per workflow, per geconfigureerde repo (projects.yaml). Nieuwe
     * operatie voor de bridge (§5 `builds.list`), zie [GitHubActionsClient]. Zelfde parallel+cache-
     * recept als [projectsOverview]/[downloads].
     */
    fun builds(force: Boolean = false): BuildsPageData {
        val now = System.currentTimeMillis()
        if (!force) {
            buildsCache?.let { (at, value) -> if (now - at < PAGE_CACHE_TTL_MS) return value }
        }
        val errors = mutableListOf<String>()
        val names = projectRepoResolver.projectNames()
        val futures = names.associateWith { name ->
            val slug = GitHubSlug.fromUrl(projectRepoResolver.repoFor(name))
            if (slug != null) {
                CompletableFuture.supplyAsync { gitHubActionsClient.latestRunsPerWorkflow(slug, name) }
            } else {
                CompletableFuture.completedFuture(emptyList<WorkflowRunInfo>())
            }
        }
        val repos = names.mapNotNull { name ->
            val slug = GitHubSlug.fromUrl(projectRepoResolver.repoFor(name)) ?: return@mapNotNull null
            val runs = runCatching { futures.getValue(name).get(PRD_VERSION_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                .getOrElse { errors += errorMessage(it); emptyList() }
            RepoBuildsView(projectKey = name, repository = slug, runs = runs)
        }
        val result = BuildsPageData(repos = repos, errors = errors)
        buildsCache = now to result
        return result
    }

    /** Laatste run per workflow voor één repo (`owner/repo`) — voor `GET /api/v1/repositories/{owner}/{repo}/(workflows|runs)`. */
    fun buildsFor(owner: String, repo: String): List<WorkflowRunInfo> {
        val slug = "$owner/$repo"
        val projectKey = projectRepoResolver.projectNames()
            .firstOrNull { name -> GitHubSlug.fromUrl(projectRepoResolver.repoFor(name)) == slug }
            ?: slug
        return gitHubActionsClient.latestRunsPerWorkflow(slug, projectKey)
    }

    /** Runs op de default branch van een beheerd repo met `conclusion == failure` — voor de attention-sectie. */
    private fun failingDefaultBranchBuilds(): List<WorkflowRunInfo> =
        builds().repos.flatMap { repo ->
            val defaultBranch = gitHubActionsClient.defaultBranch(repo.repository) ?: return@flatMap emptyList()
            repo.runs.filter { it.branch == defaultBranch && it.conclusion == "failure" }
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

    private fun loadWorkIssues(errors: MutableList<String>, limit: Int, includeFinished: Boolean = false): List<TrackerIssue> =
        load(errors, emptyList()) { issueTrackerClient.findWorkIssues(maxResults = limit, includeFinished = includeFinished) }
}
