package nl.vdzon.softwarefactory.verification

import nl.vdzon.softwarefactory.git.GitApi
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

data class CheckoutIdentity(
    val headSha: String,
    /** Git-tree van de werkelijke working tree, inclusief tracked en niet-genegeerde untracked files. */
    val treeSha: String,
)

/** Berekent een worktree-tree via een tijdelijk index en muteert de echte Git-index/worktree niet. */
class CheckoutIdentityResolver(
    private val gitApi: GitApi = GitApi.default(),
) {
    fun resolve(repoRoot: Path): CheckoutIdentity? {
        val head = git(repoRoot, listOf("rev-parse", "HEAD"))?.takeIf(SHA::matches) ?: return null
        val temporaryIndex = Files.createTempFile("factory-verification-index-", ".gitindex")
        return try {
            temporaryIndex.deleteIfExists()
            val env = mapOf("GIT_INDEX_FILE" to temporaryIndex.toString())
            val readTree = git(repoRoot, listOf("read-tree", "HEAD"), env)
            val add = git(repoRoot, listOf("add", "-A", "--", "."), env)
            val tree = git(repoRoot, listOf("write-tree"), env)?.takeIf(SHA::matches)
            tree?.takeIf { readTree != null && add != null }?.let { CheckoutIdentity(head, it) }
        } finally {
            temporaryIndex.deleteIfExists()
        }
    }

    private fun git(repoRoot: Path, args: List<String>, env: Map<String, String> = emptyMap()): String? {
        val result = gitApi.runCommand(listOf("git") + args, cwd = repoRoot, env = env, timeoutSeconds = 30)
        return result.stdout.trim().takeIf { result.exitCode == 0 }
    }

    private companion object {
        val SHA = Regex("^[0-9a-fA-F]{40,64}$")
    }
}
