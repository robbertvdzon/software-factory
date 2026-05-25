package nl.vdzon.softwarefactory.agentworker.flows

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.docs.DeploymentConfig
import nl.vdzon.softwarefactory.docs.DocsApi
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.youtrack.AgentRole
import nl.vdzon.softwarefactory.agent.AgentEvent
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

data class TargetRepositorySession(
    val repoRoot: Path,
    val repoUrl: String,
    val baseBranch: String,
    val branchPrefix: String,
    val branchName: String,
    val deploymentConfig: DeploymentConfig,
)

data class DeveloperRepositoryResult(
    val branchName: String,
    val prNumber: Int,
    val prUrl: String?,
    val committed: Boolean,
    val completionEvent: AgentEvent,
)

class TargetRepositoryPreparer(
    private val git: GitApi = GitApi.default(),
    private val docs: DocsApi = DocsApi.default(),
) {
    fun prepare(env: Map<String, String>, ticketKey: String, role: AgentRole): TargetRepositorySession? {
        val repoUrl = env["SF_REPO_URL"]?.takeIf { it.isNotBlank() } ?: return null
        val repoRoot = Path.of(env["SF_REPO_ROOT"] ?: "/work/repo")
        val githubToken = env["SF_GITHUB_TOKEN"]

        git.clone(repoUrl, repoRoot, githubToken)
        val config = docs.loadFactoryDocs(role, repoRoot).deploymentConfig ?: DeploymentConfig()
        val branchName = config.branchPrefix + ticketKey

        when (role) {
            AgentRole.REFINER -> git.checkoutBase(repoRoot, config.defaultBaseBranch, githubToken)
            AgentRole.DEVELOPER -> git.checkoutStoryBranch(
                repoRoot = repoRoot,
                branchName = branchName,
                baseBranch = config.defaultBaseBranch,
                createIfMissing = true,
                githubToken = githubToken,
            )
            AgentRole.REVIEWER,
            AgentRole.TESTER,
            -> git.checkoutStoryBranch(
                repoRoot = repoRoot,
                branchName = branchName,
                baseBranch = config.defaultBaseBranch,
                createIfMissing = false,
                githubToken = githubToken,
            )
            AgentRole.COST_MONITOR,
            AgentRole.ORCHESTRATOR,
            -> Unit
        }

        return TargetRepositorySession(
            repoRoot = repoRoot,
            repoUrl = repoUrl,
            baseBranch = config.defaultBaseBranch,
            branchPrefix = config.branchPrefix,
            branchName = branchName,
            deploymentConfig = config,
        )
    }
}

class DeveloperRepositoryFlow(
    private val git: GitApi = GitApi.default(),
    private val pullRequests: GitHubApi = GitHubApi.default(),
    private val docs: DocsApi = DocsApi.default(),
    private val skeletonRoot: Path = Path.of("/usr/local/share/factory/docs-skeleton"),
) {
    private val objectMapper = jacksonObjectMapper()

    fun completeDeveloperRun(
        session: TargetRepositorySession,
        ticketKey: String,
        githubToken: String?,
    ): DeveloperRepositoryResult {
        bootstrapFactoryDocsIfMissing(session.repoRoot)

        val committed = git.commitAll(
            repoRoot = session.repoRoot,
            message = "$ticketKey: AI developer changes",
            githubToken = githubToken,
        )
        git.push(session.repoRoot, session.branchName, githubToken)

        val pr = pullRequests.ensurePullRequest(
            repoRoot = session.repoRoot,
            branchName = session.branchName,
            baseBranch = session.baseBranch,
            title = "$ticketKey - Software Factory changes",
            body = "Automatische Software Factory PR voor `$ticketKey`.",
        )
        return developerResult(session, pr.number, pr.url, committed)
    }

    fun completeDummyDeveloperRun(
        session: TargetRepositorySession,
        ticketKey: String,
        storyText: String,
        githubToken: String?,
    ): DeveloperRepositoryResult {
        bootstrapFactoryDocsIfMissing(session.repoRoot)

        val storyLog = docs.recordDeveloperRunStart(session.repoRoot, ticketKey, storyText)
        val dummyLog = session.repoRoot.resolve("docs").resolve("factory").resolve(".dummy-log")
        dummyLog.parent.createDirectories()
        val dummyLogEntry = "- ${OffsetDateTime.now()}: $ticketKey dummy developer update on ${session.branchName}\n"
        if (dummyLog.exists()) {
            dummyLog.appendText(dummyLogEntry)
        } else {
            dummyLog.writeText(dummyLogEntry)
        }
        docs.markStepDone(storyLog, "implement requested changes")
        docs.appendDone(
            storyLog,
            "Dummy developer-flow heeft een placeholder-wijziging gemaakt zodat clone, commit, push en PR-flow end-to-end getest worden.",
        )

        val committed = git.commitAll(
            repoRoot = session.repoRoot,
            message = "$ticketKey dummy developer update",
            githubToken = githubToken,
        )
        git.push(session.repoRoot, session.branchName, githubToken)

        val pr = pullRequests.ensurePullRequest(
            repoRoot = session.repoRoot,
            branchName = session.branchName,
            baseBranch = session.baseBranch,
            title = "$ticketKey - Software Factory changes",
            body = "Automatische Software Factory PR voor `$ticketKey`.",
        )
        docs.markStepDone(storyLog, "update story-log with results")
        docs.appendDone(
            storyLog,
            "Branch `${session.branchName}` is gepusht en PR #${pr.number} is geopend of hergebruikt.",
        )

        return developerResult(session, pr.number, pr.url, committed)
    }

    private fun bootstrapFactoryDocsIfMissing(repoRoot: Path) {
        if (repoRoot.resolve("docs").resolve("factory").exists()) {
            return
        }
        docs.installSkeleton(repoRoot, skeletonRoot = skeletonRoot.takeIf { it.exists() })
    }

    private fun developerResult(
        session: TargetRepositorySession,
        prNumber: Int,
        prUrl: String?,
        committed: Boolean,
    ): DeveloperRepositoryResult {
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "branchName" to session.branchName,
                "baseBranch" to session.baseBranch,
                "branchPrefix" to session.branchPrefix,
                "prNumber" to prNumber,
                "prUrl" to prUrl,
                "previewUrlTemplate" to session.deploymentConfig.previewUrlTemplate,
                "previewNamespaceTemplate" to session.deploymentConfig.previewNamespaceTemplate,
                "previewDbSecretRecipe" to session.deploymentConfig.previewDbSecretRecipe,
            ),
        )
        return DeveloperRepositoryResult(
            branchName = session.branchName,
            prNumber = prNumber,
            prUrl = prUrl,
            committed = committed,
            completionEvent = AgentEvent("github-pr", payload),
        )
    }
}
