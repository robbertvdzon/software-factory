package nl.vdzon.softwarefactory.runtime.workspaces

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.core.DeploymentConfig
import nl.vdzon.softwarefactory.docs.DocsApi
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.core.contracts.PreparedStoryWorkspace
import nl.vdzon.softwarefactory.core.contracts.RepositorySyncResult
import nl.vdzon.softwarefactory.core.contracts.StoryRunRecord
import nl.vdzon.softwarefactory.core.contracts.StoryWorkspaceApi
import nl.vdzon.softwarefactory.support.SupportApi
import nl.vdzon.softwarefactory.core.AgentRole
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
        // Developer-runs: haal de laatste base-branch op en merge die in de story-branch (lokaal,
        // geen auth nodig). Conflicten blijven als markers in de werkboom staan; de developer-agent
        // lost ze op. Reviewer/tester wijzigen geen code en mergen dus niet.
        if (role == AgentRole.DEVELOPER) {
            val merge = git.mergeBaseIntoBranch(repoRoot, initialConfig.defaultBaseBranch, factorySecrets.githubToken)
            if (merge.clean) {
                logger.info("Story {}: '{}' in branch {} gemerged (schoon).", storyRun.storyKey, initialConfig.defaultBaseBranch, branchName)
            } else {
                logger.warn(
                    "Story {}: merge van '{}' in {} heeft conflicten ({}); developer-agent lost ze op.",
                    storyRun.storyKey,
                    initialConfig.defaultBaseBranch,
                    branchName,
                    merge.conflictedFiles.joinToString(),
                )
            }
        }
        installFactoryDocsSkeleton(repoRoot, storyRun.storyKey)

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

        // Faal-vangnet: zijn er na de developer-run nog conflict-markers van een main-merge blijven
        // staan, dan NIET committen (anders commit je markers). Gooi door → de completion zet `Error`
        // en de fase advancet niet, zodat een mens kan ingrijpen (oplossen + sync, of re-implement).
        val unresolved = git.unmergedPaths(repoRoot, factorySecrets.githubToken)
            .filter { hasConflictMarkers(repoRoot.resolve(it)) }
        if (unresolved.isNotEmpty()) {
            error(
                "Merge-conflict met '${config.defaultBaseBranch}' niet opgelost in: ${unresolved.joinToString()}. " +
                    "Los de conflict-markers op en sync opnieuw, of doe een re-implement.",
            )
        }

        val committed = git.commitAll(
            repoRoot = repoRoot,
            message = "${storyRun.storyKey}: ${role.markerKeyPart} changes",
            githubToken = factorySecrets.githubToken,
        )
        // De developer-prep merget main al vóór de agent-run (mergeBaseIntoBranch); dat levert een
        // merge-commit op zónder vuile werkboom. Push daarom ook bij een al-gecommitte voorsprong
        // op origin — anders reset de prep van de volgende rol de branch terug naar origin en
        // verdampt de merge, waarna de tester zonder de laatste main test en blijft afkeuren.
        val pushNeeded = committed || git.aheadOfRemote(repoRoot, branchName, factorySecrets.githubToken)
        var prNumber = storyRun.prNumber
        var prUrl = storyRun.prUrl
        if (pushNeeded) {
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
            pushed = pushNeeded,
            prNumber = prNumber,
            prUrl = prUrl,
        )
    }

    override fun ensureStoryWorklog(storyRun: StoryRunRecord, summary: String, description: String?): Path? {
        val repoRoot = workspacePath(storyRun).resolve("repo")
        if (!repoRoot.exists()) {
            return null
        }
        return docs.ensureStoryWorklog(repoRoot, storyRun.storyKey, summary, description)
    }

    override fun writeFinalStory(
        storyRun: StoryRunRecord,
        summary: String,
        description: String?,
        finalSummary: String,
    ): Path? {
        val repoRoot = workspacePath(storyRun).resolve("repo")
        if (!repoRoot.exists()) {
            return null
        }
        return docs.writeFinalStory(repoRoot, storyRun.storyKey, summary, description, finalSummary)
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

    private fun installFactoryDocsSkeleton(repoRoot: Path, storyKey: String) {
        val result = docs.installSkeleton(repoRoot)
        if (result.created.isNotEmpty()) {
            logger.info(
                "Installed missing factory docs skeleton entries: story={} created={}",
                storyKey,
                result.created.joinToString(","),
            )
        }
    }

    private fun safeStoryKey(storyKey: String): String =
        storyKey.replace(Regex("[^A-Za-z0-9_.-]"), "-").ifBlank { "story" }

    /** Bevat het bestand nog git-conflict-markers (een `<<<<<<< ` én een `>>>>>>> `)? */
    private fun hasConflictMarkers(path: Path): Boolean {
        if (!path.exists() || !Files.isRegularFile(path)) {
            return false
        }
        return runCatching {
            var start = false
            var end = false
            Files.newBufferedReader(path).use { reader ->
                reader.lineSequence().forEach { line ->
                    if (line.startsWith("<<<<<<< ")) start = true
                    if (line.startsWith(">>>>>>> ")) end = true
                }
            }
            start && end
        }.getOrDefault(false)
    }
}
