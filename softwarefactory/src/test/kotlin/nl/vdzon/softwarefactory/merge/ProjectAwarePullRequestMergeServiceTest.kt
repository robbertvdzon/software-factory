package nl.vdzon.softwarefactory.merge

import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.github.GitHubClientException
import nl.vdzon.softwarefactory.github.PullRequestChecksResult
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.github.PullRequestHeadChangedException
import nl.vdzon.softwarefactory.github.PullRequestInfo
import nl.vdzon.softwarefactory.merge.internal.ProjectAwarePullRequestMergeService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ProjectAwarePullRequestMergeServiceTest {

    @Test
    fun `ready merges once with verified head as atomic precondition`() {
        val github = FakeGitHubApi(PullRequestChecksResult.Ready("head-a", emptyList()))
        val service = service(github)
        var beforeMergeCalls = 0

        val result = service.merge("backend", "git@example/backend.git", 42) { beforeMergeCalls++ }

        assertEquals(PullRequestMergeResult.Merged("head-a"), result)
        assertEquals(1, beforeMergeCalls)
        assertEquals(listOf(MergeCall("git@example/backend.git", 42, "head-a")), github.merges)
    }

    @Test
    fun `pending does not enter irreversible phase or merge`() {
        val github = FakeGitHubApi(PullRequestChecksResult.Pending("Backend CI queued"))
        val service = service(github)
        var beforeMergeCalls = 0

        val result = service.merge("backend", "git@example/backend.git", 42) { beforeMergeCalls++ }

        assertTrue(result is PullRequestMergeResult.Pending)
        assertEquals(0, beforeMergeCalls)
        assertTrue(github.merges.isEmpty())
    }

    @Test
    fun `blocked and API failure fail closed without merge`() {
        val blocked = FakeGitHubApi(PullRequestChecksResult.Blocked("required check failed"))
        assertTrue(service(blocked).merge("backend", "repo", 1) {} is PullRequestMergeResult.Blocked)
        assertTrue(blocked.merges.isEmpty())

        val apiFailure = FakeGitHubApi(PullRequestChecksResult.Ready("unused", emptyList())).apply {
            checksFailure = GitHubClientException("API unavailable")
        }
        val result = service(apiFailure).merge("backend", "repo", 1) {}
        assertTrue(result is PullRequestMergeResult.Blocked)
        assertTrue(apiFailure.merges.isEmpty())
    }

    @Test
    fun `two projects use independent required check names`() {
        val github = FakeGitHubApi(PullRequestChecksResult.Ready("head", emptyList()))
        val resolver = ProjectRepoResolver(
            repos = mapOf("backend" to "repo-b", "frontend" to "repo-f"),
            requiredChecks = mapOf(
                "backend" to setOf("Backend verification"),
                "frontend" to setOf("Flutter verification"),
            ),
        )
        val service = ProjectAwarePullRequestMergeService(github, resolver)

        service.merge("backend", "repo-b", 1) {}
        service.merge("frontend", "repo-f", 2) {}

        assertEquals(
            listOf(setOf("Backend verification"), setOf("Flutter verification")),
            github.requiredPolicies,
        )
    }

    @Test
    fun `head A green then head B before merge becomes pending and never merges B`() {
        val github = FakeGitHubApi(PullRequestChecksResult.Ready("head-a", emptyList())).apply {
            mergeFailure = PullRequestHeadChangedException("head-a", "head-b")
        }
        val service = service(github)

        val result = service.merge("backend", "repo", 1) {}

        assertTrue(result is PullRequestMergeResult.Pending)
        assertEquals(listOf(MergeCall("repo", 1, "head-a")), github.merges)
    }

    @Test
    fun `startup rejects a configured repository without non-empty policy`() {
        val resolver = ProjectRepoResolver(repos = mapOf("backend" to "repo"))

        val exception = assertThrows(IllegalArgumentException::class.java) {
            ProjectAwarePullRequestMergeService(FakeGitHubApi(PullRequestChecksResult.Pending("unused")), resolver)
        }

        assertTrue(exception.message.orEmpty().contains("backend"))
    }

    private fun service(github: FakeGitHubApi) = ProjectAwarePullRequestMergeService(
        github,
        ProjectRepoResolver(
            repos = mapOf("backend" to "repo"),
            requiredChecks = mapOf("backend" to setOf("Backend verification")),
        ),
    )

    private data class MergeCall(val repo: String, val number: Int, val expectedHeadSha: String)

    private class FakeGitHubApi(private val checksResult: PullRequestChecksResult) : GitHubApi {
        val merges = mutableListOf<MergeCall>()
        val requiredPolicies = mutableListOf<Set<String>>()
        var checksFailure: GitHubClientException? = null
        var mergeFailure: GitHubClientException? = null

        override fun requiredChecks(targetRepo: String, prNumber: Int, requiredNames: Set<String>): PullRequestChecksResult {
            requiredPolicies += requiredNames
            checksFailure?.let { throw it }
            return checksResult
        }

        override fun mergePullRequest(targetRepo: String, prNumber: Int, expectedHeadSha: String) {
            merges += MergeCall(targetRepo, prNumber, expectedHeadSha)
            mergeFailure?.let { throw it }
        }

        override fun ensurePullRequest(
            repoRoot: Path,
            branchName: String,
            baseBranch: String,
            title: String,
            body: String,
        ) = PullRequestInfo(1, null)

        override fun isMerged(targetRepo: String, prNumber: Int) = false
        override fun unprocessedFactoryComments(targetRepo: String, prNumber: Int) = emptyList<PullRequestComment>()
        override fun claimedFactoryComments(targetRepo: String, prNumber: Int) = emptyList<PullRequestComment>()
        override fun markCommentClaimed(targetRepo: String, commentId: Long) = Unit
        override fun markCommentDone(targetRepo: String, commentId: Long) = Unit
        override fun markCommentFailed(targetRepo: String, commentId: Long) = Unit
        override fun closePullRequest(targetRepo: String, prNumber: Int) = Unit
        override fun deleteBranch(targetRepo: String, branchName: String) = Unit
    }
}
