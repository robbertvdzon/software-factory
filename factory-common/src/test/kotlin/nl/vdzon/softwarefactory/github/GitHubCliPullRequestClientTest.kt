package nl.vdzon.softwarefactory.github

import nl.vdzon.softwarefactory.github.*

import nl.vdzon.softwarefactory.github.clients.GitHubCliClient
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.git.GitProcessResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class GitHubCliClientTest {
    @Test
    fun `ensurePullRequest reuses existing open PR for branch`() {
        val runner = FakeProcessRunner { command ->
            when {
                command.take(3) == listOf("gh", "pr", "list") ->
                    GitProcessResult(0, """[{"number":7,"url":"https://github.example/pr/7","state":"OPEN"}]""", "")
                else -> GitProcessResult(99, "", "unexpected command")
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
                    GitProcessResult(
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
                    GitProcessResult(0, "[]", "")
                command.take(2) == listOf("gh", "api") && command.last().endsWith("/104/reactions") ->
                    GitProcessResult(0, """[{"content":"eyes"}]""", "")
                else -> GitProcessResult(99, "", "unexpected command: $command")
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
                    GitProcessResult(
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
                    GitProcessResult(0, """[{"content":"eyes"}]""", "")
                command.take(2) == listOf("gh", "api") && command.last().endsWith("/202/reactions") ->
                    GitProcessResult(0, """[{"content":"eyes"},{"content":"rocket"}]""", "")
                command.take(2) == listOf("gh", "api") && command.last().endsWith("/203/reactions") ->
                    GitProcessResult(0, """[{"content":"eyes"},{"content":"confused"}]""", "")
                else -> GitProcessResult(99, "", "unexpected command: $command")
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
        val runner = FakeProcessRunner { GitProcessResult(0, "", "") }
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

    @Test
    fun `mergePullRequest treats already-merged PR as success when gh exits non-zero`() {
        val runner = FakeProcessRunner { command ->
            when {
                command.take(3) == listOf("gh", "pr", "merge") ->
                    GitProcessResult(1, "", "failed to run git: HTTP 401: Bad credentials (https://api.github.com/graphql)")
                command.take(3) == listOf("gh", "pr", "view") ->
                    GitProcessResult(0, """{"state":"MERGED","mergedAt":"2026-06-19T17:26:41Z"}""", "")
                else -> GitProcessResult(99, "", "unexpected command: $command")
            }
        }
        val client = GitHubCliClient(runner)

        // Geen exception: gh faalde, maar de PR is op GitHub MERGED → behandeld als succes.
        client.mergePullRequest("git@github.com:robbertvdzon/sample-build-project.git", 12)

        assertTrue(
            runner.commands.any {
                it == listOf("gh", "pr", "view", "12", "--repo", "robbertvdzon/sample-build-project", "--json", "state,mergedAt")
            },
        )
    }

    @Test
    fun `mergePullRequest throws when gh fails and PR is not merged`() {
        val runner = FakeProcessRunner { command ->
            when {
                command.take(3) == listOf("gh", "pr", "merge") ->
                    GitProcessResult(1, "", "Pull request is not mergeable: merge conflict")
                command.take(3) == listOf("gh", "pr", "view") ->
                    GitProcessResult(0, """{"state":"OPEN","mergedAt":null}""", "")
                else -> GitProcessResult(99, "", "unexpected command: $command")
            }
        }
        val client = GitHubCliClient(runner)

        assertThrows(GitHubClientException::class.java) {
            client.mergePullRequest("git@github.com:robbertvdzon/sample-build-project.git", 12)
        }
    }

    private class FakeProcessRunner(
        private val handler: (List<String>) -> GitProcessResult,
    ) : GitApi {
        val commands = mutableListOf<List<String>>()

        override fun runCommand(command: List<String>, cwd: Path?, env: Map<String, String>, timeoutSeconds: Long): GitProcessResult {
            commands += command
            return handler(command)
        }

        override fun repositorySlug(repoUrl: String): String? =
            "robbertvdzon/sample-build-project"

        override fun clone(repoUrl: String, targetDir: Path, githubToken: String?) = Unit
        override fun checkoutBase(repoRoot: Path, baseBranch: String, githubToken: String?) = Unit
        override fun checkoutStoryBranch(repoRoot: Path, branchName: String, baseBranch: String, createIfMissing: Boolean, githubToken: String?) = Unit
        override fun commitAll(repoRoot: Path, message: String, githubToken: String?): Boolean = true
        override fun push(repoRoot: Path, branchName: String, githubToken: String?) = Unit
        override fun remoteBranchExists(repoRoot: Path, branchName: String, githubToken: String?): Boolean = false
    }
}
