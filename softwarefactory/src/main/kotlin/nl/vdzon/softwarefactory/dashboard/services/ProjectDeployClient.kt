package nl.vdzon.softwarefactory.dashboard.services

import nl.vdzon.softwarefactory.config.DeployConfig
import nl.vdzon.softwarefactory.config.ConfigApi
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Kleine REST-client voor de deploy-endpoints van een project ([DeployConfig.RestRestart]):
 * de force-restart-call en de versie-poll van de projectenpagina. Houdt het raw-HttpClient-gebruik
 * uit [DashboardQueryService]; de [HttpClient] is injecteerbaar zodat tests 'm kunnen vervangen.
 */
@Service
class ProjectDeployClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val configApi: ConfigApi = ConfigApi.default(),
) {

    /** Triggert de restart-URL van het project met het Bearer-token uit [DeployConfig.RestRestart.tokenEnvVar]. */
    fun forceRestart(deployConfig: DeployConfig.RestRestart) {
        val token = configApi.resolvedValues()[deployConfig.tokenEnvVar]?.takeIf(String::isNotBlank)
            ?: error("Configkey ${deployConfig.tokenEnvVar} niet ingesteld")
        val request = HttpRequest.newBuilder(URI.create(deployConfig.restartUrl))
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Force-deploy faalde: HTTP ${response.statusCode()}"
        }
    }

    /**
     * De JSON-body van het versie-endpoint van een project, of null bij een niet-2xx of netwerkfout.
     * Soft-fail: de projectenpagina toont dan simpelweg geen prd-versie.
     */
    fun fetchVersionBody(versionUrl: String): String? =
        runCatching {
            val request = HttpRequest.newBuilder(URI.create(versionUrl)).timeout(Duration.ofSeconds(3)).GET().build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) response.body() else null
        }.getOrNull()
}
