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
import java.util.Base64

/**
 * De image-tags die deze ronde niet weg mogen, plus of die lijst compleet kon worden opgehaald.
 * Is [complete] `false`, dan is de lijst niet te vertrouwen als beschermingslijst.
 */
data class ProtectedTags(val tags: Set<String>, val complete: Boolean)

/**
 * Leest welke `sha-*`-image-tags voor [GitHubProtectedShaSource.protectedTags] nog in gebruik zijn: elke sha die voorkomt in
 * de geconfigureerde manifest-bestanden (huidige productie/preview-deploy-doelen, bv.
 * `deploy/base/kustomization.yaml`) én de head-sha van elke open pull request (preview-deploys, zie
 * build-images.yml's PR-trigger — zonder deze bescherming zou een lopende preview-PR een
 * ImagePullBackOff krijgen zodra z'n image wordt opgeruimd). Read-only, gebruikt het gedeelde
 * [FactorySecrets.githubToken] (geen `delete:packages` nodig).
 */
// `open` puur voor testbaarheid: er is geen mock-framework in deze repo, dus
// MaintenanceCleanupSchedulerTest zet er een handgeschreven subklasse-fake voor in de plaats.
@Component
open class GitHubProtectedShaSource(
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

    /**
     * De beschermde tags plus of die lijst compleet is. Anders dan bij de andere clients is een
     * onvolledig resultaat hier gevaarlijk: dit is een *veiligheids*lijst, dus een ontbrekende
     * open-PR-pagina zou beschermde images alsnog laten verwijderen. De aanroeper slaat de
     * package-cleanup voor dat project dan over (zie [MaintenanceCleanupScheduler]).
     */
    open fun protectedTags(slug: String, manifestPaths: List<String>): ProtectedTags {
        val manifestShas = manifestPaths.flatMap { path -> extractShaTags(contentsOf(slug, path).orEmpty()) }
        val pulls = openPullRequestHeadShas(slug)
        GitHubPagination.warnIfIncomplete(logger, "open pull requests van $slug", pulls)
        return ProtectedTags(
            tags = (manifestShas + pulls.items.map(::shortShaTag)).toSet(),
            complete = pulls.complete,
        )
    }

    private fun contentsOf(slug: String, path: String): String? =
        sendJsonOrNull("https://api.github.com/repos/$slug/contents/$path")?.let(::decodeContent)

    /** De `contents`-call blijft ongepagineerd (geen lijst); alleen `/pulls` doorloopt alle pagina's. */
    internal fun openPullRequestHeadShas(slug: String): PagedItems<String> =
        openPullRequestHeadShasWith { page ->
            val url = "https://api.github.com/repos/$slug/pulls" +
                "?state=open&per_page=${GitHubPagination.PER_PAGE}&page=$page"
            sendJsonOrNull(url)
                ?.let { GitHubPage.Fetched(parseOpenPullRequestHeadShas(it), it.size()) }
                ?: GitHubPage.Failed
        }

    /** Test-seam op [openPullRequestHeadShas]: dezelfde paginatielus met een injecteerbare paginafunctie. */
    internal fun openPullRequestHeadShasWith(fetchPage: (page: Int) -> GitHubPage<String>): PagedItems<String> =
        GitHubPagination.fetchAllPages(settings.githubPageLimit, fetchPage = fetchPage)

    private fun sendJsonOrNull(url: String): JsonNode? =
        runCatching {
            val request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer ${secrets.githubToken}")
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching null
            objectMapper.readTree(response.body())
        }.onFailure { logger.warn("GitHub contents/pulls-call faalde voor {}.", url, it) }
            .getOrNull()

    internal companion object {
        /** Maximale wachttijd per HTTP-call; zonder deze grens kan `send` eindeloos blijven wachten (SF-2059). */
        private val HTTP_TIMEOUT: Duration = Duration.ofSeconds(10)

        private val SHA_TAG_PATTERN = Regex("sha-[0-9a-f]{7,}")

        /** Base64-gedecodeerde inhoud uit een GitHub "contents"-response, of null bij een directory/fout. Puur/testbaar zonder HTTP. */
        internal fun decodeContent(body: JsonNode): String? {
            val encoded = body.path("content").asText(null)?.replace("\n", "") ?: return null
            return runCatching { String(Base64.getDecoder().decode(encoded)) }.getOrNull()
        }

        /** Alle `sha-<kort>`-vermeldingen in tekst (bv. een kustomization.yaml). Puur/testbaar zonder HTTP. */
        internal fun extractShaTags(content: String): Set<String> =
            SHA_TAG_PATTERN.findAll(content).map { it.value }.toSet()

        /** Head-sha's van open pull requests uit een `/pulls?state=open`-response (top-level array). Puur/testbaar zonder HTTP. */
        internal fun parseOpenPullRequestHeadShas(body: JsonNode): List<String> =
            body.mapNotNull { it.path("head").path("sha").asText(null) }.filter { it.isNotBlank() }

        /** `sha-<eerste 7 tekens>`, hetzelfde formaat als build-images.yml's image-tags. Puur/testbaar zonder HTTP. */
        internal fun shortShaTag(sha: String): String = "sha-${sha.take(7)}"
    }
}
