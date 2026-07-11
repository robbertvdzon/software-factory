package nl.vdzon.softwarefactory.testsupport

import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.github.PullRequestInfo
import java.nio.file.Path

/**
 * [GitHubApi]-fake: gemergde PR's en (geclaimde) factory-comments zijn via de constructor
 * te seed'en; geclaimde comment-id's worden in [claimedComments] geregistreerd.
 * Niet te verwarren met de e2e-variant (`nl.vdzon.softwarefactory.e2e.FakeGitHubApi`)
 * die tegen een lokale git-remote werkt.
 */
class FakeGitHubApi(
    private val mergedPrs: Set<Int> = emptySet(),
    private val commentsByPr: Map<Int, List<PullRequestComment>> = emptyMap(),
    private val claimedCommentsByPr: Map<Int, List<PullRequestComment>> = emptyMap(),
    private val latestSha: String? = null,
) : GitHubApi {
    val claimedComments = mutableListOf<Long>()

    override fun latestCommitSha(targetRepo: String, branch: String): String? = latestSha

    override fun ensurePullRequest(
        repoRoot: Path,
        branchName: String,
        baseBranch: String,
        title: String,
        body: String,
    ): PullRequestInfo =
        PullRequestInfo(number = 1, url = "https://github.example/pr/1")

    override fun isMerged(targetRepo: String, prNumber: Int): Boolean =
        prNumber in mergedPrs

    override fun unprocessedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> =
        commentsByPr[prNumber].orEmpty()

    override fun claimedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> =
        claimedCommentsByPr[prNumber].orEmpty()

    override fun markCommentClaimed(targetRepo: String, commentId: Long) {
        claimedComments += commentId
    }

    override fun markCommentDone(targetRepo: String, commentId: Long) = Unit

    override fun markCommentFailed(targetRepo: String, commentId: Long) = Unit

    override fun closePullRequest(targetRepo: String, prNumber: Int) = Unit

    override fun deleteBranch(targetRepo: String, branchName: String) = Unit

    override fun mergePullRequest(targetRepo: String, prNumber: Int, expectedHeadSha: String) = Unit
}
