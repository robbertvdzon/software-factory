package nl.vdzon.softwarefactory.github.clients

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.github.GitHubClientException
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.github.PullRequestInfo
import nl.vdzon.softwarefactory.core.AgentComments
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.git.GitProcessResult
import nl.vdzon.softwarefactory.support.SupportApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
class GitHubCliClient(
    private val git: GitApi = GitApi.default(),
    private val factorySecrets: FactorySecrets? = null,
) : GitHubApi {
    private val objectMapper = jacksonObjectMapper()
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun ensurePullRequest(
        repoRoot: Path,
        branchName: String,
        baseBranch: String,
        title: String,
        body: String,
    ): PullRequestInfo {
        findOpenPullRequest(repoRoot, branchName)?.let { return it }
        val created = runGh(
            cwd = repoRoot,
            args = listOf(
                "pr",
                "create",
                "--base",
                baseBranch,
                "--head",
                branchName,
                "--title",
                title,
                "--body",
                body,
            ),
            timeoutSeconds = 120,
        )
        if (created.exitCode == 0) {
            return findOpenPullRequest(repoRoot, branchName)
                ?: PullRequestInfo(number = 0, url = created.stdout.trim().takeIf { it.isNotBlank() })
        }

        // If another run created it first, reuse the branch PR.
        return findOpenPullRequest(repoRoot, branchName)
            ?: throw GitHubClientException("gh pr create failed: ${SupportApi.default().redact(created.output).take(1000)}")
    }

    override fun isMerged(targetRepo: String, prNumber: Int): Boolean {
        val slug = requireSlug(targetRepo)
        val result = runGh(
            args = listOf("pr", "view", prNumber.toString(), "--repo", slug, "--json", "number,url,state,mergedAt"),
        )
        requireSuccess(result, "gh pr view")
        return parsePullRequest(result.stdout).isMerged
    }

    override fun unprocessedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> {
        val slug = requireSlug(targetRepo)
        return factoryComments(slug, prNumber)
            .filter { comment -> reactions(slug, comment.id).none { it in processedReactionContent } }
    }

    override fun claimedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> {
        val slug = requireSlug(targetRepo)
        return factoryComments(slug, prNumber)
            .filter { comment ->
                val reactions = reactions(slug, comment.id)
                "eyes" in reactions && "rocket" !in reactions && "confused" !in reactions
            }
    }

    override fun markCommentClaimed(targetRepo: String, commentId: Long) {
        createReaction(targetRepo, commentId, "eyes")
    }

    override fun markCommentDone(targetRepo: String, commentId: Long) {
        createReaction(targetRepo, commentId, "rocket")
    }

    override fun markCommentFailed(targetRepo: String, commentId: Long) {
        createReaction(targetRepo, commentId, "confused")
    }

    override fun closePullRequest(targetRepo: String, prNumber: Int) {
        val slug = requireSlug(targetRepo)
        val result = runGh(args = listOf("pr", "close", prNumber.toString(), "--repo", slug))
        requireSuccess(result, "gh pr close")
    }

    override fun deleteBranch(targetRepo: String, branchName: String) {
        val slug = requireSlug(targetRepo)
        val result = runGh(
            args = listOf("api", "-X", "DELETE", "repos/$slug/git/refs/heads/$branchName"),
        )
        if (result.exitCode != 0 && !result.output.contains("Reference does not exist", ignoreCase = true)) {
            requireSuccess(result, "gh api delete branch")
        }
    }

    override fun mergePullRequest(targetRepo: String, prNumber: Int) {
        val slug = requireSlug(targetRepo)
        val result = runGh(args = listOf("pr", "merge", prNumber.toString(), "--repo", slug, "--squash", "--delete-branch"))
        if (result.exitCode == 0) {
            return
        }
        // `gh pr merge` kan een non-zero exit geven terwijl de merge op GitHub tóch een feit is
        // (bv. een transiente 401 op een vervolg-call, of de PR was al gemerged). Verifieer daarom
        // de echte PR-status: is 'ie MERGED, dan is de merge gelukt → geen false failure.
        if (isPullRequestMerged(slug, prNumber)) {
            logger.warn(
                "gh pr merge gaf exitCode={} maar PR #{} is op GitHub MERGED → behandeld als succes.",
                result.exitCode,
                prNumber,
            )
            return
        }
        requireSuccess(result, "gh pr merge")
    }

    override fun latestCommitSha(targetRepo: String, branch: String): String? {
        val slug = git.repositorySlug(targetRepo) ?: run {
            logger.warn("latestCommitSha: geen github-slug voor {}; kan verwachte SHA niet bepalen.", targetRepo)
            return null
        }
        val result = runGh(args = listOf("api", "repos/$slug/commits/$branch", "-q", ".sha"))
        if (result.exitCode != 0) {
            logger.warn("latestCommitSha mislukt voor {}@{}: exitCode={}", slug, branch, result.exitCode)
            return null
        }
        return result.stdout.trim().takeIf { it.isNotBlank() }
    }

    /** Vraagt de actuele PR-status op; true zodra GitHub de PR als gemerged rapporteert. */
    private fun isPullRequestMerged(slug: String, prNumber: Int): Boolean {
        val result = runGh(args = listOf("pr", "view", prNumber.toString(), "--repo", slug, "--json", "state,mergedAt"))
        if (result.exitCode != 0) {
            logger.warn("Kon PR-status niet ophalen voor #{} (repo={}): exitCode={}", prNumber, slug, result.exitCode)
            return false
        }
        val node = objectMapper.readTree(result.stdout)
        val merged = node.path("state").asText("").equals("MERGED", ignoreCase = true) ||
            node.path("mergedAt").asText("").let { it.isNotBlank() && it != "null" }
        return merged
    }

    private fun findOpenPullRequest(repoRoot: Path, branchName: String): PullRequestInfo? {
        val result = runGh(
            cwd = repoRoot,
            args = listOf("pr", "list", "--head", branchName, "--state", "open", "--limit", "1", "--json", "number,url,state"),
        )
        requireSuccess(result, "gh pr list")
        return objectMapper.readTree(result.stdout).firstOrNull()?.let(::parsePullRequest)
    }

    private fun factoryComments(slug: String, prNumber: Int): List<PullRequestComment> {
        val result = runGh(
            args = listOf("api", "repos/$slug/issues/$prNumber/comments"),
            timeoutSeconds = 60,
        )
        requireSuccess(result, "gh api issue comments")
        return objectMapper.readTree(result.stdout)
            .filter { comment ->
                val body = comment.path("body").asText("")
                // Voorheen liep dit via een optionele YouTrackApi-dependency, maar die delegeerde
                // óók gewoon naar de prefix-check; nu rechtstreeks, zonder youtrack-koppeling.
                body.contains("@factory", ignoreCase = true) && !AgentComments.isAgentComment(body)
            }
            .map { comment ->
                PullRequestComment(
                    id = comment.path("id").asLong(),
                    body = comment.path("body").asText(""),
                )
            }
    }

    private fun reactions(slug: String, commentId: Long): Set<String> {
        val result = runGh(
            args = listOf(
                "api",
                "-H",
                "Accept: application/vnd.github+json",
                "repos/$slug/issues/comments/$commentId/reactions",
            ),
        )
        requireSuccess(result, "gh api comment reactions")
        return objectMapper.readTree(result.stdout)
            .map { reaction -> reaction.path("content").asText("") }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun createReaction(targetRepo: String, commentId: Long, content: String) {
        val slug = requireSlug(targetRepo)
        val result = runGh(
            args = listOf(
                "api",
                "-X",
                "POST",
                "-H",
                "Accept: application/vnd.github+json",
                "repos/$slug/issues/comments/$commentId/reactions",
                "-f",
                "content=$content",
            ),
        )
        requireSuccess(result, "gh api create reaction")
    }

    private fun parsePullRequest(json: String): PullRequestInfo =
        parsePullRequest(objectMapper.readTree(json))

    private fun parsePullRequest(node: JsonNode): PullRequestInfo =
        PullRequestInfo(
            number = node.path("number").asInt(),
            url = node.path("url").asText().takeIf { it.isNotBlank() },
            state = node.path("state").asText().takeIf { it.isNotBlank() },
            mergedAt = node.path("mergedAt").asText().takeIf { it.isNotBlank() && it != "null" },
        )

    private fun runGh(
        args: List<String>,
        cwd: Path? = null,
        timeoutSeconds: Long = 60,
    ): GitProcessResult {
        // De token zit in de env (GH_TOKEN via ghEnv()), niet in de args — args loggen is dus veilig.
        // Output gaat altijd door redact() voordat 'ie in de log belandt.
        val startedNanos = System.nanoTime()
        val result = git.runCommand(
            command = listOf("gh", *args.toTypedArray()),
            cwd = cwd,
            env = ghEnv(),
            timeoutSeconds = timeoutSeconds,
        )
        val durationMs = (System.nanoTime() - startedNanos) / 1_000_000
        val cmd = args.joinToString(" ")
        if (result.exitCode != 0) {
            logger.warn(
                "gh command failed: cmd=[gh {}] exitCode={} durationMs={} hasToken={} output={}",
                cmd,
                result.exitCode,
                durationMs,
                ghEnv().containsKey("GH_TOKEN"),
                SupportApi.default().redact(result.output),
            )
        } else {
            logger.debug("gh command ok: cmd=[gh {}] durationMs={}", cmd, durationMs)
        }
        return result
    }

    private fun ghEnv(): Map<String, String> =
        // TODO(fase 3): via ConfigApi
        (factorySecrets?.githubToken ?: System.getenv("SF_GITHUB_TOKEN"))?.takeIf { it.isNotBlank() }?.let { token ->
            mapOf("GH_TOKEN" to token)
        } ?: emptyMap()

    private fun requireSlug(targetRepo: String): String =
        git.repositorySlug(targetRepo)
            ?: throw GitHubClientException("Only github.com repositories are supported for PR operations: $targetRepo")

    private fun requireSuccess(result: GitProcessResult, action: String) {
        if (result.exitCode != 0) {
            throw GitHubClientException("$action failed: ${SupportApi.default().redact(result.output).take(1000)}")
        }
    }

    companion object {
        private val processedReactionContent = setOf("eyes", "rocket", "confused")
    }
}
