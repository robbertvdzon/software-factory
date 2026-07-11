package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.github.GitHubClientException
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.github.PullRequestInfo
import nl.vdzon.softwarefactory.github.PullRequestChecksResult
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fake [GitHubApi] voor de e2e-harness: vervangt de `gh`-CLI zodat de merge/deploy-keten
 * end-to-end kan draaien tegen de lokale [LocalGitRemote] (waar geen echte GitHub-PR bestaat).
 *
 *  - [openPullRequest] deelt PR-nummers uit per branch (aangeroepen door de scripted developer,
 *    die het `github-pr`-event rapporteert zoals de echte agentworker — zo raakt
 *    `storyRun.prNumber` via het normale completion-pad gevuld).
 *  - [mergePullRequest] doet een **echte lokale squash-merge** van de PR-branch naar `main` op de
 *    [LocalGitRemote], zodat de test kan verifiëren dat de merge-subtaak de code echt op main zet.
 *  - [isMerged] rapporteert de merge-status (gebruikt door de PR-monitor in de orchestrator-poll).
 *
 * Onbekende PR-nummers geven een [GitHubClientException] — hetzelfde foutpad als de echte client,
 * zodat het SF-244-foutgedrag (merge-fout → Error op de merge-subtaak) gedekt blijft.
 */
class FakeGitHubApi(private val remote: LocalGitRemote) : GitHubApi {
    override fun requiredChecks(targetRepo: String, prNumber: Int, requiredNames: Set<String>) =
        PullRequestChecksResult.Ready("fake-head-sha", emptyList())

    private data class PullRequest(val number: Int, val branchName: String, @Volatile var merged: Boolean = false)

    private val nextNumber = AtomicInteger(100)
    private val byNumber = ConcurrentHashMap<Int, PullRequest>()

    /** Wist de PR-registratie tussen tests (de fake is een static over de test-JVM). */
    fun reset() {
        byNumber.clear()
    }

    /** Bestaande open PR voor [branchName] hergebruiken of een nieuw nummer uitdelen (zoals `gh pr create`). */
    fun openPullRequest(branchName: String): PullRequestInfo {
        require(branchName.isNotBlank()) { "openPullRequest vereist een branch-naam" }
        val existing = byNumber.values.firstOrNull { it.branchName == branchName && !it.merged }
        val pr = existing ?: PullRequest(nextNumber.incrementAndGet(), branchName).also { byNumber[it.number] = it }
        return pr.toInfo()
    }

    /** Alle uitgedeelde PR's — voor test-asserties (bv. "er is precies één PR en die is gemerged"). */
    fun pullRequests(): List<PullRequestInfo> = byNumber.values.map { it.toInfo() }

    override fun ensurePullRequest(
        repoRoot: Path,
        branchName: String,
        baseBranch: String,
        title: String,
        body: String,
    ): PullRequestInfo = openPullRequest(branchName)

    override fun isMerged(targetRepo: String, prNumber: Int): Boolean =
        byNumber[prNumber]?.merged == true

    override fun mergePullRequest(targetRepo: String, prNumber: Int, expectedHeadSha: String) {
        val pr = byNumber[prNumber]
            ?: throw GitHubClientException("Onbekend PR-nummer #$prNumber voor $targetRepo (fake GitHub).")
        if (pr.merged) {
            return
        }
        // Echte lokale squash-merge naar main; een git-fout (bv. conflict) → hetzelfde
        // exceptietype als de echte client, zodat het handler-foutpad identiek blijft.
        runCatching { remote.squashMergeIntoMain(pr.branchName, squashMessage(pr)) }
            .onFailure { throw GitHubClientException("Squash-merge van PR #$prNumber (${pr.branchName}) faalde: ${it.message}") }
        pr.merged = true
    }

    override fun unprocessedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> = emptyList()

    override fun claimedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> = emptyList()

    override fun markCommentClaimed(targetRepo: String, commentId: Long) = Unit

    override fun markCommentDone(targetRepo: String, commentId: Long) = Unit

    override fun markCommentFailed(targetRepo: String, commentId: Long) = Unit

    override fun closePullRequest(targetRepo: String, prNumber: Int) {
        byNumber.remove(prNumber)
    }

    override fun deleteBranch(targetRepo: String, branchName: String) = Unit

    private fun PullRequest.toInfo() = PullRequestInfo(
        number = number,
        url = "http://fake-github.invalid/pr/$number",
        state = if (merged) "MERGED" else "OPEN",
        mergedAt = if (merged) "2026-01-01T00:00:00Z" else null,
    )

    companion object {
        /** Commit-onderwerp van de squash-merge op main — hierop assert de full-flow-test. */
        fun squashMessage(prNumber: Int, branchName: String): String =
            "Squash-merge PR #$prNumber ($branchName)"

        private fun squashMessage(pr: PullRequest): String = squashMessage(pr.number, pr.branchName)
    }
}
