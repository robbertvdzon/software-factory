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
import java.util.Base64

/**
 * Leest welke `sha-*`-image-tags voor [protectedTags] nog in gebruik zijn: elke sha die voorkomt in
 * de geconfigureerde manifest-bestanden (huidige productie/preview-deploy-doelen, bv.
 * `deploy/base/kustomization.yaml`) én de head-sha van elke open pull request (preview-deploys, zie
 * build-images.yml's PR-trigger — zonder deze bescherming zou een lopende preview-PR een
 * ImagePullBackOff krijgen zodra z'n image wordt opgeruimd). Read-only, gebruikt het gedeelde
 * [FactorySecrets.githubToken] (geen `delete:packages` nodig).
 */
@Component
class GitHubProtectedShaSource(
    private val secrets: FactorySecrets,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    private val objectMapper = jacksonObjectMapper()
    private val logger = LoggerFactory.getLogger(javaClass)

    fun protectedTags(slug: String, manifestPaths: List<String>): Set<String> {
        val manifestShas = manifestPaths.flatMap { path -> extractShaTags(contentsOf(slug, path).orEmpty()) }
        val prShas = openPullRequestHeadShas(slug).map(::shortShaTag)
        return (manifestShas + prShas).toSet()
    }

    private fun contentsOf(slug: String, path: String): String? =
        sendJsonOrNull("https://api.github.com/repos/$slug/contents/$path")?.let(::decodeContent)

    private fun openPullRequestHeadShas(slug: String): List<String> =
        sendJsonOrNull("https://api.github.com/repos/$slug/pulls?state=open&per_page=100")
            ?.let(::parseOpenPullRequestHeadShas)
            ?: emptyList()

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
        }.onFailure { logger.warn("GitHub contents/pulls-call faalde voor {}.", url, it) }
            .getOrNull()

    internal companion object {
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
