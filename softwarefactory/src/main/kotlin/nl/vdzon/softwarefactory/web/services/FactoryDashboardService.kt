package nl.vdzon.softwarefactory.web.services

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.web.models.AgentsPageData
import nl.vdzon.softwarefactory.web.models.DashboardPageData
import nl.vdzon.softwarefactory.web.models.MergedPageData
import nl.vdzon.softwarefactory.web.models.MyActionItem
import nl.vdzon.softwarefactory.web.models.MyActionsPageData
import nl.vdzon.softwarefactory.web.models.MyActionsStoryGroup
import nl.vdzon.softwarefactory.web.models.SettingsPageData
import nl.vdzon.softwarefactory.web.models.StoriesPageData
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.web.models.StoryDetailPageData
import nl.vdzon.softwarefactory.web.models.UiAgentRun
import nl.vdzon.softwarefactory.web.models.UiStoryRun
import nl.vdzon.softwarefactory.web.repositories.FactoryDashboardRepository
import nl.vdzon.softwarefactory.core.StoryPhase
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.FactoryCommand
import nl.vdzon.softwarefactory.core.IssueType
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.core.TrackerIssue
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
    private val projectRepoResolver: ProjectRepoResolver,
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

    /** Wacht deze (sub)taak op een mens (error, vraag, goedkeuring of handmatige stap)? */
    private fun awaitsHuman(issue: TrackerIssue): Boolean {
        // Een issue in error blokkeert de story en vraagt om ingrijpen → ook in de inbox.
        if (!issue.fields.error.isNullOrBlank()) return true
        return when (issue.issueType) {
            IssueType.STORY -> StoryPhase.fromTracker(issue.fields.storyPhase) in setOf(
                StoryPhase.REFINED_WITH_QUESTIONS,
                StoryPhase.PLANNED_WITH_QUESTIONS,
                StoryPhase.REFINED,
                StoryPhase.PLANNED,
            )
            IssueType.SUBTASK -> when (SubtaskPhase.fromTracker(issue.fields.subtaskPhase)) {
                SubtaskPhase.AWAITING_HUMAN,
                SubtaskPhase.DEVELOPED_WITH_QUESTIONS,
                SubtaskPhase.REVIEWED_WITH_QUESTIONS,
                SubtaskPhase.TESTED_WITH_QUESTIONS,
                SubtaskPhase.SUMMARY_WITH_QUESTIONS,
                SubtaskPhase.REVIEWED,
                SubtaskPhase.TESTED,
                SubtaskPhase.SUMMARIZED,
                -> true
                // Een 'developed' development-subtaak wacht op de mens; review/test/summary-subtaken niet.
                SubtaskPhase.DEVELOPED -> issue.fields.subtaskType.equals("development", ignoreCase = true)
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
        )
        if (autoApprove) {
            setAutoApproveFlag(created.key, true)
        }
        return created
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
