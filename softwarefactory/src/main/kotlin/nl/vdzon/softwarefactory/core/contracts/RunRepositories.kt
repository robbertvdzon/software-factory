package nl.vdzon.softwarefactory.core.contracts

import nl.vdzon.softwarefactory.core.AgentComments
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.DeploymentConfig
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.*

import java.time.OffsetDateTime

interface StoryRunRepository {
    fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord

    fun get(storyRunId: Long): StoryRunRecord?

    fun updatePullRequest(
        storyRunId: Long,
        branchName: String,
        prNumber: Int?,
        prUrl: String?,
        baseBranch: String?,
        branchPrefix: String?,
        previewUrlTemplate: String?,
        previewNamespaceTemplate: String?,
        previewDbSecretRecipe: String?,
    )

    fun updatePullRequest(update: StoryRunPullRequestUpdate) = updatePullRequest(
        update.storyRunId,
        update.branchName,
        update.prNumber,
        update.prUrl,
        update.baseBranch,
        update.branchPrefix,
        update.previewUrlTemplate,
        update.previewNamespaceTemplate,
        update.previewDbSecretRecipe,
    )

    fun updateWorkspace(
        storyRunId: Long,
        workspacePath: String,
        branchName: String,
        baseBranch: String?,
        branchPrefix: String?,
        previewUrlTemplate: String?,
        previewNamespaceTemplate: String?,
        previewDbSecretRecipe: String?,
    ) = Unit

    fun updateWorkspace(update: StoryRunWorkspaceUpdate) = updateWorkspace(
        update.storyRunId,
        update.workspacePath,
        update.branchName,
        update.baseBranch,
        update.branchPrefix,
        update.previewUrlTemplate,
        update.previewNamespaceTemplate,
        update.previewDbSecretRecipe,
    )

    fun activePullRequests(): List<StoryRunRecord>

    fun activeRuns(): List<StoryRunRecord>

    fun close(storyRunId: Long, finalStatus: String, endedAt: OffsetDateTime)

    fun delete(storyRunId: Long) = Unit

    /**
     * Story 5 (deployedAt/StoryDeployReconciler): gemergede runs (`final_status = 'merged'`, gezet
     * door `OrchestratorService.monitorPullRequest` zodra de PR merged blijkt) die nog geen
     * [StoryRunRecord.deployedAt] hebben — de kandidaten voor de reconciler resp. de Rollout-lijst.
     * `final_status = 'merged'` is bewust de "Done"-representatie hier (niet de latere, per-poll
     * herhaalbare `'done'`-sluiting van `SubtaskExecutionCoordinator`): dat is precies het moment
     * dat de story-lifecycle-ontwerpbeslissing ("Done zodra gemerged") bedoelt, en het is de enige
     * van de twee sluitmomenten die gegarandeerd maar één keer vuurt (`close()` hieronder is een
     * no-op zodra `ended_at` al gezet is).
     */
    fun runsAwaitingDeployConfirmation(): List<StoryRunRecord> = emptyList()

    /**
     * Zet [StoryRunRecord.deployedAt] op [deployedAt] voor [storyRunId], maar alleen als dat nog
     * niet gezet was (idempotent: een tweede aanroep voor dezelfde run is een no-op, geen dubbele
     * side-effects bij een herhaalde reconciler-run).
     */
    fun markDeployed(storyRunId: Long, deployedAt: OffsetDateTime) = Unit
}

data class StoryRunPullRequestUpdate(
    val storyRunId: Long,
    val branchName: String,
    val prNumber: Int?,
    val prUrl: String?,
    val baseBranch: String?,
    val branchPrefix: String?,
    val previewUrlTemplate: String?,
    val previewNamespaceTemplate: String?,
    val previewDbSecretRecipe: String?,
) {
    init { require(branchName.isNotBlank()) { "branchName mag niet leeg zijn" } }
}

data class StoryRunWorkspaceUpdate(
    val storyRunId: Long,
    val workspacePath: String,
    val branchName: String,
    val baseBranch: String?,
    val branchPrefix: String?,
    val previewUrlTemplate: String?,
    val previewNamespaceTemplate: String?,
    val previewDbSecretRecipe: String?,
) {
    init {
        require(workspacePath.isNotBlank()) { "workspacePath mag niet leeg zijn" }
        require(branchName.isNotBlank()) { "branchName mag niet leeg zijn" }
    }
}

