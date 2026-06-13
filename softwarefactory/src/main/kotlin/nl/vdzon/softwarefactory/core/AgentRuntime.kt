package nl.vdzon.softwarefactory.core

import nl.vdzon.softwarefactory.core.AgentRole
import java.time.OffsetDateTime

interface AgentRuntime {
    fun dispatch(request: AgentDispatchRequest): AgentDispatchResult

    fun captureLogs(containerName: String, agentRunId: Long) = Unit

    fun isContainerRunning(containerName: String): Boolean

    fun isAgentRunning(storyKey: String, role: AgentRole): Boolean

    fun isAnyAgentRunningForStory(storyKey: String): Boolean

    fun runningCount(role: AgentRole? = null): Int

    fun killForStory(storyKey: String): Int
}

data class AgentDispatchRequest(
    val storyKey: String,
    val targetRepo: String,
    val storyRunId: Long,
    val workspacePath: String? = null,
    val branchName: String? = null,
    val role: AgentRole,
    /** Actieve fase als trackerValue (bv. "refining"/"planning"); veld-onafhankelijk. */
    val phase: String,
    val baseBranch: String? = null,
    val branchPrefix: String? = null,
    val prNumber: Int? = null,
    val previewUrl: String? = null,
    val previewNamespace: String? = null,
    val previewDbUrl: String? = null,
    val developerLoopbackReason: String? = null,
    val agentMode: String? = null,
    val trackerContext: String? = null,
    val prCommentContext: String? = null,
    val aiLevel: Int? = null,
    val aiSupplier: String? = null,
    val aiModel: String? = null,
    val aiEffort: String? = null,
    /**
     * Sleutel voor serialisatie/labels. Voor subtaken = de parent-story, zodat
     * `isAnyAgentRunningForStory(parentKey)` de subtask-container ziet en er nooit
     * twee agents op de gedeelde branch draaien. Default = `storyKey` (stories).
     */
    val serializationKey: String = storyKey,
    val labels: Map<String, String> = mapOf(
        "app" to "factory-agent",
        "story-key" to serializationKey,
        "role" to role.markerKeyPart,
    ),
)

data class AgentDispatchResult(
    val containerName: String,
    val startedAt: OffsetDateTime,
    val workspacePath: String? = null,
)

class NotConfiguredAgentRuntime : AgentRuntime {
    override fun dispatch(request: AgentDispatchRequest): AgentDispatchResult =
        throw IllegalStateException("Agent runtime is not configured yet; Docker dispatch is implemented in KAN-004.")

    override fun isAgentRunning(storyKey: String, role: AgentRole): Boolean =
        false

    override fun isContainerRunning(containerName: String): Boolean =
        false

    override fun isAnyAgentRunningForStory(storyKey: String): Boolean =
        false

    override fun runningCount(role: AgentRole?): Int =
        0

    override fun killForStory(storyKey: String): Int =
        0
}
