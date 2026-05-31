package nl.vdzon.softwarefactory.runtime.workspaces

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.docs.DeploymentConfig
import nl.vdzon.softwarefactory.docs.DocsApi
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.orchestrator.PreparedStoryWorkspace
import nl.vdzon.softwarefactory.orchestrator.RepositorySyncResult
import nl.vdzon.softwarefactory.orchestrator.StoryRunRecord
import nl.vdzon.softwarefactory.orchestrator.StoryWorkspaceApi
import nl.vdzon.softwarefactory.support.SupportApi
import nl.vdzon.softwarefactory.youtrack.AgentRole
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText

@Service
class StoryWorkspaceService(
    private val factorySecrets: FactorySecrets,
    private val git: GitApi,
    private val pullRequests: GitHubApi,
    private val docs: DocsApi = DocsApi.default(),
    private val storyRoot: Path = AgentWorkspaceFactory.storyWorkspaceRoot(),
) : StoryWorkspaceApi {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun prepare(storyRun: StoryRunRecord, role: AgentRole): PreparedStoryWorkspace {
        val workspace = workspacePath(storyRun)
        val repoRoot = workspace.resolve("repo")
        workspace.createDirectories()
        workspace.resolve(AgentWorkspaceFactory.STORY_WORKSPACE_MARKER).writeText("software-factory story workspace\n")

        if (!repoRoot.resolve(".git").exists()) {
            logger.info(
                "Cloning target repository for story workspace: story={} repo={} workspace={}",
                storyRun.storyKey,
                SupportApi.default().redact(storyRun.targetRepo),
                workspace,
            )
            git.clone(storyRun.targetRepo, repoRoot, factorySecrets.githubToken)
        }

        val initialConfig = mergedConfig(storyRun, docs.loadFactoryDocs(role, repoRoot).deploymentConfig)
        val branchName = storyRun.branchName?.takeIf { it.isNotBlank() } ?: initialConfig.branchPrefix + storyRun.storyKey
        git.checkoutStoryBranch(
            repoRoot = repoRoot,
            branchName = branchName,
            baseBranch = initialConfig.defaultBaseBranch,
            createIfMissing = true,
            githubToken = factorySecrets.githubToken,
        )

        val config = mergedConfig(storyRun.copy(branchName = branchName), docs.loadFactoryDocs(role, repoRoot).deploymentConfig)
        return PreparedStoryWorkspace(
            workspacePath = workspace,
            repoRoot = repoRoot,
            branchName = branchName,
            baseBranch = config.defaultBaseBranch,
            branchPrefix = config.branchPrefix,
            deploymentConfig = config,
        )
    }

    override fun syncAfterAgent(storyRun: StoryRunRecord, role: AgentRole): RepositorySyncResult {
        val workspace = workspacePath(storyRun)
        val repoRoot = workspace.resolve("repo")
        require(repoRoot.resolve(".git").exists()) {
            "Story workspace repository is missing for ${storyRun.storyKey}: $repoRoot"
        }

        val config = mergedConfig(storyRun, docs.loadFactoryDocs(role, repoRoot).deploymentConfig)
        val branchName = storyRun.branchName?.takeIf { it.isNotBlank() } ?: config.branchPrefix + storyRun.storyKey
        val committed = git.commitAll(
            repoRoot = repoRoot,
            message = "${storyRun.storyKey}: ${role.markerKeyPart} changes",
            githubToken = factorySecrets.githubToken,
        )
        var prNumber = storyRun.prNumber
        var prUrl = storyRun.prUrl
        if (committed) {
            git.push(repoRoot, branchName, factorySecrets.githubToken)
            git.repositorySlug(storyRun.targetRepo)?.let {
                val pr = pullRequests.ensurePullRequest(
                    repoRoot = repoRoot,
                    branchName = branchName,
                    baseBranch = config.defaultBaseBranch,
                    title = "${storyRun.storyKey}: Software Factory changes",
                    body = "Automatische Software Factory PR voor `${storyRun.storyKey}`.",
                )
                prNumber = pr.number.takeIf { it > 0 }
                prUrl = pr.url
            }
        }

        return RepositorySyncResult(
            workspacePath = workspace,
            repoRoot = repoRoot,
            branchName = branchName,
            baseBranch = config.defaultBaseBranch,
            branchPrefix = config.branchPrefix,
            deploymentConfig = config,
            committed = committed,
            pushed = committed,
            prNumber = prNumber,
            prUrl = prUrl,
        )
    }

    override fun resetForReImplementation(storyRun: StoryRunRecord): Boolean {
        val workspace = workspacePath(storyRun)
        val repoRoot = workspace.resolve("repo")
        if (!repoRoot.resolve(".git").exists()) {
            logger.info(
                "Story workspace repository is missing during re-implement reset: story={} repoRoot={}",
                storyRun.storyKey,
                repoRoot,
            )
            return false
        }

        val config = mergedConfig(storyRun, docs.loadFactoryDocs(AgentRole.DEVELOPER, repoRoot).deploymentConfig)
        val branchName = storyRun.branchName?.takeIf { it.isNotBlank() } ?: config.branchPrefix + storyRun.storyKey
        git.recreateLocalBranchFromBase(
            repoRoot = repoRoot,
            branchName = branchName,
            baseBranch = config.defaultBaseBranch,
            githubToken = factorySecrets.githubToken,
        )
        workspace.resolve(AgentWorkspaceFactory.STORY_WORKSPACE_MARKER).writeText("software-factory story workspace\n")
        logger.info(
            "Recreated local story branch for re-implement: story={} branch={} base={} workspace={}",
            storyRun.storyKey,
            branchName,
            config.defaultBaseBranch,
            workspace,
        )
        return true
    }

    override fun cleanup(storyKey: String): Boolean {
        val workspace = storyRoot.resolve(safeStoryKey(storyKey)).toAbsolutePath().normalize()
        val root = storyRoot.toAbsolutePath().normalize()
        require(workspace.startsWith(root)) { "Refusing to delete story workspace outside root: $workspace" }
        if (!workspace.exists()) {
            return false
        }
        Files.walk(workspace).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
        }
        return true
    }

    private fun workspacePath(storyRun: StoryRunRecord): Path =
        storyRun.workspacePath
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it).toAbsolutePath().normalize() }
            ?: storyRoot.resolve(safeStoryKey(storyRun.storyKey)).toAbsolutePath().normalize()

    private fun mergedConfig(storyRun: StoryRunRecord, repoConfig: DeploymentConfig?): DeploymentConfig {
        val fallback = repoConfig ?: DeploymentConfig()
        return DeploymentConfig(
            defaultBaseBranch = storyRun.baseBranch?.takeIf { it.isNotBlank() } ?: fallback.defaultBaseBranch,
            branchPrefix = storyRun.branchPrefix?.takeIf { it.isNotBlank() } ?: fallback.branchPrefix,
            previewUrlTemplate = fallback.previewUrlTemplate ?: storyRun.previewUrlTemplate,
            previewNamespaceTemplate = fallback.previewNamespaceTemplate ?: storyRun.previewNamespaceTemplate,
            previewDbSecretRecipe = fallback.previewDbSecretRecipe ?: storyRun.previewDbSecretRecipe,
        )
    }

    private fun safeStoryKey(storyKey: String): String =
        storyKey.replace(Regex("[^A-Za-z0-9_.-]"), "-").ifBlank { "story" }
}