data class StoryRunRecord(
    val id: Long,
    val storyKey: String,
    val targetRepo: String,
    val workspacePath: String? = null,
    val branchName: String? = null,
    val prNumber: Int? = null,
    val prUrl: String? = null,
    val baseBranch: String? = null,
    val branchPrefix: String? = null,
    val previewUrlTemplate: String? = null,
    val previewNamespaceTemplate: String? = null,
    val previewDbSecretRecipe: String? = null,
    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0,
    val totalCacheReadTokens: Long = 0,
    val totalCacheCreationTokens: Long = 0,
    val totalCostUsdEst: Double = 0.0,
    // Story 5: apart van het story-afrondingsproces gezet, alleen door StoryDeployReconciler.
    val deployedAt: OffsetDateTime? = null,
) {
    val totalTokens: Long =
        totalInputTokens + totalOutputTokens + totalCacheReadTokens + totalCacheCreationTokens
}

data class SystemStateRecord(
    val creditsPausedUntil: OffsetDateTime?,
    val creditsPausedReason: String?,
)

interface SystemStateRepository {
    fun current(): SystemStateRecord

    fun pauseCredits(until: OffsetDateTime, reason: String)

    fun resumeCredits()
}

interface AgentRunRepository {
    fun recordStarted(
        storyRunId: Long,
        role: AgentRole,
        containerName: String,
        model: String?,
        effort: String?,
        level: Int?,
        workspacePath: String?,
        subtaskKey: String? = null,
    ): Long

    fun recordStarted(start: AgentRunStart): Long = recordStarted(
        start.storyRunId,
        start.role,
        start.containerName,
        start.model,
        start.effort,
        start.level,
        start.workspacePath,
        start.subtaskKey,
    )

    fun complete(containerName: String, completion: AgentRunCompletionRecord, endedAt: OffsetDateTime): CompletedAgentRun?

    fun addUsageToStoryRun(storyRunId: Long, completion: AgentRunCompletionRecord)

    /** Durable completion hook; JDBC overrides this with an agent-run idempotency key. */
    fun addUsageToStoryRunOnce(agentRunId: Long, storyRunId: Long, completion: AgentRunCompletionRecord) =
        addUsageToStoryRun(storyRunId, completion)

    fun activeRuns(): List<AgentRunRecord>

    fun latestForRole(storyRunId: Long, role: AgentRole): AgentRunRecord?

    fun recentForRole(storyRunId: Long, role: AgentRole, limit: Int): List<AgentRunRecord>

    fun countForRole(storyRunId: Long, role: AgentRole): Int

    /** Zoals [countForRole], maar afgebakend tot één subtaak — de developer-loopback-cap geldt per subtaak. */
    fun countForRoleAndSubtask(storyRunId: Long, role: AgentRole, subtaskKey: String): Int

}

data class AgentRunStart(
    val storyRunId: Long,
    val role: AgentRole,
    val containerName: String,
    val model: String?,
    val effort: String?,
    val level: Int?,
    val workspacePath: String?,
    val subtaskKey: String? = null,
) {
    init { require(containerName.isNotBlank()) { "containerName mag niet leeg zijn" } }
}

data class AgentRunRecord(
    val id: Long,
    val storyRunId: Long,
    val role: AgentRole,
    val containerName: String,
    val startedAt: OffsetDateTime,
    val endedAt: OffsetDateTime?,
    val outcome: String?,
    val summaryText: String?,
    val model: String? = null,
    val effort: String? = null,
    val level: Int? = null,
    val workspacePath: String? = null,
)

data class AgentRunCompletionRecord(
    val outcome: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadInputTokens: Int,
    val cacheCreationInputTokens: Int,
    val numTurns: Int,
    val durationMs: Int,
    val costUsdEst: Double,
    val summaryText: String?,
)

data class CompletedAgentRun(
    val agentRunId: Long,
    val storyRunId: Long,
    val workspacePath: String?,
)

interface CompletionProgress {
    fun hasUnfinishedForStory(storyKey: String): Boolean

    companion object {
        fun none(): CompletionProgress = object : CompletionProgress {
            override fun hasUnfinishedForStory(storyKey: String) = false
        }
    }
}

/** Cross-module guard that prevents dispatch/merge/deploy while recovery is unfinished. */
