package nl.vdzon.softwarefactory.github

import nl.vdzon.softwarefactory.github.clients.GitHubCliClient
import java.nio.file.Path

data class PullRequestInfo(
    val number: Int,
    val url: String?,
    val state: String? = null,
    val mergedAt: String? = null,
) {
    val isMerged: Boolean =
        state.equals("MERGED", ignoreCase = true) || !mergedAt.isNullOrBlank()
}

data class PullRequestComment(
    val id: Long,
    val body: String,
)

/**
 * Public API of the GitHub module.
 *
 * The GitHub module owns pull request lifecycle operations and PR comment
 * feedback markers. Other modules use this API instead of shelling out to `gh`
 * directly.
 */
interface GitHubApi {
    fun ensurePullRequest(repoRoot: Path, branchName: String, baseBranch: String, title: String, body: String): PullRequestInfo

    fun isMerged(targetRepo: String, prNumber: Int): Boolean

    fun unprocessedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment>

    fun claimedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment>

    fun markCommentClaimed(targetRepo: String, commentId: Long)

    fun markCommentDone(targetRepo: String, commentId: Long)

    fun markCommentFailed(targetRepo: String, commentId: Long)

    fun closePullRequest(targetRepo: String, prNumber: Int)

    fun deleteBranch(targetRepo: String, branchName: String)

    fun mergePullRequest(targetRepo: String, prNumber: Int)

    companion object {
        fun default(): GitHubApi = GitHubCliClient()
    }
}

class GitHubClientException(message: String) : RuntimeException(message)
