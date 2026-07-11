package nl.vdzon.softwarefactory.merge

sealed interface PullRequestMergeResult {
    data class Merged(val verifiedHeadSha: String) : PullRequestMergeResult
    data class Pending(val reason: String) : PullRequestMergeResult
    data class Blocked(val reason: String) : PullRequestMergeResult
}

/**
 * Enige applicatie-use-case voor een onomkeerbare pull-requestmerge.
 *
 * Beide entrypoints leveren de logische projectnaam aan. De implementatie controleert de
 * bijbehorende policy op exact de actuele PR-head en gebruikt die SHA als GitHub-preconditie.
 */
fun interface PullRequestMergeService {
    fun merge(
        projectName: String?,
        targetRepo: String,
        prNumber: Int,
        beforeMerge: () -> Unit,
    ): PullRequestMergeResult
}
