package nl.vdzon.softwarefactory.web.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.web.models.WorkflowRunInfo
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
                .mapNotNull { runsForWorkflow -> runsForWorkflow.maxByOrNull { it.path("run_started_at").asText("") } }
                .map { toWorkflowRunInfo(slug, projectKey, it) }
                .sortedBy { it.workflowName.lowercase() }

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
