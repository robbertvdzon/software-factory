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

data class PullRequestCheck(
    val name: String,
    val state: String,
    val bucket: String,
    val link: String? = null,
)

sealed interface PullRequestChecksResult {
    data object Passed : PullRequestChecksResult
    data class Blocked(val reason: String, val checks: List<PullRequestCheck> = emptyList()) : PullRequestChecksResult
}

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

    /**
     * Controleert de machine-verifieerbare kwaliteitschecks op de actuele HEAD van de PR.
     * Een ontbrekende, pending, overgeslagen of rode verplichte check blokkeert de merge.
     */
    fun requiredChecks(targetRepo: String, prNumber: Int, requiredNames: Set<String>): PullRequestChecksResult =
        PullRequestChecksResult.Blocked("GitHub-checkcontrole is niet geïmplementeerd voor deze GitHubApi.")

    /**
     * De commit-SHA van de HEAD van [branch] in [targetRepo], of `null` als die niet bepaald
     * kan worden (branch onbekend, gh-fout, niet-github repo). Wordt door de deploy-verificatie
     * gebruikt om ná de merge de verwachte live-SHA te bepalen (base-branch HEAD = merge-commit).
     * Default `null` zodat test-fakes niet hoeven te implementeren; alleen de echte CLI-client vult 'm.
     */
    fun latestCommitSha(targetRepo: String, branch: String): String? = null

    companion object {
        fun default(): GitHubApi = GitHubCliClient()
    }
}

class GitHubClientException(message: String) : RuntimeException(message)
