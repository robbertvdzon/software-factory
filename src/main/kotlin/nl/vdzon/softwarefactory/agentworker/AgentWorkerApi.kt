package nl.vdzon.softwarefactory.agentworker

/**
 * Public API marker of the agent worker module.
 *
 * The agent worker module owns the standalone container process that executes
 * one assigned agent run: it prepares the target repository, builds the prompt,
 * invokes the AI client, publishes developer PRs and writes the run result to
 * the mounted workspace. It must not call factory server internals directly.
 */
interface AgentWorkerApi

data class AgentWorkerResult(
    val storyKey: String,
    val role: String,
    val containerName: String,
    val phase: String?,
    val outcome: String,
    val summaryText: String,
    val exitCode: Int,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadInputTokens: Int = 0,
    val cacheCreationInputTokens: Int = 0,
    val numTurns: Int = 0,
    val durationMs: Int = 0,
    val costUsdEst: Double = 0.0,
    val events: List<AgentWorkerEvent> = emptyList(),
    val knowledgeUpdates: List<AgentWorkerKnowledgeUpdate> = emptyList(),
)

data class AgentWorkerEvent(
    val kind: String,
    val payload: String,
)

data class AgentWorkerKnowledgeUpdate(
    val category: String,
    val key: String,
    val content: String,
)
