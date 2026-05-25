package nl.vdzon.softwarefactory.dashboard.github

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.dashboard.api.DownloadDto
import nl.vdzon.softwarefactory.dashboard.api.WorkflowDto
import nl.vdzon.softwarefactory.dashboard.api.WorkflowRunDto
import nl.vdzon.softwarefactory.dashboard.config.DashboardSecrets
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

@Component
class GitHubClient(
    private val secrets: DashboardSecrets,
) {
    private val objectMapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newHttpClient()

    fun repository(slug: String): GitHubRepositoryInfo? =
        sendJsonOrNull("GET", "/repos/$slug")?.let {
            GitHubRepositoryInfo(
                slug = slug,
                defaultBranch = it.path("default_branch").asText(null),
                htmlUrl = it.path("html_url").asText(null),
            )
        }

    fun workflows(slug: String): List<WorkflowDto> {
        val root = sendJsonOrNull("GET", "/repos/$slug/actions/workflows", listOf("per_page" to "100")) ?: return emptyList()
        return root.path("workflows").map {
            WorkflowDto(
                id = it.path("id").asLong(0),
                name = it.path("name").asText(""),
                path = it.path("path").asText(null),
                state = it.path("state").asText(null),
                url = it.path("html_url").asText(null),
            )
        }.filter { it.id > 0 }
    }

    fun runs(slug: String, limit: Int = 20): List<WorkflowRunDto> {
        val root = sendJsonOrNull("GET", "/repos/$slug/actions/runs", listOf("per_page" to limit.coerceIn(1, 100).toString()))
            ?: return emptyList()
        return root.path("workflow_runs").map { it.toRun() }.filter { it.id > 0 }
    }

    fun latestReleaseDownloads(slug: String, projectKey: String? = null): List<DownloadDto> {
        val release = sendJsonOrNull("GET", "/repos/$slug/releases/latest") ?: return emptyList()
        val releaseTag = release.path("tag_name").asText(null)
        val releaseUrl = release.path("html_url").asText(null)
        val publishedAt = release.path("published_at").asText(null) ?: release.path("created_at").asText(null)
        return release.path("assets")
            .filter { it.path("name").asText("").endsWith(".apk", ignoreCase = true) }
            .map {
                DownloadDto(
                    repository = slug,
                    projectKey = projectKey,
                    name = it.path("name").asText(""),
                    size = it.path("size").asLong(0),
                    createdAt = it.path("created_at").asText(null) ?: publishedAt,
                    downloadUrl = it.path("browser_download_url").asText(""),
                    releaseTag = releaseTag,
                    releaseUrl = releaseUrl,
                )
            }
    }

    private fun JsonNode.toRun(): WorkflowRunDto =
        WorkflowRunDto(
            id = path("id").asLong(0),
            name = path("name").asText(null),
            displayTitle = path("display_title").asText(null),
            status = path("status").asText(null),
            conclusion = path("conclusion").asText(null),
            event = path("event").asText(null),
            headBranch = path("head_branch").asText(null),
            headSha = path("head_sha").asText(null),
            createdAt = path("created_at").asText(null),
            updatedAt = path("updated_at").asText(null),
            runStartedAt = path("run_started_at").asText(null),
            url = path("html_url").asText(null),
        )

    private fun sendJsonOrNull(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
    ): JsonNode? {
        val response = httpClient.send(request(method, path, query), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 404) return null
        if (response.statusCode() !in 200..299) return null
        return if (response.body().isNullOrBlank()) objectMapper.createObjectNode() else objectMapper.readTree(response.body())
    }

    private fun request(method: String, path: String, query: List<Pair<String, String>>): HttpRequest {
        val builder = HttpRequest.newBuilder(URI.create("https://api.github.com$path${query.toQueryString()}"))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer ${secrets.githubToken}")
        return builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
    }

    private fun List<Pair<String, String>>.toQueryString(): String =
        if (isEmpty()) "" else joinToString(prefix = "?", separator = "&") { (key, value) ->
            "${key.urlEncoded()}=${value.urlEncoded()}"
        }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)
}

data class GitHubRepositoryInfo(
    val slug: String,
    val defaultBranch: String?,
    val htmlUrl: String?,
)

object GitHubSlug {
    fun fromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim().trim('<', '>').removeSuffix(".git").trimEnd('/')
        Regex("""^https://github\.com/([^/]+/[^/]+)$""").find(trimmed)?.let { return it.groupValues[1] }
        Regex("""^git@github\.com:([^/]+/.+)$""").find(trimmed)?.let { return it.groupValues[1].removeSuffix(".git") }
        return null
    }
}
