package nl.vdzon.softwarefactory.github

import nl.vdzon.softwarefactory.github.*

import nl.vdzon.softwarefactory.github.GitHubCliClient
import nl.vdzon.softwarefactory.git.ProcessResult
import nl.vdzon.softwarefactory.git.ProcessRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class GitHubCliClientTest {
    @Test
    fun `ensurePullRequest reuses existing open PR for branch`() {
        val runner = FakeProcessRunner { command ->
            when {
                command.take(3) == listOf("gh", "pr", "list") ->
                    ProcessResult(0, """[{"number":7,"url":"https://github.example/pr/7","state":"OPEN"}]""", "")
                else -> ProcessResult(99, "", "unexpected command")
            }
        }
        val client = GitHubCliClient(runner)

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
        val client = GitHubCliClient(runner)

        val comments = client.unprocessedFactoryComments(
            "git@github.com:robbertvdzon/sample-build-project.git",
            12,
        )

        assertEquals(listOf(101L), comments.map { it.id })
    }

    @Test
    fun `claimed comments use eyes without done or failed reactions`() {
        val runner = FakeProcessRunner { command ->
            when {
                command.take(2) == listOf("gh", "api") && command.last().endsWith("/comments") ->
                    ProcessResult(
                        0,
                        """
                        [
                          {"id":201,"body":"@factory claimed"},
                          {"id":202,"body":"@factory done"},
                          {"id":203,"body":"@factory failed"}
                        ]
                        """.trimIndent(),
                        "",
                    )
                command.take(2) == listOf("gh", "api") && command.last().endsWith("/201/reactions") ->
                    ProcessResult(0, """[{"content":"eyes"}]""", "")
                command.take(2) == listOf("gh", "api") && command.last().endsWith("/202/reactions") ->
                    ProcessResult(0, """[{"content":"eyes"},{"content":"rocket"}]""", "")
                command.take(2) == listOf("gh", "api") && command.last().endsWith("/203/reactions") ->
                    ProcessResult(0, """[{"content":"eyes"},{"content":"confused"}]""", "")
                else -> ProcessResult(99, "", "unexpected command: $command")
            }
        }
        val client = GitHubCliClient(runner)

        val comments = client.claimedFactoryComments(
            "git@github.com:robbertvdzon/sample-build-project.git",
            12,
        )

        assertEquals(listOf(201L), comments.map { it.id })
    }

    @Test
    fun `manual PR operations call gh with target repo slug`() {
        val runner = FakeProcessRunner { ProcessResult(0, "", "") }
        val client = GitHubCliClient(runner)

        client.closePullRequest("git@github.com:robbertvdzon/sample-build-project.git", 12)
        client.deleteBranch("git@github.com:robbertvdzon/sample-build-project.git", "ai/KAN-12")
        client.mergePullRequest("git@github.com:robbertvdzon/sample-build-project.git", 12)
        client.markCommentDone("git@github.com:robbertvdzon/sample-build-project.git", 9001)
        client.markCommentFailed("git@github.com:robbertvdzon/sample-build-project.git", 9002)

        assertEquals(
            listOf("gh", "pr", "close", "12", "--repo", "robbertvdzon/sample-build-project"),
            runner.commands[0],
        )
        assertEquals(
            listOf("gh", "api", "-X", "DELETE", "repos/robbertvdzon/sample-build-project/git/refs/heads/ai/KAN-12"),
            runner.commands[1],
        )
        assertEquals(
            listOf("gh", "pr", "merge", "12", "--repo", "robbertvdzon/sample-build-project", "--squash", "--delete-branch"),
            runner.commands[2],
        )
        assertEquals("content=rocket", runner.commands[3].last())
        assertEquals("content=confused", runner.commands[4].last())
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
