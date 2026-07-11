package nl.vdzon.softwarefactory.merge.internal

import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.github.GitHubClientException
import nl.vdzon.softwarefactory.github.PullRequestChecksResult
import nl.vdzon.softwarefactory.github.PullRequestHeadChangedException
import nl.vdzon.softwarefactory.merge.PullRequestMergeResult
import nl.vdzon.softwarefactory.merge.PullRequestMergeService
import org.springframework.stereotype.Component

@Component
class ProjectAwarePullRequestMergeService(
    private val gitHubApi: GitHubApi,
    private val projectRepoResolver: ProjectRepoResolver,
) : PullRequestMergeService {

    init {
        projectRepoResolver.requireCompleteMergePolicies()
    }

    override fun merge(
        projectName: String?,
        targetRepo: String,
        prNumber: Int,
        beforeMerge: () -> Unit,
    ): PullRequestMergeResult {
        val requiredChecks = projectRepoResolver.requiredChecksFor(projectName)
            .ifEmpty { projectRepoResolver.requiredChecksForRepo(targetRepo) }
        if (requiredChecks.isEmpty()) {
            return PullRequestMergeResult.Blocked(
                "Geen niet-lege merge.requiredChecks-policy voor project '${projectName ?: "<leeg>"}'.",
            )
        }
        return try {
            when (val readiness = gitHubApi.requiredChecks(targetRepo, prNumber, requiredChecks)) {
                is PullRequestChecksResult.Pending -> PullRequestMergeResult.Pending(readiness.reason)
                is PullRequestChecksResult.Blocked -> PullRequestMergeResult.Blocked(readiness.reason)
                is PullRequestChecksResult.Ready -> mergeReady(
                    targetRepo = targetRepo,
                    prNumber = prNumber,
                    verifiedHeadSha = readiness.verifiedHeadSha,
                    beforeMerge = beforeMerge,
                )
            }
        } catch (exception: GitHubClientException) {
            PullRequestMergeResult.Blocked("GitHub-bewijs kon niet betrouwbaar worden beoordeeld: ${exception.message}")
        } catch (exception: RuntimeException) {
            PullRequestMergeResult.Blocked("Mergepolicy kon niet betrouwbaar worden uitgevoerd: ${exception.message}")
        }
    }

    private fun mergeReady(
        targetRepo: String,
        prNumber: Int,
        verifiedHeadSha: String,
        beforeMerge: () -> Unit,
    ): PullRequestMergeResult = try {
        beforeMerge()
        gitHubApi.mergePullRequest(targetRepo, prNumber, verifiedHeadSha)
        PullRequestMergeResult.Merged(verifiedHeadSha)
    } catch (exception: PullRequestHeadChangedException) {
        PullRequestMergeResult.Pending(exception.message ?: "PR-head wijzigde; checks worden opnieuw beoordeeld.")
    } catch (exception: GitHubClientException) {
        PullRequestMergeResult.Blocked("GitHub weigerde de merge: ${exception.message}")
    }
}
