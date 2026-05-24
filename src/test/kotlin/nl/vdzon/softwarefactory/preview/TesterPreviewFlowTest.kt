package nl.vdzon.softwarefactory.preview

import com.sun.net.httpserver.HttpServer
import nl.vdzon.softwarefactory.agent.TargetRepositorySession
import nl.vdzon.softwarefactory.agent.flows.TesterPreviewFlow
import nl.vdzon.softwarefactory.docs.DeploymentConfig
import nl.vdzon.softwarefactory.git.ProcessResult
import nl.vdzon.softwarefactory.git.ProcessRunner
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
                processRunner = processRunner,
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

    private class FakeProcessRunner : ProcessRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>, cwd: Path?, env: Map<String, String>, timeoutSeconds: Long): ProcessResult {
            commands += command
            return ProcessResult(0, command.last().removePrefix("printf "), "")
        }
    }
}
