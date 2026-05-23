package nl.vdzon.softwarefactory.github

import nl.vdzon.softwarefactory.git.ProcessResult
import nl.vdzon.softwarefactory.git.ProcessRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class GitHubCliPullRequestClientTest {
    @Test
    fun `ensurePullRequest reuses existing open PR for branch`() {
        val runner = FakeProcessRunner { command ->
            when {
                command.take(3) == listOf("gh", "pr", "list") ->
                    ProcessResult(0, """[{"number":7,"url":"https://github.example/pr/7","state":"OPEN"}]""", "")
                else -> ProcessResult(99, "", "unexpected command")
            }
        }
        val client = GitHubCliPullRequestClient(runner)

        val pr = client.ensurePullRequest(Path.of("."), "ai/KAN-42", "main", "title", "body")

        assertEquals(7, pr.number)
        assertTrue(runner.commands.none { it.take(3) == listOf("gh", "pr", "create") })
    }

    @Test
    fun `factory comments ignore agent comments and already reacted comments`() {
        val runner = FakeProcessRunner { command ->
            when {
                command.take(2) == listOf("gh", "api") && command.last().endsWith("/comments") ->
                    ProcessResult(
                        0,
                        """
                        [
                          {"id":101,"body":"@factory pas dit aan"},
                          {"id":102,"body":"[REVIEWER] @factory agent-comment negeren"},
                          {"id":103,"body":"geen trigger"},
                          {"id":104,"body":"@factory al opgepakt"}
                        ]
                        """.trimIndent(),
                        "",
                    )
                command.take(2) == listOf("gh", "api") && command.last().endsWith("/101/reactions") ->
                    ProcessResult(0, "[]", "")
                command.take(2) == listOf("gh", "api") && command.last().endsWith("/104/reactions") ->
                    ProcessResult(0, """[{"content":"eyes"}]""", "")
                else -> ProcessResult(99, "", "unexpected command: $command")
            }
        }
        val client = GitHubCliPullRequestClient(runner)

        val comments = client.unprocessedFactoryComments(
            "git@github.com:robbertvdzon/sample-build-project.git",
            12,
        )

        assertEquals(listOf(101L), comments.map { it.id })
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
