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

    fun commitAll(repoRoot: Path, message: String, githubToken: String?): Boolean

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
