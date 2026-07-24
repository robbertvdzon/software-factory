package nl.vdzon.softwarefactory.github

import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.git.GitProcessResult
import nl.vdzon.softwarefactory.github.clients.GitHubCliClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.OffsetDateTime

/**
 * Story 5 (`StoryDeployReconciler`): dekt [GitHubApi.mergeInfo]/[GitHubApi.isAncestor], de nieuwe
 * GitHubCliClient-methoden die de ancestor-check (functioneel equivalent aan
 * `git merge-base --is-ancestor`) resp. de historisch-vaste merge-commit van een PR ophalen.
 */
class GitHubCliClientDeployReconcilerTest {

    @Test
    fun `mergeInfo parses merge commit sha and merged-at from the pulls endpoint`() {
        val client = GitHubCliClient(FakeProcessRunner { command ->
            if (command.last() == "repos/robbertvdzon/sample-build-project/pulls/12") {
                GitProcessResult(0, """{"merge_commit_sha":"abc123","merged_at":"2026-07-20T10:00:00Z"}""", "")
            } else {
                GitProcessResult(99, "", "unexpected command: $command")
            }
        })

        val info = client.mergeInfo("git@github.com:robbertvdzon/sample-build-project.git", 12)

        assertEquals("abc123", info?.mergeCommitSha)
        assertEquals(OffsetDateTime.parse("2026-07-20T10:00:00Z"), info?.mergedAt)
    }

    @Test
    fun `mergeInfo returns null when the PR is not (yet) merged`() {
        val client = GitHubCliClient(FakeProcessRunner { _ ->
            GitProcessResult(0, """{"merge_commit_sha":null,"merged_at":null}""", "")
        })

        val info = client.mergeInfo("git@github.com:robbertvdzon/sample-build-project.git", 12)

        assertNull(info?.mergeCommitSha)
        assertNull(info?.mergedAt)
    }

    @Test
    fun `mergeInfo returns null on a gh failure`() {
        val client = GitHubCliClient(FakeProcessRunner { GitProcessResult(1, "", "boom") })

        assertNull(client.mergeInfo("git@github.com:robbertvdzon/sample-build-project.git", 12))
    }

    @Test
    fun `isAncestor treats identical and ahead as ancestor`() {
        listOf("identical", "ahead").forEach { status ->
            val client = GitHubCliClient(FakeProcessRunner { GitProcessResult(0, status, "") })
            assertTrue(
                client.isAncestor("git@github.com:robbertvdzon/sample-build-project.git", "merge-sha", "live-sha") == true,
                "status=$status should count as ancestor",
            )
        }
    }

    @Test
    fun `isAncestor treats behind and diverged as not-ancestor`() {
        listOf("behind", "diverged").forEach { status ->
            val client = GitHubCliClient(FakeProcessRunner { GitProcessResult(0, status, "") })
            assertEquals(
                false,
                client.isAncestor("git@github.com:robbertvdzon/sample-build-project.git", "merge-sha", "live-sha"),
                "status=$status should NOT count as ancestor",
            )
        }
    }

    @Test
    fun `isAncestor returns null (unknown) on a gh failure instead of guessing`() {
        val client = GitHubCliClient(FakeProcessRunner { GitProcessResult(1, "", "not found") })

        assertNull(client.isAncestor("git@github.com:robbertvdzon/sample-build-project.git", "merge-sha", "live-sha"))
    }

    private class FakeProcessRunner(
        private val handler: (List<String>) -> GitProcessResult,
    ) : GitApi {
        override fun runCommand(command: List<String>, cwd: Path?, env: Map<String, String>, timeoutSeconds: Long): GitProcessResult =
            handler(command)

        override fun repositorySlug(repoUrl: String): String? = "robbertvdzon/sample-build-project"

        override fun clone(repoUrl: String, targetDir: Path, githubToken: String?) = Unit
        override fun checkoutBase(repoRoot: Path, baseBranch: String, githubToken: String?) = Unit
        override fun checkoutStoryBranch(repoRoot: Path, branchName: String, baseBranch: String, createIfMissing: Boolean, githubToken: String?) = Unit
        override fun commitAll(repoRoot: Path, message: String, githubToken: String?): Boolean = true
        override fun push(repoRoot: Path, branchName: String, githubToken: String?) = Unit
        override fun remoteBranchExists(repoRoot: Path, branchName: String, githubToken: String?): Boolean = false
    }
}
