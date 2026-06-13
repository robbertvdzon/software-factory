package nl.vdzon.softwarefactory.git

import nl.vdzon.softwarefactory.git.services.*

import nl.vdzon.softwarefactory.git.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class GitCommandClientTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `clone rewrites github ssh url to https and branch checkout creates from base when remote branch is absent`() {
        val runner = FakeProcessRunner { command ->
            when {
                command.take(3) == listOf("git", "ls-remote", "--exit-code") -> ProcessResult(2, "", "")
                else -> ProcessResult(0, "", "")
            }
        }
        val git = GitCommandClient(runner)
        val repoRoot = tempDir.resolve("repo")

        git.clone("git@github.com:robbertvdzon/sample-build-project.git", repoRoot, "token")
        git.checkoutStoryBranch(repoRoot, "ai/KAN-42", "main", createIfMissing = true, githubToken = "token")

        assertTrue(runner.commands.first().contains("https://github.com/robbertvdzon/sample-build-project.git"))
        assertTrue(runner.commands.any { it == listOf("git", "fetch", "origin", "+refs/heads/main:refs/remotes/origin/main") })
        assertTrue(runner.commands.any { it == listOf("git", "checkout", "-B", "ai/KAN-42", "origin/main") })
    }

    @Test
    fun `commitAll returns false when there are no changes`() {
        val runner = FakeProcessRunner { command ->
            if (command == listOf("git", "status", "--porcelain")) ProcessResult(0, "", "") else ProcessResult(0, "", "")
        }
        val git = GitCommandClient(runner)

        val committed = git.commitAll(tempDir, "KAN-42 update", githubToken = null)

        assertEquals(false, committed)
        assertTrue(runner.commands.none { it.take(2) == listOf("git", "commit") })
    }

    @Test
    fun `clone does not inject github token for non github repositories`() {
        val runner = FakeProcessRunner { ProcessResult(0, "", "") }
        val git = GitCommandClient(runner)

        git.clone("https://dev.azure.com/ing/product/_git/work-project", tempDir.resolve("repo"), "github-token")

        assertEquals("0", runner.envs.single()["GIT_TERMINAL_PROMPT"])
        assertFalse(runner.envs.single().containsKey("SF_GITHUB_TOKEN"))
        assertTrue(runner.commands.single().contains("https://dev.azure.com/ing/product/_git/work-project"))
    }

    @Test
    fun `recreateLocalBranchFromBase deletes and recreates the story branch from base`() {
        val runner = FakeProcessRunner { ProcessResult(0, "", "") }
        val git = GitCommandClient(runner)

        git.recreateLocalBranchFromBase(tempDir, "ai/KAN-42", "main", githubToken = null)

        assertTrue(runner.commands.any { it == listOf("git", "fetch", "origin", "+refs/heads/main:refs/remotes/origin/main") })
        assertTrue(runner.commands.any { it == listOf("git", "reset", "--hard") })
        assertTrue(runner.commands.any { it == listOf("git", "clean", "-fd") })
        assertTrue(runner.commands.any { it == listOf("git", "checkout", "--detach", "origin/main") })
        assertTrue(runner.commands.any { it == listOf("git", "branch", "-D", "ai/KAN-42") })
        assertTrue(runner.commands.any { it == listOf("git", "checkout", "-B", "ai/KAN-42", "origin/main") })
    }

    @Test
    fun `mergeBaseIntoBranch fetches, aligns local base and merges cleanly`() {
        val runner = FakeProcessRunner { ProcessResult(0, "", "") }
        val git = GitCommandClient(runner)

        val result = git.mergeBaseIntoBranch(tempDir, "main", githubToken = null)

        assertTrue(result.clean)
        assertTrue(runner.commands.any { it == listOf("git", "fetch", "origin", "+refs/heads/main:refs/remotes/origin/main") })
        assertTrue(runner.commands.any { it == listOf("git", "branch", "-f", "main", "origin/main") })
        assertTrue(runner.commands.any { it == listOf("git", "merge", "--no-edit", "origin/main") })
    }

    @Test
    fun `mergeBaseIntoBranch reports conflicted files and keeps the merge in progress`() {
        val runner = FakeProcessRunner { command ->
            when (command) {
                listOf("git", "merge", "--no-edit", "origin/main") -> ProcessResult(1, "CONFLICT (content)", "")
                listOf("git", "diff", "--name-only", "--diff-filter=U") -> ProcessResult(0, "a.kt\nb.kt\n", "")
                else -> ProcessResult(0, "", "")
            }
        }
        val git = GitCommandClient(runner)

        val result = git.mergeBaseIntoBranch(tempDir, "main", githubToken = null)

        assertFalse(result.clean)
        assertEquals(listOf("a.kt", "b.kt"), result.conflictedFiles)
        // Echte conflicten → merge NIET afbreken (de developer-agent lost ze op).
        assertTrue(runner.commands.none { it == listOf("git", "merge", "--abort") })
    }

    private class FakeProcessRunner(
        private val handler: (List<String>) -> ProcessResult,
    ) : ProcessRunner {
        val commands = mutableListOf<List<String>>()
        val envs = mutableListOf<Map<String, String>>()

        override fun run(command: List<String>, cwd: Path?, env: Map<String, String>, timeoutSeconds: Long): ProcessResult {
            commands += command
            envs += env
            return handler(command)
        }
    }
}
