package nl.vdzon.softwarefactory.core.contracts

import nl.vdzon.softwarefactory.core.AgentComments
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.DeploymentConfig
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.*

import java.nio.file.Path

/**
 * Public contract for preparing and synchronizing per-story Git workspaces.
 * The orchestrator uses this to make repository state available to agents and
 * to commit and push their changes either automatically after an agent run or
 * through a manual sync command.
 */
interface StoryWorkspaceApi {
    fun prepare(storyRun: StoryRunRecord, role: AgentRole): PreparedStoryWorkspace

    fun syncAfterAgent(storyRun: StoryRunRecord, role: AgentRole): RepositorySyncResult

    fun ensureStoryWorklog(storyRun: StoryRunRecord, summary: String, description: String?): Path? = null

    fun writeFinalStory(storyRun: StoryRunRecord, summary: String, description: String?, finalSummary: String): Path? = null

    fun resetForReImplementation(storyRun: StoryRunRecord): Boolean = false

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
