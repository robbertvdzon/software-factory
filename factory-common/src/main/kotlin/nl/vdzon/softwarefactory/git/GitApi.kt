package nl.vdzon.softwarefactory.git

import nl.vdzon.softwarefactory.git.services.GitCommandClient
import java.nio.file.Path

/**
 * Public API of the Git module.
 *
 * The Git module owns low-level Git command execution, repository URL parsing
 * and process execution primitives shared by higher-level modules.
 */
interface GitApi {
    fun clone(repoUrl: String, targetDir: Path, githubToken: String?)

    fun checkoutBase(repoRoot: Path, baseBranch: String, githubToken: String?)

    fun checkoutStoryBranch(
        repoRoot: Path,
        branchName: String,
        baseBranch: String,
        createIfMissing: Boolean,
        githubToken: String?,
    )

    fun recreateLocalBranchFromBase(
        repoRoot: Path,
        branchName: String,
        baseBranch: String,
        githubToken: String?,
    ) {
        throw UnsupportedOperationException("recreateLocalBranchFromBase is not implemented")
    }

    /**
     * Haalt de laatste [baseBranch] op (fetch — auth nodig), zet het lokale base-label gelijk aan
     * `origin/<base>` (zodat een diff tegen `main` weer klopt) en mergt `origin/<base>` in de huidige
     * branch. Bij conflicten blijft de merge "in progress" met conflict-markers in de werkboom, zodat
     * de developer-agent ze kan oplossen. Lokale merge = geen auth nodig.
     */
    fun mergeBaseIntoBranch(repoRoot: Path, baseBranch: String, githubToken: String?): GitMergeResult {
        throw UnsupportedOperationException("mergeBaseIntoBranch is not implemented")
    }

    /** Paden die git nog als onopgelost-gemerged ziet (`diff --name-only --diff-filter=U`). */
    fun unmergedPaths(repoRoot: Path, githubToken: String?): List<String> = emptyList()

    fun commitAll(repoRoot: Path, message: String, githubToken: String?): Boolean

    /**
     * True als de lokale HEAD commits heeft die `origin/<branch>` mist (of als die remote-ref
     * ontbreekt). Nodig omdat een main-merge in de workspace-prep al een merge-commit maakt
     * zónder de werkboom vuil te maken — de post-run-sync moet dan alsnog pushen.
     */
    fun aheadOfRemote(repoRoot: Path, branchName: String, githubToken: String?): Boolean = false

    fun push(repoRoot: Path, branchName: String, githubToken: String?)

    fun remoteBranchExists(repoRoot: Path, branchName: String, githubToken: String?): Boolean

    fun runCommand(
        command: List<String>,
        cwd: Path? = null,
        env: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 60,
    ): GitProcessResult

    fun repositorySlug(repoUrl: String): String?

    companion object {
        fun default(): GitApi = GitCommandClient()
    }
}

data class GitProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val output: String = listOf(stdout, stderr).joinToString("\n").trim()
}

/** Uitkomst van [GitApi.mergeBaseIntoBranch]. */
data class GitMergeResult(
    val clean: Boolean,
    val conflictedFiles: List<String> = emptyList(),
)
