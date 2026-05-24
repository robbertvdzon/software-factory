package nl.vdzon.softwarefactory.agentworker.flows

import nl.vdzon.softwarefactory.agentworker.flows.TargetRepositorySession
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.support.SupportApi
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class TesterPreviewContext(
    val previewUrl: String?,
    val previewNamespace: String?,
    val previewDbUrl: String?,
) {
    fun toMarkdown(): String =
        buildString {
            appendLine("## Preview Environment")
            appendLine()
            appendLine("- SF_PREVIEW_URL: `${previewUrl ?: ""}`")
            appendLine("- SF_PREVIEW_NAMESPACE: `${previewNamespace ?: ""}`")
            appendLine("- SF_PREVIEW_DB_URL: `${if (previewDbUrl.isNullOrBlank()) "" else "<resolved>"}`")
        }
}

class TesterPreviewFlow(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val git: GitApi = GitApi.default(),
    private val clock: Clock = Clock.systemUTC(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    fun prepare(env: Map<String, String>, session: TargetRepositorySession): TesterPreviewContext {
        val prNumber = env["SF_PR_NUMBER"]?.toIntOrNull()
        val previewUrl = env["SF_PREVIEW_URL"]
            ?: PreviewApi.renderTemplate(session.deploymentConfig.previewUrlTemplate, prNumber)
        val previewNamespace = env["SF_PREVIEW_NAMESPACE"]
            ?: PreviewApi.renderTemplate(session.deploymentConfig.previewNamespaceTemplate, prNumber)

        if (env["SF_SKIP_PREVIEW_WAIT"]?.toBooleanStrictOrNull() != true && !previewUrl.isNullOrBlank()) {
            waitForHttp200(
                previewUrl = previewUrl,
                timeout = Duration.ofSeconds(env["SF_PREVIEW_WAIT_TIMEOUT_SECONDS"]?.toLongOrNull() ?: 600L),
                interval = Duration.ofSeconds(env["SF_PREVIEW_WAIT_INTERVAL_SECONDS"]?.toLongOrNull() ?: 15L),
            )
        }

        val previewDbUrl = env["SF_PREVIEW_DB_URL"]?.takeIf { it.isNotBlank() }
            ?: resolvePreviewDbUrl(session, previewNamespace)

        return TesterPreviewContext(
            previewUrl = previewUrl,
            previewNamespace = previewNamespace,
            previewDbUrl = previewDbUrl,
        )
    }

    private fun waitForHttp200(previewUrl: String, timeout: Duration, interval: Duration) {
        val deadline = Instant.now(clock).plus(timeout)
        while (Instant.now(clock).isBefore(deadline)) {
            val status = runCatching {
                httpClient.send(
                    HttpRequest.newBuilder(URI.create(previewUrl)).GET().build(),
                    HttpResponse.BodyHandlers.discarding(),
                ).statusCode()
            }.getOrNull()
            if (status == 200) {
                return
            }
            sleep(interval.toMillis())
        }
        throw PreviewWaitException("Preview URL did not return HTTP 200 within ${timeout.toSeconds()}s: $previewUrl")
    }

    private fun resolvePreviewDbUrl(session: TargetRepositorySession, previewNamespace: String?): String? {
        val recipe = session.deploymentConfig.previewDbSecretRecipe?.takeIf { it.isNotBlank() } ?: return null
        val renderedRecipe = recipe.replace("{preview_namespace}", previewNamespace.orEmpty())
        val result = git.runCommand(
            command = listOf("bash", "-lc", renderedRecipe),
            cwd = session.repoRoot,
            timeoutSeconds = 120,
        )
        if (result.exitCode != 0) {
            throw PreviewWaitException("Preview DB secret recipe failed: ${SupportApi.default().redact(result.output).take(1000)}")
        }
        return result.stdout.trim().takeIf { it.isNotBlank() }
    }
}

class PreviewWaitException(message: String) : RuntimeException(message)
