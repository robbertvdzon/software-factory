package nl.vdzon.softwarefactory.web.services

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.nightly.NightlyJobsReader
import nl.vdzon.softwarefactory.nightly.NightlySettings
import nl.vdzon.softwarefactory.nightly.NightlySettingsRepository
import nl.vdzon.softwarefactory.web.models.NightlyJobsPageData
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.web.models.AgentsPageData
import nl.vdzon.softwarefactory.web.models.DashboardPageData
import nl.vdzon.softwarefactory.web.models.MergedPageData
import nl.vdzon.softwarefactory.web.models.MyActionItem
import nl.vdzon.softwarefactory.web.models.MyActionsPageData
import nl.vdzon.softwarefactory.web.models.MyActionsStoryGroup
import nl.vdzon.softwarefactory.web.models.PrdVersionInfo
import nl.vdzon.softwarefactory.web.models.ProjectOverviewItem
import nl.vdzon.softwarefactory.web.models.ProjectsPageData
import nl.vdzon.softwarefactory.web.models.SettingsPageData
import nl.vdzon.softwarefactory.web.models.StoriesPageData
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.web.models.StoryDetailPageData
import nl.vdzon.softwarefactory.web.models.UiAgentRun
import nl.vdzon.softwarefactory.web.models.UiStoryRun
import nl.vdzon.softwarefactory.web.repositories.FactoryDashboardRepository
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.StoryPhase
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.FactoryCommand
import nl.vdzon.softwarefactory.core.IssueType
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.config.DeployConfig
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
    private val projectRepoResolver: ProjectRepoResolver,
    private val versionService: FactoryVersionService,
    private val nightlySettingsRepository: NightlySettingsRepository,
    private val nightlyJobsReader: NightlyJobsReader = NightlyJobsReader(),
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
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

    /**
     * De door de agent gestelde vraag voor dit issue, of null. Zelfde bron als de "My actions"-inbox
     * (de meest recente run met een niet-lege samenvatting, met de questions-JSON eruit gefilterd).
     * Hergebruikt door de Telegram-notifier zodat een melding exact dezelfde vraagtekst toont als het
     * dashboard.
     */
    fun questionFor(issue: TrackerIssue): String? {
        val ownerKey = if (issue.issueType == IssueType.SUBTASK) {
            runCatching { issueTrackerClient.parentStoryKey(issue.key) }.getOrNull() ?: issue.key
        } else {
            issue.key
        }
        val run = runCatching { repository.latestStoryRun(ownerKey) }.getOrNull() ?: return null
        val runs = runCatching { repository.agentRunsForStory(run.id) }.getOrDefault(emptyList())
        return latestAgentQuestions(runs, ownerKey)[issue.key]
    }

    /** Een story die af is (alle subtaken terminaal) en een open, nog niet gemergede PR heeft. */
    data class MergeReadyInfo(val storyKey: String, val prNumber: Int?, val prUrl: String?)

    /**
     * Is [storyKey] klaar om te mergen? Alle subtaken terminaal én er is een PR die nog niet gemerged
     * is. Geeft de PR-info terug, of null wanneer er nog werk open staat / geen PR is / al gemerged.
     * Gebruikt door de Telegram-notifier om aan het einde een merge-actie aan te bieden.
     */
    fun mergeReady(storyKey: String): MergeReadyInfo? {
        val subtasks = runCatching { issueTrackerClient.subtasksOf(storyKey) }.getOrNull() ?: return null
        if (subtasks.isEmpty()) return null
        val allDone = subtasks.all { SubtaskPhase.fromTracker(it.fields.subtaskPhase)?.isTerminal == true }
        if (!allDone) return null
        val run = runCatching { repository.latestStoryRun(storyKey) }.getOrNull() ?: return null
        if (run.prNumber == null) return null
        if (run.finalStatus.equals("merged", ignoreCase = true)) return null
        return MergeReadyInfo(storyKey, run.prNumber, run.prUrl)
    }

    /** Idem, maar startend vanaf een zojuist afgeronde subtaak: zoekt eerst de parent-story op. */
    fun mergeReadyForSubtask(subtask: TrackerIssue): MergeReadyInfo? {
        val parentKey = runCatching { issueTrackerClient.parentStoryKey(subtask.key) }.getOrNull() ?: return null
        return mergeReady(parentKey)
    }

    /**
     * Het laatste tester-rapport van [storyKey]: de samenvatting van de meest recente TESTER-agent-run met
     * niet-lege tekst, of null. Gebruikt door de Telegram-melding om bij een afgeronde test-subtaak het
     * testrapport mee te sturen. Soft-fail: een DB-fout geeft null i.p.v. te gooien.
     */
    fun testerReportFor(storyKey: String): String? {
        val run = runCatching { repository.latestStoryRun(storyKey) }.getOrNull() ?: return null
        val runs = runCatching { repository.agentRunsForStory(run.id) }.getOrDefault(emptyList())
        return runs
            .filter { it.role.equals(AgentRole.TESTER.markerKeyPart, ignoreCase = true) }
            .sortedByDescending { it.startedAt }
            .firstNotNullOfOrNull { it.summaryText?.takeIf { s -> s.isNotBlank() } }
    }

    /**
     * De preview-/test-URL van [storyKey] (dezelfde als de 'Test op preview'-knop), of null wanneer het
     * project geen preview heeft (`previewUrlTemplate` ontbreekt). Soft-fail: gooit nooit.
     */
    fun previewUrlFor(storyKey: String): String? {
        val run = runCatching { repository.latestStoryRun(storyKey) }.getOrNull() ?: return null
        return runCatching { run.previewUrl() }.getOrNull()
    }

    /**
     * Auto-approve geldt centraal op de PARENT-story; een subtask zelf mag 'm ook gezet hebben.
     * Voor een subtask dus: eigen vlag OF die van de parent-story (parent-lookup is best-effort).
     * Spiegelt [SubtaskExecutionCoordinator.autoApproveActive] zodat melding/inbox en uitvoering
     * dezelfde beslissing nemen.
     */
    internal fun autoApproveActive(issue: TrackerIssue): Boolean {
        // SF-335 — silent impliceert auto-approve: de conditie is (autoApprove || silent), met dezelfde
        // best-effort parent-lookup voor subtaken.
        if (issue.fields.autoApprove || issue.fields.silent) return true
        if (issue.issueType != IssueType.SUBTASK) return false
        val parentKey = runCatching { issueTrackerClient.parentStoryKey(issue.key) }.getOrNull() ?: return false
        return runCatching {
            issueTrackerClient.getIssue(parentKey).fields.let { it.autoApprove || it.silent }
        }.getOrDefault(false)
    }

    /** Wacht deze (sub)taak op een mens (error, vraag, goedkeuring of handmatige stap)? */
    internal fun awaitsHuman(issue: TrackerIssue): Boolean {
        // Een issue in error blokkeert de story en vraagt om ingrijpen → ook in de inbox.
        if (!issue.fields.error.isNullOrBlank()) return true
        val autoApprove = autoApproveActive(issue)
        return when (issue.issueType) {
            IssueType.STORY -> when (StoryPhase.fromTracker(issue.fields.storyPhase)) {
                StoryPhase.REFINED_WITH_QUESTIONS,
                StoryPhase.PLANNED_WITH_QUESTIONS,
                -> true
                StoryPhase.REFINED,
                StoryPhase.PLANNED,
                -> !autoApprove
                else -> false
            }
            IssueType.SUBTASK -> when (SubtaskPhase.fromTracker(issue.fields.subtaskPhase)) {
                SubtaskPhase.AWAITING_HUMAN,
                // SF-192 — de manual-approve-poort vraagt altijd om een mens, ook bij auto-approve.
                SubtaskPhase.MANUAL_APPROVE_NEEDED,
                SubtaskPhase.DEVELOPED_WITH_QUESTIONS,
                SubtaskPhase.REVIEWED_WITH_QUESTIONS,
                SubtaskPhase.TESTED_WITH_QUESTIONS,
                SubtaskPhase.SUMMARY_WITH_QUESTIONS,
                -> true
                SubtaskPhase.REVIEWED,
                SubtaskPhase.TESTED,
                SubtaskPhase.SUMMARIZED,
                -> !autoApprove
                // Een 'developed' development-subtaak wacht op de mens; review/test/summary-subtaken niet.
                SubtaskPhase.DEVELOPED -> !autoApprove && issue.fields.subtaskType.equals("development", ignoreCase = true)
                else -> false
            }
        }
    }

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
    fun nightlyJobs(): NightlyJobsPageData {
        val projects = projectRepoResolver.projectNames().mapNotNull { name ->
            projectRepoResolver.repoFor(name)?.let { name to it }
        }
        val result = nightlyJobsReader.readAll(projects)
        return NightlyJobsPageData(result.jobs, result.errors)
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
            previewUrl = run?.previewUrl(),
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
        val token = System.getenv(deployConfig.tokenEnvVar)
            ?: error("Env-var ${deployConfig.tokenEnvVar} niet ingesteld")
        val request = HttpRequest.newBuilder(URI.create(deployConfig.restartUrl))
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Force-deploy faalde: HTTP ${response.statusCode()}"
        }
    }

    internal fun storyStatusBucket(status: String?): String {
        val normalized = status?.trim()?.lowercase() ?: return "todo"
        return when (normalized) {
            "done", "fixed", "verified", "closed", "resolved" -> "done"
            "in progress", "to verify", "develop", "developing" -> "in-progress"
            else -> "todo"
        }
    }

    internal fun repoMatchesProject(dbTargetRepo: String, resolvedUrl: String): Boolean {
        if (resolvedUrl.isBlank() || dbTargetRepo.isBlank()) return false
        val a = dbTargetRepo.trim().lowercase()
        val b = resolvedUrl.trim().lowercase()
        return a == b || a.contains(b) || b.contains(a)
    }

    internal fun parsePrdVersionJson(json: String): PrdVersionInfo? {
        val commitHash = Regex(""""commitHash"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            ?: Regex(""""commit"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            ?: return null
        val commitDate = Regex(""""commitDate"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: ""
        val branch = Regex(""""branch"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: ""
        return PrdVersionInfo(
            commitShort = commitHash.take(7),
            commitDate = commitDate,
            branch = branch,
        )
    }

    private fun fetchPrdVersion(versionUrl: String): PrdVersionInfo? =
        runCatching {
            val request = HttpRequest.newBuilder(URI.create(versionUrl)).GET().build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return null
            parsePrdVersionJson(response.body())
        }.getOrNull()

    companion object {
        private const val MY_ACTIONS_COUNT_TTL_MS = 5_000L
        private val questionMapper = jacksonObjectMapper()

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
                        ?.let { key to questionTextFrom(it) }
                }
                .toMap()

        /**
         * Een agent stuurt z'n hele bericht als samenvatting, met ergens een control-JSON:
         * `{"phase":"...-with-questions","questions":["...","..."]}`. Toon ALLEEN die vragen
         * (genummerd bij meerdere). De JSON mag multi-line / pretty-printed zijn en in een
         * ```json-codeblok staan. Geen herkenbare questions-JSON → val terug op de volle samenvatting.
         */
        internal fun questionTextFrom(summary: String): String {
            val questions = jsonObjectsIn(summary)
                .mapNotNull { runCatching { questionMapper.readTree(it) }.getOrNull() }
                .firstOrNull { it.path("questions").isArray }
                ?.path("questions")
                ?.mapNotNull { node -> node.asText("").takeIf { it.isNotBlank() } }
                ?.takeIf { it.isNotEmpty() }
                ?: return summary
            return if (questions.size == 1) {
                questions.single()
            } else {
                questions.mapIndexed { index, q -> "${index + 1}. $q" }.joinToString("\n\n")
            }
        }

        /**
         * Alle top-level `{...}`-objecten in [text], met balans op accolades en string-bewust
         * (accolades binnen "..."-strings tellen niet mee). Zo wordt ook een multi-line of in een
         * ```json-blok ingepakte JSON correct uit de agent-samenvatting gehaald.
         */
        private fun jsonObjectsIn(text: String): List<String> {
            val results = mutableListOf<String>()
            var depth = 0
            var start = -1
            var inString = false
            var escaped = false
            for (i in text.indices) {
                val c = text[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> {
                        if (depth == 0) start = i
                        depth++
                    }
                    '}' -> if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            results += text.substring(start, i + 1)
                            start = -1
                        }
                    }
                }
            }
            return results
        }
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
                startTime = nl.vdzon.softwarefactory.nightly.NightlyTime.parseHhMm(startTime),
                summaryTime = nl.vdzon.softwarefactory.nightly.NightlyTime.parseHhMm(summaryTime),
            )
        }.getOrElse { throw IllegalArgumentException("Ongeldige tijd (verwacht HH:MM): ${it.message}") }
        nightlySettingsRepository.save(parsed)
    }

    fun queueCommand(storyKey: String, command: FactoryCommand, reason: String? = null) {
        orchestratorApi.queueCommand(storyKey, command, reason)
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
