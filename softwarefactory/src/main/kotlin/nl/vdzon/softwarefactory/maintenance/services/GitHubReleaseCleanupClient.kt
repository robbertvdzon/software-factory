package nl.vdzon.softwarefactory.maintenance.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Eén GitHub Release: genoeg om 'm te kunnen groeperen/sorteren (zie [ReleaseRetentionPlanner]) en te verwijderen. */
data class ReleaseInfo(val id: Long, val tagName: String, val publishedAt: String?)

/**
 * Read/delete GitHub-Releases-client voor [MaintenanceCleanupScheduler] — zelfde recept als
 * [nl.vdzon.softwarefactory.dashboard.services.GitHubReleaseClient] (raw `HttpClient`, Jackson, pure
 * parsing in de companion object), maar dan met write-operaties via het gedeelde [FactorySecrets.githubToken]
 * (repo-scope volstaat voor het verwijderen van releases + tag-refs).
 */
// `open` puur voor testbaarheid: er is geen mock-framework in deze repo, dus
// MaintenanceCleanupSchedulerTest zet er een handgeschreven subklasse-fake voor in de plaats.
@Component
open class GitHubReleaseCleanupClient(
    private val secrets: FactorySecrets,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(HTTP_TIMEOUT)
        .build(),
    // Default zodat de handgeschreven fakes in MaintenanceCleanupSchedulerTest positioneel
    // (1-arg) kunnen blijven construeren.
    private val settings: MaintenanceCleanupSettings = MaintenanceCleanupSettings(),
) {
    private val objectMapper = jacksonObjectMapper()
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Alle releases van [slug] ("owner/repo"), over alle pagina's heen; leeg bij geen releases/fout op pagina 1. */
    open fun listReleases(slug: String): List<ReleaseInfo> =
        listReleasesWith(slug) { page ->
            val url = "https://api.github.com/repos/$slug/releases?per_page=${GitHubPagination.PER_PAGE}&page=$page"
            sendJsonOrNull(url)
                ?.let { GitHubPage.Fetched(parseReleases(it), it.size()) }
                ?: GitHubPage.Failed
        }

    /** Test-seam op [listReleases]: dezelfde paginatielus met een injecteerbare "haal pagina n op"-functie. */
    internal fun listReleasesWith(slug: String, fetchPage: (page: Int) -> GitHubPage<ReleaseInfo>): List<ReleaseInfo> {
        val paged = GitHubPagination.fetchAllPages(settings.githubPageLimit, fetchPage = fetchPage)
        GitHubPagination.warnIfIncomplete(logger, "releases van $slug", paged)
        return paged.items
    }

    /** Verwijdert release [id] en de bijbehorende git-tag [tagName] van [slug]; best-effort per stap. */
    open fun deleteRelease(slug: String, id: Long, tagName: String) {
        delete("https://api.github.com/repos/$slug/releases/$id")
        delete("https://api.github.com/repos/$slug/git/refs/tags/$tagName")
    }

    private fun delete(url: String) {
        runCatching {
            val request = authorizedRequest(url).DELETE().build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299 && response.statusCode() != 404) {
                logger.warn("GitHub DELETE {} gaf status {}: {}", url, response.statusCode(), response.body().take(300))
            }
        }.onFailure { logger.warn("GitHub DELETE {} faalde.", url, it) }
    }

    private fun sendJsonOrNull(url: String): JsonNode? =
        runCatching {
            val request = authorizedRequest(url).GET().build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching null
            objectMapper.readTree(response.body())
        }.getOrNull()

    private fun authorizedRequest(url: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer ${secrets.githubToken}")
            .timeout(HTTP_TIMEOUT)

    internal companion object {
        /** Maximale wachttijd per HTTP-call; zonder deze grens kan `send` eindeloos blijven wachten (SF-2059). */
        private val HTTP_TIMEOUT: Duration = Duration.ofSeconds(10)

        /** Parseert een `/repos/{slug}/releases`-response (top-level array). Puur/testbaar zonder HTTP. */
        internal fun parseReleases(body: JsonNode): List<ReleaseInfo> =
            body.mapNotNull { release ->
                val id = release.path("id").asLong(0)
                val tag = release.path("tag_name").asText(null)
                if (id <= 0 || tag.isNullOrBlank()) return@mapNotNull null
                ReleaseInfo(
                    id = id,
                    tagName = tag,
                    publishedAt = release.path("published_at").asText(null) ?: release.path("created_at").asText(null),
                )
            }
    }
}
