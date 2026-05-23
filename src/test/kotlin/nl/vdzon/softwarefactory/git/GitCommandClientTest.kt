package nl.vdzon.softwarefactory.git

import org.junit.jupiter.api.Assertions.assertEquals
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

    private class FakeProcessRunner(
        private val handler: (List<String>) -> ProcessResult,
    ) : ProcessRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>, cwd: Path?, env: Map<String, String>, timeoutSeconds: Long): ProcessResult {
            commands += command
            return handler(command)
        }
    }
}
