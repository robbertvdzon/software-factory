package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.docs.DeploymentConfig
import nl.vdzon.softwarefactory.youtrack.AgentRole
import java.nio.file.Path

/**
 * Public contract for preparing and synchronizing per-story Git workspaces.
 * The orchestrator uses this to make repository state available to agents and
 * to commit and push their changes after an agent run finishes.
 */
interface StoryWorkspaceApi {
    fun prepare(storyRun: StoryRunRecord, role: AgentRole): PreparedStoryWorkspace

    fun syncAfterAgent(storyRun: StoryRunRecord, role: AgentRole): RepositorySyncResult

    fun cleanup(storyKey: String): Boolean
}

data class PreparedStoryWorkspace(
    val workspacePath: Path,
    val repoRoot: Path,
    val branchName: String,
    val baseBranch: String,
    val branchPrefix: String,
    val deploymentConfig: DeploymentConfig,
)

data class RepositorySyncResult(
    val workspacePath: Path,
    val repoRoot: Path,
    val branchName: String,
    val baseBranch: String,
    val branchPrefix: String,
    val deploymentConfig: DeploymentConfig,
    val committed: Boolean,
    val pushed: Boolean,
    val prNumber: Int?,
    val prUrl: String?,
)
