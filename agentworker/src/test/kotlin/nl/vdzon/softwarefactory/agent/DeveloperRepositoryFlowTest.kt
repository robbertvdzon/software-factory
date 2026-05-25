package nl.vdzon.softwarefactory.agent

import nl.vdzon.softwarefactory.agent.*
import nl.vdzon.softwarefactory.agentworker.flows.*

import nl.vdzon.softwarefactory.agentworker.flows.DeveloperRepositoryFlow
import nl.vdzon.softwarefactory.agentworker.flows.TargetRepositorySession
import nl.vdzon.softwarefactory.docs.DeploymentConfig
import nl.vdzon.softwarefactory.git.services.GitCommandClient
import nl.vdzon.softwarefactory.git.services.ProcessResult
import nl.vdzon.softwarefactory.git.services.ProcessRunner
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.github.PullRequestInfo
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

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
        val pullRequests = FakeGitHubApi()
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
            deploymentConfig = DeploymentConfig(
                defaultBaseBranch = "main",
                branchPrefix = "ai/",
                previewUrlTemplate = "https://app-pr-{pr_num}.example.com",
                previewNamespaceTemplate = "app-pr-{pr_num}",
            ),
        )

        val result = flow.completeDummyDeveloperRun(session, "KAN-42", "Story body", githubToken = "token")

        assertTrue(tempDir.resolve("docs/factory/README.md").toFile().exists())
        assertTrue(tempDir.resolve("docs/factory/.dummy-log").readText().contains("KAN-42"))
        assertTrue(tempDir.resolve("docs/stories/KAN-42-story-body.md").readText().contains("[x]: implement requested changes"))
        assertTrue(runner.commands.any { it.take(2) == listOf("git", "commit") })
        assertTrue(runner.commands.any { it.take(2) == listOf("git", "push") })
        assertTrue(pullRequests.created)
        assertTrue(result.completionEvent.payload.contains("\"prNumber\":55"))
        assertTrue(result.completionEvent.payload.contains("\"previewUrlTemplate\":\"https://app-pr-{pr_num}.example.com\""))
    }

    @Test
    fun `real developer flow does not append agent handover to story log`() {
        val runner = FakeProcessRunner { command ->
            if (command == listOf("git", "status", "--porcelain")) {
                ProcessResult(0, " M docs/stories/KAN-42-story.md\n", "")
            } else {
                ProcessResult(0, "", "")
            }
        }
        val pullRequests = FakeGitHubApi()
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
            deploymentConfig = DeploymentConfig(defaultBaseBranch = "main", branchPrefix = "ai/"),
        )
        val storyLog = tempDir.resolve("docs/stories/KAN-42-story.md")
        storyLog.parent.createDirectories()
        val originalStoryLog = """
            # KAN-42

            Menselijke story-context.
        """.trimIndent()
        storyLog.writeText(originalStoryLog)

        flow.completeDeveloperRun(session, "KAN-42", githubToken = "token")

        assertTrue(tempDir.resolve("docs/factory/README.md").toFile().exists())
        assertTrue(runner.commands.any { it.take(2) == listOf("git", "commit") })
        assertTrue(pullRequests.created)
        assertTrue(storyLog.readText() == originalStoryLog)
    }

    @Test
    fun `developer flow supports non github repositories without creating a pull request`() {
        val runner = FakeProcessRunner { command ->
            if (command == listOf("git", "status", "--porcelain")) {
                ProcessResult(0, " M README.md\n", "")
            } else {
                ProcessResult(0, "", "")
            }
        }
        val pullRequests = FakeGitHubApi()
        val flow = DeveloperRepositoryFlow(
            git = GitCommandClient(runner),
            pullRequests = pullRequests,
            skeletonRoot = Path.of("/missing-skeleton-so-classpath-is-used"),
        )
        val session = TargetRepositorySession(
            repoRoot = tempDir,
            repoUrl = "https://dev.azure.com/ing/product/_git/work-project",
            baseBranch = "main",
            branchPrefix = "ai/",
            branchName = "ai/KAN-42",
            deploymentConfig = DeploymentConfig(defaultBaseBranch = "main", branchPrefix = "ai/"),
        )

        val result = flow.completeDeveloperRun(session, "KAN-42", githubToken = "token")

        assertTrue(runner.commands.any { it.take(2) == listOf("git", "push") })
        assertFalse(pullRequests.created)
        assertTrue(result.prNumber == null)
        assertTrue(result.completionEvent.kind == "repository-branch")
        assertTrue(result.completionEvent.payload.contains("\"branchName\":\"ai/KAN-42\""))
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

    private class FakeGitHubApi : GitHubApi {
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

        override fun claimedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> = emptyList()

        override fun markCommentClaimed(targetRepo: String, commentId: Long) = Unit

        override fun markCommentDone(targetRepo: String, commentId: Long) = Unit

        override fun markCommentFailed(targetRepo: String, commentId: Long) = Unit

        override fun closePullRequest(targetRepo: String, prNumber: Int) = Unit

        override fun deleteBranch(targetRepo: String, branchName: String) = Unit

        override fun mergePullRequest(targetRepo: String, prNumber: Int) = Unit
    }
}
