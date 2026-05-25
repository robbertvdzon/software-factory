package nl.vdzon.softwarefactory.git

import nl.vdzon.softwarefactory.git.services.GitCommandClient
import nl.vdzon.softwarefactory.git.services.ProcessResult
import nl.vdzon.softwarefactory.git.services.ProcessRunner
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
    fun `clone rewrites github ssh url to https`() {
        val runner = FakeProcessRunner { ProcessResult(0, "", "") }
        val git = GitCommandClient(runner)

        git.clone("git@github.com:robbertvdzon/sample-build-project.git", tempDir.resolve("repo"), "token")

        assertTrue(runner.commands.single().contains("https://github.com/robbertvdzon/sample-build-project.git"))
        assertEquals("token", runner.envs.single()["SF_GITHUB_TOKEN"])
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
