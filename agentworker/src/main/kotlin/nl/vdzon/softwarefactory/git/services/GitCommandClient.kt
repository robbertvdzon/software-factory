package nl.vdzon.softwarefactory.git.services

import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.git.GitMergeResult
import nl.vdzon.softwarefactory.git.GitProcessResult
import nl.vdzon.softwarefactory.support.SupportApi
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText

class GitCommandException(message: String) : RuntimeException(message)

@Component
class GitCommandClient(
    private val processRunner: ProcessRunner = LocalProcessRunner(),
) : GitApi {
    override fun clone(repoUrl: String, targetDir: Path, githubToken: String?) {
        val normalizedTarget = targetDir.toAbsolutePath().normalize()
        if (normalizedTarget.exists()) {
            deleteRecursively(normalizedTarget)
        }
        normalizedTarget.parent.createDirectories()

        val parsed = GitRepositoryUrl.parse(repoUrl)
        val env = gitAuthEnv(normalizedTarget.parent, parsed.githubToken(githubToken))
        requireSuccess(
            processRunner.run(
                listOf("git", "clone", "--depth", "50", parsed.cloneUrl, normalizedTarget.toString()),
                env = env,
                timeoutSeconds = 180,
            ),
            "git clone",
        )
    }

    override fun checkoutBase(repoRoot: Path, baseBranch: String, githubToken: String?) {
        fetch(repoRoot, baseBranch, githubToken)
        requireSuccess(
            runGit(repoRoot, githubToken, "checkout", "-B", baseBranch, "origin/$baseBranch"),
            "git checkout base",
        )
    }

    override fun checkoutStoryBranch(
        repoRoot: Path,
        branchName: String,
        baseBranch: String,
        createIfMissing: Boolean,
        githubToken: String?,
    ) {
        // De story-workspace wordt over alle stappen heen hergebruikt. Een eerdere (gecrashte) stap
        // kan niet-gecommitte wijzigingen in de working tree hebben achtergelaten; dan breekt
        // `git checkout` af met "local changes would be overwritten". Gooi die rommel daarom eerst
        // weg — legitiem werk is op dit punt al door de agent gecommit. Best-effort: de cleanup mag
        // zelf nooit de blokker worden (bv. op een vers-gekloonde repo zonder vuile tree).
        runGit(repoRoot, githubToken, "reset", "--hard")
        runGit(repoRoot, githubToken, "clean", "-fd")

        if (remoteBranchExists(repoRoot, branchName, githubToken)) {
            fetch(repoRoot, branchName, githubToken)
            requireSuccess(
                runGit(repoRoot, githubToken, "checkout", "-B", branchName, "origin/$branchName"),
                "git checkout existing story branch",
            )
            return
        }

        if (!createIfMissing) {
            throw GitCommandException("Remote story branch does not exist: $branchName")
        }

        fetch(repoRoot, baseBranch, githubToken)
        requireSuccess(
            runGit(repoRoot, githubToken, "checkout", "-B", branchName, "origin/$baseBranch"),
            "git checkout new story branch",
        )
    }

    override fun recreateLocalBranchFromBase(
        repoRoot: Path,
        branchName: String,
        baseBranch: String,
        githubToken: String?,
    ) {
        fetch(repoRoot, baseBranch, githubToken)
        requireSuccess(runGit(repoRoot, githubToken, "reset", "--hard"), "git reset --hard")
        requireSuccess(runGit(repoRoot, githubToken, "clean", "-fd"), "git clean")
        requireSuccess(runGit(repoRoot, githubToken, "checkout", "--detach", "origin/$baseBranch"), "git checkout detached base")
        runGit(repoRoot, githubToken, "branch", "-D", branchName)
        requireSuccess(
            runGit(repoRoot, githubToken, "checkout", "-B", branchName, "origin/$baseBranch"),
            "git recreate story branch",
        )
        requireSuccess(runGit(repoRoot, githubToken, "clean", "-fd"), "git clean recreated branch")
    }

    override fun mergeBaseIntoBranch(repoRoot: Path, baseBranch: String, githubToken: String?): GitMergeResult {
        fetch(repoRoot, baseBranch, githubToken)
        // Houd het lokale base-label gelijk aan origin, zodat een diff tegen `main` het juiste
        // aftakpunt gebruikt (anders blijft `main` op het clone-commit hangen → vervuilde diff).
        requireSuccess(
            runGit(repoRoot, githubToken, "branch", "-f", baseBranch, "origin/$baseBranch"),
            "git branch -f base",
        )
        val merge = runGit(repoRoot, githubToken, "merge", "--no-edit", "origin/$baseBranch")
        if (merge.exitCode == 0) {
            return GitMergeResult(clean = true)
        }
        val conflicted = unmergedPaths(repoRoot, githubToken)
        if (conflicted.isEmpty()) {
            // Geen merge-conflict maar een andere fout → afbreken en doorgooien.
            runGit(repoRoot, githubToken, "merge", "--abort")
            throw GitCommandException(
                "git merge origin/$baseBranch failed: ${SupportApi.default().redact(merge.output).take(500)}",
            )
        }
        return GitMergeResult(clean = false, conflictedFiles = conflicted)
    }

    override fun unmergedPaths(repoRoot: Path, githubToken: String?): List<String> {
        val result = runGit(repoRoot, githubToken, "diff", "--name-only", "--diff-filter=U")
        if (result.exitCode != 0) {
            return emptyList()
        }
        return result.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }
    }

    override fun commitAll(repoRoot: Path, message: String, githubToken: String?): Boolean {
        configureAuthor(repoRoot, githubToken)
        requireSuccess(runGit(repoRoot, githubToken, "add", "-A"), "git add")
        val status = runGit(repoRoot, githubToken, "status", "--porcelain")
        requireSuccess(status, "git status")
        if (status.stdout.isBlank()) {
            return false
        }
        requireSuccess(runGit(repoRoot, githubToken, "commit", "-m", message), "git commit")
        return true
    }

    override fun push(repoRoot: Path, branchName: String, githubToken: String?) {
        requireSuccess(
            runGit(repoRoot, githubToken, "push", "-u", "origin", branchName),
            "git push",
        )
    }

    override fun remoteBranchExists(repoRoot: Path, branchName: String, githubToken: String?): Boolean {
        val result = runGit(repoRoot, githubToken, "ls-remote", "--exit-code", "--heads", "origin", branchName)
        return when (result.exitCode) {
            0 -> true
            2 -> false
            else -> throw GitCommandException("git ls-remote failed: ${SupportApi.default().redact(result.output)}")
        }
    }

    override fun runCommand(
        command: List<String>,
        cwd: Path?,
        env: Map<String, String>,
        timeoutSeconds: Long,
    ): GitProcessResult =
        processRunner.run(command, cwd, env, timeoutSeconds)
            .let { GitProcessResult(it.exitCode, it.stdout, it.stderr) }

    override fun repositorySlug(repoUrl: String): String? =
        GitRepositoryUrl.parse(repoUrl).slug

    private fun fetch(repoRoot: Path, branchName: String, githubToken: String?) {
        requireSuccess(
            runGit(repoRoot, githubToken, "fetch", "origin", "+refs/heads/$branchName:refs/remotes/origin/$branchName"),
            "git fetch",
        )
    }

    private fun configureAuthor(repoRoot: Path, githubToken: String?) {
        requireSuccess(runGit(repoRoot, githubToken, "config", "user.email", "software-factory@example.invalid"), "git config email")
        requireSuccess(runGit(repoRoot, githubToken, "config", "user.name", "Software Factory"), "git config name")
    }

    private fun runGit(repoRoot: Path, githubToken: String?, vararg args: String): ProcessResult =
        processRunner.run(
            command = listOf("git", *args),
            cwd = repoRoot,
            env = gitAuthEnv(repoRoot.parent, if (isGithubRepository(repoRoot)) githubToken else null),
            timeoutSeconds = 120,
        )

    private fun isGithubRepository(repoRoot: Path): Boolean =
        runCatching {
            repoRoot.resolve(".git/config").toFile().readText().contains("github.com", ignoreCase = true)
        }.getOrDefault(false)

    private fun GitRepositoryUrl.githubToken(githubToken: String?): String? =
        if (slug == null) null else githubToken

    private fun gitAuthEnv(workspaceRoot: Path, githubToken: String?): Map<String, String> {
        if (githubToken.isNullOrBlank()) {
            return mapOf("GIT_TERMINAL_PROMPT" to "0")
        }
        val askPass = workspaceRoot.resolve(".git-askpass")
        if (!askPass.exists()) {
            askPass.writeText(
                """
                #!/bin/sh
                case "${'$'}1" in
                  *Username*) echo x-access-token ;;
                  *) echo "${'$'}SF_GITHUB_TOKEN" ;;
                esac
                """.trimIndent() + "\n",
            )
            runCatching {
                Files.setPosixFilePermissions(askPass, PosixFilePermissions.fromString("rwx------"))
            }
        }
        return mapOf(
            "GIT_TERMINAL_PROMPT" to "0",
            "GIT_ASKPASS" to askPass.toString(),
            "SF_GITHUB_TOKEN" to githubToken,
        )
    }

    private fun requireSuccess(result: ProcessResult, action: String) {
        if (result.exitCode != 0) {
            throw GitCommandException("$action failed: ${SupportApi.default().redact(result.output).take(1000)}")
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!path.exists()) {
            return
        }
        require(path.isDirectory()) { "Refusing to delete non-directory path: $path" }
        val stream = Files.walk(path)
        try {
            stream.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
        } finally {
            stream.close()
        }
    }
}
