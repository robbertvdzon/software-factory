package nl.vdzon.softwarefactory.github

import nl.vdzon.softwarefactory.github.clients.GitHubCliClient
import java.nio.file.Path
import java.time.OffsetDateTime

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
    val id: Long = 0,
)

sealed interface PullRequestChecksResult {
    data class Ready(val verifiedHeadSha: String, val checks: List<PullRequestCheck>) : PullRequestChecksResult
    data class Pending(val reason: String, val checks: List<PullRequestCheck> = emptyList()) : PullRequestChecksResult
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

    fun mergePullRequest(targetRepo: String, prNumber: Int, expectedHeadSha: String)

    /**
     * Controleert de machine-verifieerbare kwaliteitschecks op exact de actuele HEAD van de PR.
     * Queued/in-progress is [PullRequestChecksResult.Pending]. Een nog geheel ontbrekende
     * check-run telt ook als [PullRequestChecksResult.Pending] zolang de HEAD-commit vers is
     * (GitHub Actions-queuevertraging vlak na een push); blijft hij na die coulanceperiode nog
     * steeds afwezig, overgeslagen, geannuleerd, rood of onbetrouwbaar, dan is het
     * [PullRequestChecksResult.Blocked].
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

    /**
     * De bestandspaden die PR [prNumber] van [targetRepo] wijzigt, of `null` als dat niet bepaald kan
     * worden (onbekende/niet-github repo, gh-fout). Gebruikt door de DEPLOY-subtaak (multi-deployment-
     * routing, SF-1) om te bepalen welke `matchPaths`-deploy-doelen deze story raakt — werkt ook ná de
     * merge, want GitHub blijft de bestandslijst van een gemergede/gesloten PR rapporteren. Default
     * `null` zodat test-fakes niet hoeven te implementeren; alleen de echte CLI-client vult 'm.
     */
    fun changedFiles(targetRepo: String, prNumber: Int): List<String>? = null

    /**
     * De merge-commit-SHA en het merge-tijdstip van PR [prNumber] in [targetRepo], of `null` als dat
     * niet bepaald kan worden (PR (nog) niet gemerged, onbekende/niet-github repo, gh-fout). Gebruikt
     * door `StoryDeployReconciler` (Story 5 — deployedAt/rollout): anders dan [latestCommitSha] (de
     * HUIDIGE HEAD van de base-branch, die na latere merges van andere stories niet meer overeenkomt
     * met DEZE story's merge) is dit de historisch vaste merge-commit van precies deze PR — nodig
     * voor de ancestor-check ("zit deze story's merge-commit al in de live-SHA?"), ook lang nadat
     * andere stories al opnieuw gemerged zijn. Default `null` zodat test-fakes niet hoeven te
     * implementeren; alleen de echte CLI-client vult 'm.
     */
    fun mergeInfo(targetRepo: String, prNumber: Int): PullRequestMergeInfo? = null

    /**
     * Is [ancestorSha] een voorouder van (of gelijk aan) [descendantSha] op [targetRepo]? `null` als
     * dat niet te bepalen is (onbekende/niet-github repo, gh-fout, onbekende SHA). Gebruikt door
     * `StoryDeployReconciler` (Story 5) i.p.v. een lokale `git merge-base --is-ancestor`: er is geen
     * blijvende lokale clone van elk deploy-doel-repo beschikbaar in de factory zelf (zie
     * [changedFiles]'s docstring voor dezelfde afweging bij de story-diff), dus dit wrapt in plaats
     * daarvan de functioneel equivalente GitHub-compare-API
     * (`GET repos/{slug}/compare/{ancestorSha}...{descendantSha}`): status `identical`/`ahead` ⇒
     * ancestor (true), `behind`/`diverged` ⇒ geen ancestor (false).
     */
    fun isAncestor(targetRepo: String, ancestorSha: String, descendantSha: String): Boolean? = null

    companion object {
        fun default(): GitHubApi = GitHubCliClient()
    }
}

/**
 * Zie [GitHubApi.mergeInfo]. [mergeCommitSha]/[mergedAt] zijn `null` als GitHub ze (nog) niet
 * rapporteert (PR niet gemerged, of veld ontbreekt in de API-respons).
 */
data class PullRequestMergeInfo(
    val mergeCommitSha: String?,
    val mergedAt: OffsetDateTime?,
)

open class GitHubClientException(message: String) : RuntimeException(message)

class PullRequestHeadChangedException(
    val expectedHeadSha: String,
    val actualHeadSha: String?,
) : GitHubClientException(
    "PR-head wijzigde vóór de merge: verwacht $expectedHeadSha, actueel ${actualHeadSha ?: "onbekend"}",
)
