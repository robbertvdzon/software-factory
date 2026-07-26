package nl.vdzon.softwarefactory.dashboard.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.dashboard.models.CommitInfo
import nl.vdzon.softwarefactory.dashboard.models.PullRequestDetail
import nl.vdzon.softwarefactory.dashboard.models.PullRequestInfo
import nl.vdzon.softwarefactory.dashboard.models.WorkflowRunInfo
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * GitHub Actions REST-client voor de bridge-operaties `builds.list`/`builds.runs` (zie
 * docs/ontwerp-bridge-dashboard.md §5) — zelfde recept als [GitHubReleaseClient], nu voor
 * workflow-runs i.p.v. releases. Korte in-memory TTL-cache per repo-slug (patroon
 * [nl.vdzon.softwarefactory.nightly.NightlyJobsReader]) omdat zowel het geaggregeerde
 * `builds.list` als het per-repo endpoint dezelfde data ophalen.
 */
@Component
class GitHubActionsClient(
    private val secrets: FactorySecrets,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    private val objectMapper = jacksonObjectMapper()

    @Volatile
    private var runsCache: Map<String, Pair<Long, List<WorkflowRunInfo>>> = emptyMap()

    @Volatile
    private var defaultBranchCache: Map<String, Pair<Long, String?>> = emptyMap()

    @Volatile
    private var lastCompletedPushRunCache: Map<Pair<String, String>, Pair<Long, WorkflowRunInfo?>> = emptyMap()

    @Volatile
    private var pullRequestCache: Map<Pair<String, Int>, Pair<Long, PullRequestDetail?>> = emptyMap()

    @Volatile
    private var commitByShaCache: Map<Pair<String, String>, Pair<Long, CommitInfo?>> = emptyMap()

    @Volatile
    private var workflowIdsCache: Map<String, Pair<Long, Map<String, String>>> = emptyMap()

    @Volatile
    private var latestRunForWorkflowCache: Map<Triple<String, String, String>, Pair<Long, WorkflowRunInfo?>> = emptyMap()

    /** Laatste run per workflow-naam voor [slug] ("owner/repo"); leeg bij geen workflows/fout. */
    fun latestRunsPerWorkflow(slug: String, projectKey: String): List<WorkflowRunInfo> {
        runsCache[slug]?.let { (at, value) -> if (System.currentTimeMillis() - at < RUNS_TTL_MILLIS) return value }
        val runs = sendJsonOrNull("https://api.github.com/repos/$slug/actions/runs?per_page=$PER_PAGE")
            ?.let { parseLatestRunsPerWorkflow(it, slug, projectKey) }
            ?: emptyList()
        runsCache = runsCache + (slug to (System.currentTimeMillis() to runs))
        return runs
    }

    /** De default branch (`main`/`master`/...) van [slug], gecached met een langere TTL (verandert zelden). */
    fun defaultBranch(slug: String): String? {
        defaultBranchCache[slug]?.let { (at, value) -> if (System.currentTimeMillis() - at < BRANCH_TTL_MILLIS) return value }
        val branch = sendJsonOrNull("https://api.github.com/repos/$slug")?.path("default_branch")?.asText(null)
        defaultBranchCache = defaultBranchCache + (slug to (System.currentTimeMillis() to branch))
        return branch
    }

    @Volatile
    private var workflowNamesCache: Map<String, Pair<Long, List<String>>> = emptyMap()

    @Volatile
    private var openPullRequestsCache: Map<String, Pair<Long, List<PullRequestInfo>>> = emptyMap()

    /**
     * Alle actieve, geconfigureerde workflow-namen van [slug] (zie `/actions/workflows`) — dit is
     * de complete set, ook workflows die recent niet gedraaid hebben. Nodig voor de branch-timeline
     * (SF: per-branch build-status): zonder deze lijst is "geen run voor deze commit" niet te
     * onderscheiden van "workflow bestaat niet (meer)".
     */
    fun allWorkflowNames(slug: String): List<String> {
        workflowNamesCache[slug]?.let { (at, value) -> if (System.currentTimeMillis() - at < BRANCHES_TTL_MILLIS) return value }
        val names = sendJsonOrNull("https://api.github.com/repos/$slug/actions/workflows?per_page=100")
            ?.let(::parseWorkflowNames)
            ?: emptyList()
        workflowNamesCache = workflowNamesCache + (slug to (System.currentTimeMillis() to names))
        return names
    }

    /**
     * Alle workflow-runs op precies commit [sha] van [slug] (elke workflow, niet gecollapst tot één
     * per naam — dat gebeurt bij het samenstellen van de dots, zie `DashboardQueryService.dotsFor`).
     * Geen cache: dit wordt al lazy (alleen op branch-timeline-uitklap) en per exacte commit aangeroepen.
     */
    fun runsForCommit(slug: String, sha: String, projectKey: String): List<WorkflowRunInfo> =
        sendJsonOrNull("https://api.github.com/repos/$slug/actions/runs?head_sha=$sha&per_page=$PER_PAGE")
            ?.let { parseRunsForCommit(it, slug, projectKey) }
            ?: emptyList()

    /** Open pull requests van [slug], kort gecached (voorkomt dubbele calls bij snel aan/uit-klikken). */
    fun openPullRequests(slug: String): List<PullRequestInfo> {
        openPullRequestsCache[slug]?.let { (at, value) -> if (System.currentTimeMillis() - at < BRANCHES_TTL_MILLIS) return value }
        val prs = sendJsonOrNull("https://api.github.com/repos/$slug/pulls?state=open&per_page=$PER_PAGE")
            ?.let(::parseOpenPullRequests)
            ?: emptyList()
        openPullRequestsCache = openPullRequestsCache + (slug to (System.currentTimeMillis() to prs))
        return prs
    }

    /** Laatste commit op [branch] van [slug] (sha, boodschap, datum) in één call, of null bij een lege/onbekende branch. */
    fun latestCommitOn(slug: String, branch: String): CommitInfo? =
        sendJsonOrNull("https://api.github.com/repos/$slug/commits?sha=$branch&per_page=1")
            ?.let(::parseLatestCommit)

    /**
     * Commit-historie op [branch] van [slug], [perPage] commits vanaf [page] (1-based) — voor de
     * Builds-tab (project → branch → laatste N commits). Geen cache: elke paginaklik ("Meer") moet
     * een verse pagina ophalen.
     */
    fun commitsOn(slug: String, branch: String, page: Int, perPage: Int): List<CommitInfo> =
        sendJsonOrNull("https://api.github.com/repos/$slug/commits?sha=$branch&page=$page&per_page=$perPage")
            ?.let(::parseCommitsList)
            ?: emptyList()

    /**
     * Laatst afgeronde 'push'-run op [branch] van [slug], over alle workflows heen — rechtstreeks via
     * GitHub's eigen `branch`/`event`/`status`-filters, i.p.v. client-side filteren binnen de ene
     * pagina recente runs van [latestRunsPerWorkflow]. Nodig omdat elke automatische image-bump-PR
     * (zie de `automation/image-bump-*`-branches) een eigen "Repository verification"-run triggert;
     * na een reeks van dit soort merges kan dat de échte laatste main-build makkelijk uit dat
     * ene-pagina-venster verdringen, waardoor de sync-badge onterecht "Geen productieversie
     * beschikbaar" toont ook al draait er wél een bekende, recentere versie (zie
     * `DashboardQueryService.lastCompletedMainRun`, dat hiermee wordt gevoed i.p.v. alleen met
     * [latestRunsPerWorkflow]'s resultaat).
     */
    fun latestCompletedPushRun(slug: String, branch: String, projectKey: String): WorkflowRunInfo? {
        val cacheKey = slug to branch
        lastCompletedPushRunCache[cacheKey]?.let { (at, value) -> if (System.currentTimeMillis() - at < RUNS_TTL_MILLIS) return value }
        val run = sendJsonOrNull(
            "https://api.github.com/repos/$slug/actions/runs?branch=$branch&event=push&status=completed&per_page=1",
        )?.let { parseRunsForCommit(it, slug, projectKey) }?.firstOrNull()
        lastCompletedPushRunCache = lastCompletedPushRunCache + (cacheKey to (System.currentTimeMillis() to run))
        return run
    }

    /**
     * Laatst afgeronde 'push'-run van precies workflow [workflowName] op [branch] van [slug] —
     * per-component variant van [latestCompletedPushRun]: die laatste is workflow-onafhankelijk (de
     * laatste push-run van WELKE workflow dan ook op main). In een monorepo met meerdere
     * path-filtered workflows (bv. meerdere apps/componenten in één project, zie
     * `LiveComponentConfig.workflowName`/`ApkPackageMapping.workflowName`) laat dat een component
     * onterecht "loopt achter" zien zodra een ANDER component een main-build triggert. Vraagt daarom
     * GitHub's per-workflow-runs-endpoint op (i.p.v. het gedeelde `/actions/runs` client-side te
     * filteren op naam — die is niet gegarandeerd uniek en het endpoint zelf filtert betrouwbaarder
     * op de exacte workflow), zodat elk component alleen tegen zijn EIGEN laatste build vergeleken
     * wordt. Onbekende workflow-naam (niet gevonden bij [workflowIdFor]) → null, geen fout.
     */
    fun latestCompletedRunForWorkflow(slug: String, workflowName: String, branch: String, projectKey: String): WorkflowRunInfo? {
        val workflowId = workflowIdFor(slug, workflowName) ?: return null
        val cacheKey = Triple(slug, workflowId, branch)
        latestRunForWorkflowCache[cacheKey]?.let { (at, value) -> if (System.currentTimeMillis() - at < RUNS_TTL_MILLIS) return value }
        val run = sendJsonOrNull(
            "https://api.github.com/repos/$slug/actions/workflows/$workflowId/runs?branch=$branch&event=push&status=completed&per_page=1",
        )?.let { parseRunsForCommit(it, slug, projectKey) }?.firstOrNull()
        latestRunForWorkflowCache = latestRunForWorkflowCache + (cacheKey to (System.currentTimeMillis() to run))
        return run
    }

    /** GitHub's numerieke workflow-id voor [workflowName] van [slug], of null als die naam niet bestaat. */
    private fun workflowIdFor(slug: String, workflowName: String): String? {
        workflowIdsCache[slug]?.let { (at, value) -> if (System.currentTimeMillis() - at < BRANCHES_TTL_MILLIS) return value[workflowName] }
        val ids = sendJsonOrNull("https://api.github.com/repos/$slug/actions/workflows?per_page=100")
            ?.let(::parseWorkflowIdsByName)
            ?: emptyMap()
        workflowIdsCache = workflowIdsCache + (slug to (System.currentTimeMillis() to ids))
        return ids[workflowName]
    }

    /**
     * Eén pull request opgezocht via z'n nummer — werkt, i.t.t. [openPullRequests], ook nog nadat
     * de PR gemerged of gesloten is (GitHub's "get a pull request"-endpoint kent geen `state`-filter).
     * Nodig voor de Buildstraat-pagina: als de story-branch al weg is, is dit de enige manier om nog
     * het `merge_commit_sha` te achterhalen en zo de build-/deploystatus van dát exacte commit te
     * tonen i.p.v. van de inmiddels doorgeschoven main-tip.
     */
    fun pullRequestByNumber(slug: String, number: Int): PullRequestDetail? {
        val cacheKey = slug to number
        pullRequestCache[cacheKey]?.let { (at, value) -> if (System.currentTimeMillis() - at < BRANCHES_TTL_MILLIS) return value }
        val pr = sendJsonOrNull("https://api.github.com/repos/$slug/pulls/$number")?.let(::parsePullRequestDetail)
        pullRequestCache = pullRequestCache + (cacheKey to (System.currentTimeMillis() to pr))
        return pr
    }

    /** Eén commit op exact [sha] van [slug] (sha, boodschap, datum), of null bij een onbekende sha. */
    fun commitBySha(slug: String, sha: String): CommitInfo? {
        val cacheKey = slug to sha
        commitByShaCache[cacheKey]?.let { (at, value) -> if (System.currentTimeMillis() - at < RUNS_TTL_MILLIS) return value }
        val commit = sendJsonOrNull("https://api.github.com/repos/$slug/commits/$sha")?.let(::parseCommitInfo)
        commitByShaCache = commitByShaCache + (cacheKey to (System.currentTimeMillis() to commit))
        return commit
    }

    private fun sendJsonOrNull(url: String): JsonNode? =
        runCatching {
            val request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer ${secrets.githubToken}")
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching null
            objectMapper.readTree(response.body())
        }.getOrNull()

    internal companion object {
        private const val RUNS_TTL_MILLIS = 30_000L
        private const val BRANCH_TTL_MILLIS = 300_000L
        // Kort gecached (i.t.t. RUNS_TTL_MILLIS): dit is al lazy/on-demand (branch-timeline-uitklap),
        // deze TTL beschermt alleen tegen dubbele calls bij snel aan/uit-klikken door één gebruiker.
        private const val BRANCHES_TTL_MILLIS = 15_000L
        private const val PER_PAGE = 30

        /**
         * Groepeert de `workflow_runs` van de GitHub Actions "list runs"-response per workflow-naam
         * en houdt per workflow alleen de meest recente run over (op `run_started_at`). Puur/testbaar
         * zonder HTTP.
         */
        internal fun parseLatestRunsPerWorkflow(body: JsonNode, slug: String, projectKey: String): List<WorkflowRunInfo> =
            body.path("workflow_runs")
                .filter { it.path("name").asText("").isNotBlank() }
                .groupBy { it.path("name").asText("") }
                .values
                .mapNotNull { runsForWorkflow ->
                    // Geeft de voorkeur aan de meest recente run met een echte conclusie. Workflows die
                    // op `workflow_run` reageren (bv. de image-builds die wachten op "Repository
                    // verification") vuren ook af voor PR-branches, waar hun eigen `if:`-conditie de run
                    // laat "skippen" — zo'n skip is vaak recenter dan de laatste echte main-build en zou
                    // die anders verdringen. Alleen als ALLE runs in dit venster skipped/cancelled zijn
                    // (nooit een echte run gezien), toon je alsnog die skip i.p.v. niets.
                    val meaningful = runsForWorkflow.filter {
                        it.path("conclusion").asText("") !in setOf("skipped", "cancelled")
                    }
                    (meaningful.ifEmpty { runsForWorkflow }).maxByOrNull { it.path("run_started_at").asText("") }
                }
                .map { toWorkflowRunInfo(slug, projectKey, it) }
                .sortedBy { it.workflowName.lowercase() }

        /** Namen van alle actieve workflows uit de `/actions/workflows`-response. Puur/testbaar zonder HTTP. */
        internal fun parseWorkflowNames(body: JsonNode): List<String> =
            body.path("workflows")
                .filter { it.path("state").asText("") == "active" }
                .map { it.path("name").asText("") }
                .filter { it.isNotBlank() }

        /**
         * Workflow-naam → GitHub-id uit dezelfde `/actions/workflows`-response als [parseWorkflowNames]
         * (aparte parser, want [latestCompletedRunForWorkflow] heeft het id nodig voor GitHub's
         * per-workflow-runs-endpoint, dat [parseWorkflowNames] bewust weggooit). Puur/testbaar zonder HTTP.
         */
        internal fun parseWorkflowIdsByName(body: JsonNode): Map<String, String> =
            body.path("workflows")
                .filter { it.path("state").asText("") == "active" }
                .mapNotNull { workflow ->
                    val name = workflow.path("name").asText("")
                    val id = workflow.path("id").asText("")
                    if (name.isBlank() || id.isBlank()) null else name to id
                }
                .toMap()

        /**
         * Alle runs uit een `/actions/runs?head_sha=...`-response, NIET gecollapst tot één per
         * workflow (i.t.t. [parseLatestRunsPerWorkflow]) — de aanroeper (branch-timeline) heeft de
         * volledige lijst nodig om per workflow te kunnen zien of er wel/geen run voor deze exacte
         * commit bestaat. Puur/testbaar zonder HTTP.
         */
        internal fun parseRunsForCommit(body: JsonNode, slug: String, projectKey: String): List<WorkflowRunInfo> =
            body.path("workflow_runs")
                .filter { it.path("name").asText("").isNotBlank() }
                .map { toWorkflowRunInfo(slug, projectKey, it) }

        /**
         * Open pull requests uit een `/pulls?state=open`-response (top-level JSON-array, i.t.t. de
         * andere endpoints hierboven die een object teruggeven). Puur/testbaar zonder HTTP.
         */
        internal fun parseOpenPullRequests(body: JsonNode): List<PullRequestInfo> =
            body.filter { it.path("number").asInt(0) > 0 }
                .map { pr ->
                    PullRequestInfo(
                        number = pr.path("number").asInt(),
                        headRef = pr.path("head").path("ref").asText(""),
                        headSha = pr.path("head").path("sha").asText(""),
                        htmlUrl = pr.path("html_url").asText(""),
                        updatedAt = pr.path("updated_at").asText(null),
                        title = pr.path("title").asText(""),
                    )
                }
                .filter { it.headSha.isNotBlank() }

        /**
         * Laatste commit uit een `/commits?sha=...&per_page=1`-response (top-level JSON-array met
         * hooguit één element). Puur/testbaar zonder HTTP.
         */
        internal fun parseLatestCommit(body: JsonNode): CommitInfo? = body.firstOrNull()?.let(::commitInfoFrom)

        /**
         * Alle commits uit een `/commits?sha=...&page=...&per_page=...`-response (top-level
         * JSON-array), voor de Builds-tab commit-historie. Puur/testbaar zonder HTTP.
         */
        internal fun parseCommitsList(body: JsonNode): List<CommitInfo> = body.mapNotNull(::commitInfoFrom)

        /** Gedeelde velden-extractie voor één commit-JSON-node, gebruikt door alle drie de commit-parsers hierboven/onder. */
        private fun commitInfoFrom(node: JsonNode): CommitInfo? {
            val sha = node.path("sha").asText("")
            if (sha.isBlank()) return null
            return CommitInfo(
                sha = sha,
                message = node.path("commit").path("message").asText(""),
                date = node.path("commit").path("author").path("date").asText(null),
            )
        }

        /**
         * Eén PR uit een `/pulls/{number}`-response (top-level object, i.t.t. [parseOpenPullRequests]'
         * array). `merged`/`merge_commit_sha` zijn hier wél beschikbaar (ontbreken op de open-PR-lijst-
         * respons) — precies waarom deze aparte parser bestaat. Puur/testbaar zonder HTTP.
         */
        internal fun parsePullRequestDetail(body: JsonNode): PullRequestDetail? {
            val number = body.path("number").asInt(0)
            if (number <= 0) return null
            return PullRequestDetail(
                number = number,
                headRef = body.path("head").path("ref").asText(""),
                htmlUrl = body.path("html_url").asText(""),
                title = body.path("title").asText(""),
                merged = body.path("merged").asBoolean(false),
                mergeCommitSha = body.path("merge_commit_sha").asText(null)?.takeIf { it.isNotBlank() },
            )
        }

        /**
         * Eén commit uit een `/commits/{sha}`-response (top-level object, i.t.t. [parseLatestCommit]'
         * array-van-hooguit-één voor `/commits?sha=<branch>`). Zelfde velden, andere top-level vorm.
         * Puur/testbaar zonder HTTP.
         */
        internal fun parseCommitInfo(body: JsonNode): CommitInfo? = commitInfoFrom(body)

        private fun toWorkflowRunInfo(slug: String, projectKey: String, run: JsonNode): WorkflowRunInfo {
            val startedAt = run.path("run_started_at").asText(null) ?: run.path("created_at").asText(null)
            val updatedAt = run.path("updated_at").asText(null)
            return WorkflowRunInfo(
                repository = slug,
                projectKey = projectKey,
                workflowName = run.path("name").asText(""),
                status = run.path("status").asText(""),
                conclusion = run.path("conclusion").asText(null),
                branch = run.path("head_branch").asText(""),
                event = run.path("event").asText(""),
                durationSeconds = durationSeconds(startedAt, updatedAt),
                updatedAt = updatedAt,
                htmlUrl = run.path("html_url").asText(""),
                headSha = run.path("head_sha").asText(""),
                runStartedAt = startedAt,
            )
        }

        private fun durationSeconds(startedAt: String?, updatedAt: String?): Long? {
            if (startedAt.isNullOrBlank() || updatedAt.isNullOrBlank()) return null
            return try {
                Duration.between(Instant.parse(startedAt), Instant.parse(updatedAt)).seconds.coerceAtLeast(0)
            } catch (ex: DateTimeParseException) {
                null
            }
        }
    }
}
