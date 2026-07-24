package nl.vdzon.softwarefactory.github.clients

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.github.GitHubClientException
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.github.PullRequestInfo
import nl.vdzon.softwarefactory.github.PullRequestCheck
import nl.vdzon.softwarefactory.github.PullRequestChecksResult
import nl.vdzon.softwarefactory.github.PullRequestHeadChangedException
import nl.vdzon.softwarefactory.github.PullRequestMergeInfo
import nl.vdzon.softwarefactory.core.AgentComments
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.git.GitProcessResult
import nl.vdzon.softwarefactory.support.SupportApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

@Component
class GitHubCliClient(
    private val git: GitApi = GitApi.default(),
    private val factorySecrets: FactorySecrets? = null,
    private val clock: Clock = Clock.systemUTC(),
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

    override fun mergePullRequest(targetRepo: String, prNumber: Int, expectedHeadSha: String) {
        val slug = requireSlug(targetRepo)
        val result = runGh(
            args = listOf(
                "pr", "merge", prNumber.toString(), "--repo", slug, "--squash", "--delete-branch",
                "--match-head-commit", expectedHeadSha,
            ),
        )
        if (result.exitCode == 0) {
            return
        }
        // `gh pr merge` kan een non-zero exit geven terwijl de merge op GitHub tóch een feit is
        // (bv. een transiente 401 op een vervolg-call, of de PR was al gemerged). Verifieer daarom
        // de echte PR-status: is 'ie MERGED, dan is de merge gelukt → geen false failure.
        val state = pullRequestState(slug, prNumber)
        if (state?.merged == true) {
            logger.warn(
                "gh pr merge gaf exitCode={} maar PR #{} is op GitHub MERGED → behandeld als succes.",
                result.exitCode,
                prNumber,
            )
            return
        }
        if (state?.headSha != null && state.headSha != expectedHeadSha) {
            throw PullRequestHeadChangedException(expectedHeadSha, state.headSha)
        }
        requireSuccess(result, "gh pr merge")
    }

    override fun requiredChecks(
        targetRepo: String,
        prNumber: Int,
        requiredNames: Set<String>,
    ): PullRequestChecksResult {
        val slug = requireSlug(targetRepo)
        val headResult = runGh(
            args = listOf("pr", "view", prNumber.toString(), "--repo", slug, "--json", "headRefOid"),
        )
        if (headResult.exitCode != 0) {
            return PullRequestChecksResult.Blocked(
                "Kon actuele PR-head niet ophalen: ${SupportApi.default().redact(headResult.output).take(500)}",
            )
        }
        val headSha = runCatching {
            objectMapper.readTree(headResult.stdout).path("headRefOid").asText("").trim()
        }.getOrElse { exception ->
            return PullRequestChecksResult.Blocked("Ongeldige GitHub-PR-respons: ${exception.message}")
        }
        if (headSha.isEmpty()) {
            return PullRequestChecksResult.Blocked("GitHub rapporteerde geen actuele PR-head-SHA.")
        }
        val result = runGh(args = listOf("api", "repos/$slug/commits/$headSha/check-runs?per_page=100"))
        if (result.exitCode != 0) {
            return PullRequestChecksResult.Blocked(
                "Kon GitHub-checks voor head $headSha niet ophalen: " +
                    SupportApi.default().redact(result.output).take(500),
            )
        }
        val checks = runCatching {
            objectMapper.readTree(result.stdout).path("check_runs").map { node ->
                val status = node.path("status").asText("")
                val conclusion = node.path("conclusion").asText("")
                PullRequestCheck(
                    name = node.path("name").asText(""),
                    state = conclusion.ifBlank { status },
                    bucket = checkBucket(status, conclusion),
                    link = node.path("details_url").asText().takeIf { it.isNotBlank() },
                    id = node.path("id").asLong(0),
                )
            }
        }.getOrElse { exception ->
            return PullRequestChecksResult.Blocked("Ongeldige GitHub-checkrespons: ${exception.message}")
        }
        val byName = checks.groupBy { it.name }.mapValues { (_, runs) -> runs.maxBy { it.id } }
        val missing = requiredNames - byName.keys
        if (missing.isNotEmpty()) {
            // Vlak na een push heeft GitHub Actions de check-run soms nog niet eens aangemaakt
            // (queue-vertraging) — dat is niet te onderscheiden van een écht ontbrekende check
            // (verkeerde job-naam / workflow triggert nooit, zie projects.yaml @robberts-assistent,
            // ontdekt bij SF-948/949/950). Behandel "ontbreekt" daarom als Pending zolang de laatste
            // push nog vers is (zelfde soepele auto-retry als een lopende check); blijft de check na
            // de coulanceperiode nog steeds afwezig, dan is het weer een harde Blocked zoals voorheen
            // (SF-1082/SF-1111 liepen hier op vast: check nog niet gerapporteerd, kort erna al groen —
            // bij dit repo's eigen aggregatiecheck kan dat oplopen tot ruim 5 min door mvn verify).
            if (withinMissingCheckGracePeriod(slug, headSha)) {
                return PullRequestChecksResult.Pending(
                    "Verplichte GitHub-check(s) zijn nog niet gerapporteerd (< ${MISSING_CHECK_GRACE_PERIOD.toMinutes()} min na de laatste push): " +
                        missing.sorted().joinToString(),
                    checks,
                )
            }
            return PullRequestChecksResult.Blocked("Verplichte GitHub-check(s) ontbreken: ${missing.sorted().joinToString()}", checks)
        }
        val required = requiredNames.mapNotNull(byName::get)
        val pending = required.filter { it.bucket == "pending" }
        if (pending.isNotEmpty()) {
            val details = pending.joinToString { "${it.name}=${it.state}" }
            return PullRequestChecksResult.Pending("Verplichte GitHub-check(s) lopen nog: $details", checks)
        }
        val blocked = required.filterNot { it.bucket == "pass" }
        if (blocked.isNotEmpty()) {
            val details = blocked.joinToString { "${it.name}=${it.state}" }
            return PullRequestChecksResult.Blocked("Verplichte GitHub-check(s) zijn geblokkeerd: $details", checks)
        }
        return PullRequestChecksResult.Ready(headSha, checks)
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

    override fun changedFiles(targetRepo: String, prNumber: Int): List<String>? {
        val slug = git.repositorySlug(targetRepo) ?: run {
            logger.warn("changedFiles: geen github-slug voor {}; kan story-diff niet bepalen.", targetRepo)
            return null
        }
        // --paginate + -q vlakt de (mogelijk >100 bestanden, meerdere pagina's) JSON-respons plat naar
        // één bestandsnaam per regel; werkt ook voor een al gemergede/gesloten PR.
        val result = runGh(args = listOf("api", "repos/$slug/pulls/$prNumber/files", "--paginate", "-q", ".[].filename"))
        if (result.exitCode != 0) {
            logger.warn("changedFiles mislukt voor {}#{}: exitCode={}", slug, prNumber, result.exitCode)
            return null
        }
        return result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    override fun mergeInfo(targetRepo: String, prNumber: Int): PullRequestMergeInfo? {
        val slug = git.repositorySlug(targetRepo) ?: run {
            logger.warn("mergeInfo: geen github-slug voor {}; kan merge-commit niet bepalen.", targetRepo)
            return null
        }
        val result = runGh(args = listOf("api", "repos/$slug/pulls/$prNumber"))
        if (result.exitCode != 0) {
            logger.warn("mergeInfo mislukt voor {}#{}: exitCode={}", slug, prNumber, result.exitCode)
            return null
        }
        return runCatching {
            val node = objectMapper.readTree(result.stdout)
            val mergeCommitSha = node.path("merge_commit_sha").asText().takeIf { it.isNotBlank() && it != "null" }
            val mergedAtText = node.path("merged_at").asText().takeIf { it.isNotBlank() && it != "null" }
            val mergedAt = mergedAtText?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            PullRequestMergeInfo(mergeCommitSha, mergedAt)
        }.getOrElse {
            logger.warn("mergeInfo: ongeldige GitHub-respons voor {}#{}: {}", slug, prNumber, it.message)
            null
        }
    }

    override fun isAncestor(targetRepo: String, ancestorSha: String, descendantSha: String): Boolean? {
        if (ancestorSha.isBlank() || descendantSha.isBlank()) return null
        val slug = git.repositorySlug(targetRepo) ?: run {
            logger.warn("isAncestor: geen github-slug voor {}; kan ancestor-check niet uitvoeren.", targetRepo)
            return null
        }
        val result = runGh(args = listOf("api", "repos/$slug/compare/$ancestorSha...$descendantSha", "-q", ".status"))
        if (result.exitCode != 0) {
            logger.warn(
                "isAncestor mislukt voor {} ({}...{}): exitCode={}",
                slug, ancestorSha, descendantSha, result.exitCode,
            )
            return null
        }
        return when (result.stdout.trim().lowercase()) {
            // "identical": beide SHA's wijzen op dezelfde commit -> ancestorSha is (triviaal) een
            // voorouder van descendantSha. "ahead": descendantSha heeft extra commits bovenop
            // ancestorSha -> ancestorSha zit in de geschiedenis van descendantSha.
            "identical", "ahead" -> true
            // "behind": descendantSha is juist OUDER dan ancestorSha (geen ancestor). "diverged":
            // geen van beide is een voorouder van de ander.
            "behind", "diverged" -> false
            else -> null
        }
    }

    private data class PullRequestState(val merged: Boolean, val headSha: String?)

    /** Vraagt na een mislukte merge de onomkeerbare GitHub-status en actuele head opnieuw op. */
    private fun pullRequestState(slug: String, prNumber: Int): PullRequestState? {
        val result = runGh(
            args = listOf("pr", "view", prNumber.toString(), "--repo", slug, "--json", "state,mergedAt,headRefOid"),
        )
        if (result.exitCode != 0) {
            logger.warn("Kon PR-status niet ophalen voor #{} (repo={}): exitCode={}", prNumber, slug, result.exitCode)
            return null
        }
        val node = objectMapper.readTree(result.stdout)
        val merged = node.path("state").asText("").equals("MERGED", ignoreCase = true) ||
            node.path("mergedAt").asText("").let { it.isNotBlank() && it != "null" }
        return PullRequestState(merged, node.path("headRefOid").asText("").takeIf { it.isNotBlank() })
    }

    /** True zolang [headSha] korter dan [MISSING_CHECK_GRACE_PERIOD] geleden gecommit is; false bij twijfel (geen commit-datum → geen coulance). */
    private fun withinMissingCheckGracePeriod(slug: String, headSha: String): Boolean {
        val pushedAt = commitTimestamp(slug, headSha) ?: return false
        return Duration.between(pushedAt, Instant.now(clock)) < MISSING_CHECK_GRACE_PERIOD
    }

    private fun commitTimestamp(slug: String, sha: String): Instant? {
        val result = runGh(args = listOf("api", "repos/$slug/commits/$sha"))
        if (result.exitCode != 0) {
            return null
        }
        return runCatching {
            val dateText = objectMapper.readTree(result.stdout)
                .path("commit").path("committer").path("date").asText("")
            Instant.parse(dateText)
        }.getOrNull()
    }

    private fun checkBucket(status: String, conclusion: String): String = when {
        !status.equals("completed", ignoreCase = true) -> "pending"
        conclusion.equals("success", ignoreCase = true) -> "pass"
        else -> "blocked"
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
                // Voorheen liep dit via een optionele TrackerApi-dependency, maar die delegeerde
                // óók gewoon naar de prefix-check; nu rechtstreeks, zonder tracker-koppeling.
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
        // 3 min bleek te krap voor dit repo's eigen "Repository verification"-aggregatiecheck:
        // die ontstaat pas nadat backend-verification (mvn verify, doorgaans ~5 min) klaar is,
        // dus de check bestaat structureel nog niet binnen 3 min na de push (SF-1111, 2026-07-18).
        private val MISSING_CHECK_GRACE_PERIOD: Duration = Duration.ofMinutes(20)
    }
}
