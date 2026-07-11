package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.git.GitMergeResult
import nl.vdzon.softwarefactory.git.GitProcessResult
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.github.PullRequestInfo
import nl.vdzon.softwarefactory.core.StoryRunRecord
import nl.vdzon.softwarefactory.runtime.workspaces.StoryWorkspaceService
import nl.vdzon.softwarefactory.core.AgentRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class StoryWorkspaceServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `prepare installs missing factory and stories docs when docs directory already exists`() {
        val git = FakeGitApi { repoRoot ->
            repoRoot.resolve(".git").createDirectories()
            repoRoot.resolve("docs").createDirectories()
            repoRoot.resolve("docs").resolve("README.md").writeText("project docs\n")
        }
        val storyRoot = tempDir.resolve("stories")
        val workspace = storyRoot.resolve("KAN-42")
        val service = StoryWorkspaceService(
            factorySecrets = factorySecrets(),
            git = git,
            pullRequests = FakeGitHubApi(),
            storyRoot = storyRoot,
        )

        service.prepare(
            StoryRunRecord(
                id = 1,
                storyKey = "KAN-42",
                targetRepo = "ssh://git.example.internal/team/project.git",
                workspacePath = workspace.toString(),
            ),
            AgentRole.REFINER,
        )

        val repoRoot = workspace.resolve("repo")
        assertTrue(repoRoot.resolve("docs/factory/README.md").exists())
        assertTrue(repoRoot.resolve("docs/factory/deployment.md").exists())
        assertTrue(repoRoot.resolve("docs/stories/.gitkeep").exists())
        assertEquals("project docs\n", repoRoot.resolve("docs/README.md").readText())
        assertEquals(listOf("ai/KAN-42" to "main"), git.checkedOutBranches)
    }

    @Test
    fun `developer prepare merges the base branch into the story branch`() {
        val git = FakeGitApi { repoRoot -> repoRoot.resolve(".git").createDirectories() }
        val storyRoot = tempDir.resolve("stories")
        val workspace = storyRoot.resolve("KAN-43")
        val service = StoryWorkspaceService(factorySecrets(), git, FakeGitHubApi(), storyRoot = storyRoot)

        service.prepare(
            StoryRunRecord(id = 1, storyKey = "KAN-43", targetRepo = "ssh://git.example.internal/team/project.git", workspacePath = workspace.toString()),
            AgentRole.DEVELOPER,
        )

        assertEquals(listOf("main"), git.mergedBases)
    }

    @Test
    fun `syncAfterAgent fails when merge conflict markers remain unresolved`() {
        val git = FakeGitApi { repoRoot -> repoRoot.resolve(".git").createDirectories() }
        git.unmerged = listOf("conflict.kt")
        val storyRoot = tempDir.resolve("stories")
        val workspace = storyRoot.resolve("KAN-44")
        val repoRoot = workspace.resolve("repo")
        repoRoot.resolve(".git").createDirectories()
        repoRoot.resolve("conflict.kt").writeText("ok\n<<<<<<< HEAD\nmine\n=======\ntheirs\n>>>>>>> origin/main\n")
        val service = StoryWorkspaceService(factorySecrets(), git, FakeGitHubApi(), storyRoot = storyRoot)

        val ex = assertThrows(IllegalStateException::class.java) {
            service.syncAfterAgent(
                StoryRunRecord(id = 1, storyKey = "KAN-44", targetRepo = "ssh://git.example.internal/team/project.git", workspacePath = workspace.toString()),
                AgentRole.DEVELOPER,
            )
        }
        assertTrue(ex.message!!.contains("niet opgelost"))
        assertFalse(git.committed)
    }

    @Test
    fun `syncAfterAgent commits when an unmerged path no longer has conflict markers`() {
        val git = FakeGitApi { repoRoot -> repoRoot.resolve(".git").createDirectories() }
        git.unmerged = listOf("resolved.kt")
        val storyRoot = tempDir.resolve("stories")
        val workspace = storyRoot.resolve("KAN-45")
        val repoRoot = workspace.resolve("repo")
        repoRoot.resolve(".git").createDirectories()
        repoRoot.resolve("resolved.kt").writeText("schone, opgeloste inhoud\n")
        val service = StoryWorkspaceService(factorySecrets(), git, FakeGitHubApi(), storyRoot = storyRoot)

        service.syncAfterAgent(
            StoryRunRecord(id = 1, storyKey = "KAN-45", targetRepo = "ssh://git.example.internal/team/project.git", workspacePath = workspace.toString()),
            AgentRole.DEVELOPER,
        )

        assertTrue(git.committed)
    }

    private fun factorySecrets(): FactorySecrets =
        FactorySecrets(
            trackerProjects = emptyList(),
            githubToken = "github-token",
            factoryDatabaseUrl = "postgresql://localhost/software_factory",
            factoryDatabaseSchema = "software_factory",
            kubeconfig = null,
            aiCredentialsDir = null,
            aiOauthToken = null,
            loadedFrom = "test",
        )

    private class FakeGitApi(
        private val afterClone: (Path) -> Unit,
    ) : GitApi {
        val checkedOutBranches = mutableListOf<Pair<String, String>>()
        val mergedBases = mutableListOf<String>()
        var unmerged: List<String> = emptyList()
        var committed = false

        override fun clone(repoUrl: String, targetDir: Path, githubToken: String?) {
            targetDir.createDirectories()
            afterClone(targetDir)
        }

        override fun checkoutBase(repoRoot: Path, baseBranch: String, githubToken: String?) = Unit

        override fun checkoutStoryBranch(
            repoRoot: Path,
            branchName: String,
            baseBranch: String,
            createIfMissing: Boolean,
            githubToken: String?,
        ) {
            checkedOutBranches += branchName to baseBranch
        }

        override fun mergeBaseIntoBranch(repoRoot: Path, baseBranch: String, githubToken: String?): GitMergeResult {
            mergedBases += baseBranch
            return GitMergeResult(clean = true)
        }

        override fun unmergedPaths(repoRoot: Path, githubToken: String?): List<String> = unmerged

        override fun commitAll(repoRoot: Path, message: String, githubToken: String?): Boolean {
            committed = true
            return false
        }

        override fun push(repoRoot: Path, branchName: String, githubToken: String?) = Unit

        override fun remoteBranchExists(repoRoot: Path, branchName: String, githubToken: String?): Boolean = false

        override fun runCommand(command: List<String>, cwd: Path?, env: Map<String, String>, timeoutSeconds: Long): GitProcessResult =
            GitProcessResult(0, "", "")

        override fun repositorySlug(repoUrl: String): String? = null
    }

    private class FakeGitHubApi : GitHubApi {
        override fun ensurePullRequest(repoRoot: Path, branchName: String, baseBranch: String, title: String, body: String): PullRequestInfo =
            PullRequestInfo(0, null)

        override fun isMerged(targetRepo: String, prNumber: Int): Boolean = false

        override fun unprocessedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> = emptyList()

        override fun claimedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> = emptyList()

        override fun markCommentClaimed(targetRepo: String, commentId: Long) = Unit

        override fun markCommentDone(targetRepo: String, commentId: Long) = Unit

        override fun markCommentFailed(targetRepo: String, commentId: Long) = Unit

        override fun closePullRequest(targetRepo: String, prNumber: Int) = Unit

        override fun deleteBranch(targetRepo: String, branchName: String) = Unit

        override fun mergePullRequest(targetRepo: String, prNumber: Int, expectedHeadSha: String) = Unit
    }
}
