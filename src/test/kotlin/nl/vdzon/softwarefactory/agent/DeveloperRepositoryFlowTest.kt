package nl.vdzon.softwarefactory.agent

import nl.vdzon.softwarefactory.git.GitCommandClient
import nl.vdzon.softwarefactory.git.ProcessResult
import nl.vdzon.softwarefactory.git.ProcessRunner
import nl.vdzon.softwarefactory.github.PullRequestClient
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.github.PullRequestInfo
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText

class DeveloperRepositoryFlowTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `dummy developer flow bootstraps docs commits pushes and emits PR metadata`() {
        val runner = FakeProcessRunner { command ->
            if (command == listOf("git", "status", "--porcelain")) {
                ProcessResult(0, " M docs/factory/.dummy-log\n", "")
            } else {
                ProcessResult(0, "", "")
            }
        }
        val pullRequests = FakePullRequestClient()
        val flow = DeveloperRepositoryFlow(
            git = GitCommandClient(runner),
            pullRequests = pullRequests,
            skeletonRoot = Path.of("/missing-skeleton-so-classpath-is-used"),
        )
        val session = TargetRepositorySession(
            repoRoot = tempDir,
            repoUrl = "git@github.com:robbertvdzon/sample-build-project.git",
            baseBranch = "main",
            branchPrefix = "ai/",
            branchName = "ai/KAN-42",
        )

        val result = flow.completeDummyDeveloperRun(session, "KAN-42", "Story body", githubToken = "token")

        assertTrue(tempDir.resolve("docs/factory/README.md").toFile().exists())
        assertTrue(tempDir.resolve("docs/factory/.dummy-log").readText().contains("KAN-42"))
        assertTrue(tempDir.resolve("docs/stories/KAN-42-description.md").readText().contains("[x]: implement requested changes"))
        assertTrue(runner.commands.any { it.take(2) == listOf("git", "commit") })
        assertTrue(runner.commands.any { it.take(2) == listOf("git", "push") })
        assertTrue(pullRequests.created)
        assertTrue(result.completionEvent.payload.contains("\"prNumber\":55"))
    }

    private class FakeProcessRunner(
        private val handler: (List<String>) -> ProcessResult,
    ) : ProcessRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>, cwd: Path?, env: Map<String, String>, timeoutSeconds: Long): ProcessResult {
            commands += command
            return handler(command)
        }
    }

    private class FakePullRequestClient : PullRequestClient {
        var created = false

        override fun ensurePullRequest(
            repoRoot: Path,
            branchName: String,
            baseBranch: String,
            title: String,
            body: String,
        ): PullRequestInfo {
            created = true
            return PullRequestInfo(55, "https://github.example/pr/55")
        }

        override fun isMerged(targetRepo: String, prNumber: Int): Boolean = false

        override fun unprocessedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> = emptyList()

        override fun markCommentClaimed(targetRepo: String, commentId: Long) = Unit
    }
}
