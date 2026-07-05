package nl.vdzon.softwarefactory.preview

import nl.vdzon.softwarefactory.preview.services.*

import com.sun.net.httpserver.HttpServer
import nl.vdzon.softwarefactory.agentworker.flows.TargetRepositorySession
import nl.vdzon.softwarefactory.agentworker.flows.TesterPreviewFlow
import nl.vdzon.softwarefactory.core.DeploymentConfig
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.git.GitProcessResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.file.Path

class TesterPreviewFlowTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `renders preview values waits for HTTP 200 and resolves DB recipe`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()

        try {
            val processRunner = FakeProcessRunner()
            val port = server.address.port
            val session = TargetRepositorySession(
                repoRoot = tempDir,
                repoUrl = "git@github.com:robbertvdzon/sample-build-project.git",
                baseBranch = "main",
                branchPrefix = "ai/",
                branchName = "ai/KAN-12",
                deploymentConfig = DeploymentConfig(
                    previewUrlTemplate = "http://127.0.0.1:$port/pr-{pr_num}",
                    previewNamespaceTemplate = "app-pr-{pr_num}",
                    previewDbSecretRecipe = "printf db-url-for-{preview_namespace}",
                ),
            )
            val flow = TesterPreviewFlow(
                httpClient = HttpClient.newHttpClient(),
                git = processRunner,
                sleep = {},
            )

            val context = flow.prepare(
                env = mapOf(
                    "SF_PR_NUMBER" to "12",
                    "SF_PREVIEW_WAIT_INTERVAL_SECONDS" to "0",
                ),
                session = session,
            )

            assertEquals("http://127.0.0.1:$port/pr-12", context.previewUrl)
            assertEquals("app-pr-12", context.previewNamespace)
            assertEquals("db-url-for-app-pr-12", context.previewDbUrl)
            assertTrue(processRunner.commands.single().contains("printf db-url-for-app-pr-12"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `preview wait timeout message reflects the configured timeout`() {
        val session = TargetRepositorySession(
            repoRoot = tempDir,
            repoUrl = "git@github.com:robbertvdzon/sample-build-project.git",
            baseBranch = "main",
            branchPrefix = "ai/",
            branchName = "ai/KAN-12",
            deploymentConfig = DeploymentConfig(
                previewUrlTemplate = "http://127.0.0.1:0/pr-{pr_num}",
                previewNamespaceTemplate = "app-pr-{pr_num}",
                previewDbSecretRecipe = null,
            ),
        )
        val flow = TesterPreviewFlow(
            httpClient = HttpClient.newHttpClient(),
            git = FakeProcessRunner(),
            sleep = {},
        )

        val ex = org.junit.jupiter.api.Assertions.assertThrows(
            nl.vdzon.softwarefactory.agentworker.flows.PreviewWaitException::class.java,
        ) {
            flow.prepare(
                env = mapOf(
                    "SF_PR_NUMBER" to "12",
                    // Expliciete override: de foutmelding moet exact deze waarde noemen.
                    "SF_PREVIEW_WAIT_TIMEOUT_SECONDS" to "0",
                    "SF_PREVIEW_WAIT_INTERVAL_SECONDS" to "0",
                ),
                session = session,
            )
        }
        assertTrue(ex.message!!.contains("within 0s"), "verwachtte de werkelijke timeout in de melding: ${ex.message}")
    }

    private class FakeProcessRunner : GitApi {
        val commands = mutableListOf<List<String>>()

        override fun runCommand(command: List<String>, cwd: Path?, env: Map<String, String>, timeoutSeconds: Long): GitProcessResult {
            commands += command
            return GitProcessResult(0, command.last().removePrefix("printf "), "")
        }

        override fun repositorySlug(repoUrl: String): String? = null
        override fun clone(repoUrl: String, targetDir: Path, githubToken: String?) = Unit
        override fun checkoutBase(repoRoot: Path, baseBranch: String, githubToken: String?) = Unit
        override fun checkoutStoryBranch(repoRoot: Path, branchName: String, baseBranch: String, createIfMissing: Boolean, githubToken: String?) = Unit
        override fun commitAll(repoRoot: Path, message: String, githubToken: String?): Boolean = true
        override fun push(repoRoot: Path, branchName: String, githubToken: String?) = Unit
        override fun remoteBranchExists(repoRoot: Path, branchName: String, githubToken: String?): Boolean = false
    }
}
