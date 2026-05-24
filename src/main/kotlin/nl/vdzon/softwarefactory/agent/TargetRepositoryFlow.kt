package nl.vdzon.softwarefactory.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.docs.DeploymentConfig
import nl.vdzon.softwarefactory.docs.DocsSkeletonInstaller
import nl.vdzon.softwarefactory.docs.FactoryDocsLoader
import nl.vdzon.softwarefactory.docs.StoryLogWriter
import nl.vdzon.softwarefactory.git.GitCommandClient
import nl.vdzon.softwarefactory.github.PullRequestClient
import nl.vdzon.softwarefactory.github.GitHubCliPullRequestClient
import nl.vdzon.softwarefactory.tracker.AgentRole
import nl.vdzon.softwarefactory.runtime.AgentRunEventPayload
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
    val completionEvent: AgentRunEventPayload,
)

class TargetRepositoryPreparer(
    private val git: GitCommandClient = GitCommandClient(),
) {
    fun prepare(env: Map<String, String>, ticketKey: String, role: AgentRole): TargetRepositorySession? {
        val repoUrl = env["SF_REPO_URL"]?.takeIf { it.isNotBlank() } ?: return null
        val repoRoot = Path.of(env["SF_REPO_ROOT"] ?: "/work/repo")
        val githubToken = env["SF_GITHUB_TOKEN"]

        git.clone(repoUrl, repoRoot, githubToken)
        val config = FactoryDocsLoader().load(role, repoRoot).deploymentConfig ?: DeploymentConfig()
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
    private val git: GitCommandClient = GitCommandClient(),
    private val pullRequests: PullRequestClient = GitHubCliPullRequestClient(),
    private val storyLogWriter: StoryLogWriter = StoryLogWriter(),
    private val skeletonRoot: Path = Path.of("/usr/local/share/factory/docs-skeleton"),
) {
    private val objectMapper = jacksonObjectMapper()

    fun completeDummyDeveloperRun(
        session: TargetRepositorySession,
        ticketKey: String,
        storyText: String,
        githubToken: String?,
    ): DeveloperRepositoryResult {
        bootstrapFactoryDocsIfMissing(session.repoRoot)

        val storyLog = storyLogWriter.recordDeveloperRunStart(session.repoRoot, ticketKey, storyText)
        val dummyLog = session.repoRoot.resolve("docs").resolve("factory").resolve(".dummy-log")
        dummyLog.parent.createDirectories()
        val dummyLogEntry = "- ${OffsetDateTime.now()}: $ticketKey dummy developer update on ${session.branchName}\n"
        if (dummyLog.exists()) {
            dummyLog.appendText(dummyLogEntry)
        } else {
            dummyLog.writeText(dummyLogEntry)
        }
        storyLogWriter.markStepDone(storyLog, "implement requested changes")
        storyLogWriter.appendDone(
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
        storyLogWriter.markStepDone(storyLog, "update story-log with results")
        storyLogWriter.appendDone(
            storyLog,
            "Branch `${session.branchName}` is gepusht en PR #${pr.number} is geopend of hergebruikt.",
        )

        val payload = objectMapper.writeValueAsString(
            mapOf(
                "branchName" to session.branchName,
                "baseBranch" to session.baseBranch,
                "branchPrefix" to session.branchPrefix,
                "prNumber" to pr.number,
                "prUrl" to pr.url,
                "previewUrlTemplate" to session.deploymentConfig.previewUrlTemplate,
                "previewNamespaceTemplate" to session.deploymentConfig.previewNamespaceTemplate,
                "previewDbSecretRecipe" to session.deploymentConfig.previewDbSecretRecipe,
            ),
        )
        return DeveloperRepositoryResult(
            branchName = session.branchName,
            prNumber = pr.number,
            prUrl = pr.url,
            committed = committed,
            completionEvent = AgentRunEventPayload("github-pr", payload),
        )
    }

    private fun bootstrapFactoryDocsIfMissing(repoRoot: Path) {
        if (repoRoot.resolve("docs").resolve("factory").exists()) {
            return
        }
        val installer = if (skeletonRoot.exists()) {
            DocsSkeletonInstaller(skeletonRoot)
        } else {
            DocsSkeletonInstaller()
        }
        installer.install(repoRoot)
    }
}
