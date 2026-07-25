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

/** Eén ghcr.io-package-version: genoeg om 'm te kunnen bewaren/verwijderen (zie [PackageVersionRetentionPlanner]). */
data class PackageVersionInfo(val id: Long, val createdAt: String?, val tags: List<String>)

/**
 * Read/delete ghcr.io-container-package-client voor een personal (niet-org) GitHub-account. Auth
 * via [FactorySecrets.githubPackagesToken] — een apart, minimaal gescopeerd PAT (zie FactorySecrets),
 * want [FactorySecrets.githubToken] heeft doorgaans geen `delete:packages`. Ontbreekt dat token, dan
 * geeft [listVersions] een lege lijst (eenmalig gelogd) en doet [deleteVersion] niets: de aanroeper
 * slaat package-cleanup voor dat project dan simpelweg over, zonder te crashen.
 */
@Component
class GitHubPackageCleanupClient(
    private val secrets: FactorySecrets,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    private val objectMapper = jacksonObjectMapper()
    private val logger = LoggerFactory.getLogger(javaClass)

    fun listVersions(owner: String, packageName: String): List<PackageVersionInfo> {
        val token = secrets.githubPackagesToken
        if (token.isNullOrBlank()) {
            logger.warn("SF_GITHUB_PACKAGES_TOKEN ontbreekt; package-cleanup voor {}/{} overgeslagen.", owner, packageName)
            return emptyList()
        }
        return sendJsonOrNull(
            "https://api.github.com/users/$owner/packages/container/$packageName/versions?per_page=100",
            token,
        )?.let(::parseVersions) ?: emptyList()
    }

    fun deleteVersion(owner: String, packageName: String, id: Long) {
        val token = secrets.githubPackagesToken?.takeIf { it.isNotBlank() } ?: return
        val url = "https://api.github.com/users/$owner/packages/container/$packageName/versions/$id"
        runCatching {
            val request = authorizedRequest(url, token).DELETE().build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299 && response.statusCode() != 404) {
                logger.warn("ghcr DELETE {} gaf status {}: {}", url, response.statusCode(), response.body().take(300))
            }
        }.onFailure { logger.warn("ghcr DELETE {} faalde.", url, it) }
    }

    private fun sendJsonOrNull(url: String, token: String): JsonNode? =
        runCatching {
            val request = authorizedRequest(url, token).GET().build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching null
            objectMapper.readTree(response.body())
        }.getOrNull()

    private fun authorizedRequest(url: String, token: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", "Bearer $token")

    internal companion object {
        /** Parseert een `/packages/container/{name}/versions`-response (top-level array). Puur/testbaar zonder HTTP. */
        internal fun parseVersions(body: JsonNode): List<PackageVersionInfo> =
            body.mapNotNull { version ->
                val id = version.path("id").asLong(0)
                if (id <= 0) return@mapNotNull null
                val tags = version.path("metadata").path("container").path("tags")
                    .map { it.asText("") }
                    .filter { it.isNotBlank() }
                PackageVersionInfo(id, version.path("created_at").asText(null), tags)
            }
    }
}
