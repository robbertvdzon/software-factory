package nl.vdzon.softwarefactory.web.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.web.models.DownloadInfo
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Kleine GitHub-REST-client voor de bridge-operatie `downloads.list` (zie
 * docs/ontwerp-bridge-dashboard.md §5) — de enige écht nieuwe businesslogica in dit project.
 * Zelfde recept als de verwijderde `dashboard-backend.../github/GitHubClient.kt`
 * (`latestReleaseDownloads`), nu aan de factory-kant met [FactorySecrets.githubToken].
 */
@Component
class GitHubReleaseClient(
    private val secrets: FactorySecrets,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    private val objectMapper = jacksonObjectMapper()

    /** De `.apk`-assets van de laatste release van [slug] ("owner/repo"); leeg bij geen release/fout. */
    fun latestApkDownloads(slug: String, projectKey: String): List<DownloadInfo> {
        val release = sendJsonOrNull(slug) ?: return emptyList()
        val releaseTag = release.path("tag_name").asText(null)
        val releaseUrl = release.path("html_url").asText(null)
        val publishedAt = release.path("published_at").asText(null) ?: release.path("created_at").asText(null)
        return release.path("assets")
            .filter { it.path("name").asText("").endsWith(".apk", ignoreCase = true) }
            .map {
                DownloadInfo(
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

    private fun sendJsonOrNull(slug: String): JsonNode? =
        runCatching {
            val request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/$slug/releases/latest"))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer ${secrets.githubToken}")
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching null
            objectMapper.readTree(response.body())
        }.getOrNull()
}

/** Herleidt "owner/repo" uit een GitHub-repo-URL (https of ssh); null voor niet-GitHub-URL's. */
object GitHubSlug {
    fun fromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim().trim('<', '>').removeSuffix(".git").trimEnd('/')
        Regex("""^https://github\.com/([^/]+/[^/]+)$""").find(trimmed)?.let { return it.groupValues[1] }
        Regex("""^git@github\.com:([^/]+/.+)$""").find(trimmed)?.let { return it.groupValues[1].removeSuffix(".git") }
        return null
    }
}
