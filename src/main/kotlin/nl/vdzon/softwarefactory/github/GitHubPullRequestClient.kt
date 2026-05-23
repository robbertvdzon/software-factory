package nl.vdzon.softwarefactory.github

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.git.GitRepositoryUrl
import nl.vdzon.softwarefactory.git.ProcessRunner
import nl.vdzon.softwarefactory.git.ProcessResult
import nl.vdzon.softwarefactory.git.LocalProcessRunner
import nl.vdzon.softwarefactory.jira.JiraCommentParser
import nl.vdzon.softwarefactory.runtime.SecretRedactor
import org.springframework.stereotype.Component
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

interface PullRequestClient {
    fun ensurePullRequest(repoRoot: Path, branchName: String, baseBranch: String, title: String, body: String): PullRequestInfo

    fun isMerged(targetRepo: String, prNumber: Int): Boolean

    fun unprocessedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment>

    fun markCommentClaimed(targetRepo: String, commentId: Long)
}

class GitHubClientException(message: String) : RuntimeException(message)

@Component
class GitHubCliPullRequestClient(
    private val processRunner: ProcessRunner = LocalProcessRunner(),
    private val factorySecrets: FactorySecrets? = null,
) : PullRequestClient {
    private val objectMapper = jacksonObjectMapper()

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
            ?: throw GitHubClientException("gh pr create failed: ${SecretRedactor.redact(created.output).take(1000)}")
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
        val result = runGh(
            args = listOf("api", "repos/$slug/issues/$prNumber/comments"),
            timeoutSeconds = 60,
        )
        requireSuccess(result, "gh api issue comments")
        return objectMapper.readTree(result.stdout)
            .filter { comment ->
                val body = comment.path("body").asText("")
                body.contains("@factory", ignoreCase = true) && !JiraCommentParser.isAgentComment(body)
            }
            .map { comment ->
                PullRequestComment(
                    id = comment.path("id").asLong(),
                    body = comment.path("body").asText(""),
                )
            }
            .filterNot { comment -> hasProcessedReaction(slug, comment.id) }
    }

    override fun markCommentClaimed(targetRepo: String, commentId: Long) {
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
                "content=eyes",
            ),
        )
        requireSuccess(result, "gh api create reaction")
    }

    private fun findOpenPullRequest(repoRoot: Path, branchName: String): PullRequestInfo? {
        val result = runGh(
            cwd = repoRoot,
            args = listOf("pr", "list", "--head", branchName, "--state", "open", "--limit", "1", "--json", "number,url,state"),
        )
        requireSuccess(result, "gh pr list")
        return objectMapper.readTree(result.stdout).firstOrNull()?.let(::parsePullRequest)
    }

    private fun hasProcessedReaction(slug: String, commentId: Long): Boolean {
        val result = runGh(
            args = listOf(
                "api",
                "-H",
                "Accept: application/vnd.github+json",
                "repos/$slug/issues/comments/$commentId/reactions",
            ),
        )
        requireSuccess(result, "gh api comment reactions")
        return objectMapper.readTree(result.stdout).any { reaction ->
            reaction.path("content").asText("") in processedReactionContent
        }
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
    ): ProcessResult =
        processRunner.run(
            command = listOf("gh", *args.toTypedArray()),
            cwd = cwd,
            env = ghEnv(),
            timeoutSeconds = timeoutSeconds,
        )

    private fun ghEnv(): Map<String, String> =
        (factorySecrets?.githubToken ?: System.getenv("SF_GITHUB_TOKEN"))?.takeIf { it.isNotBlank() }?.let { token ->
            mapOf("GH_TOKEN" to token)
        } ?: emptyMap()

    private fun requireSlug(targetRepo: String): String =
        GitRepositoryUrl.parse(targetRepo).slug
            ?: throw GitHubClientException("Only github.com repositories are supported for PR operations: $targetRepo")

    private fun requireSuccess(result: ProcessResult, action: String) {
        if (result.exitCode != 0) {
            throw GitHubClientException("$action failed: ${SecretRedactor.redact(result.output).take(1000)}")
        }
    }

    companion object {
        private val processedReactionContent = setOf("eyes", "rocket", "confused")
    }
}
