package nl.vdzon.softwarefactory.testsupport

import nl.vdzon.softwarefactory.core.contracts.AgentDispatchRequest
import nl.vdzon.softwarefactory.core.contracts.AgentDispatchResult
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AgentRuntime
import java.time.OffsetDateTime

/**
 * [AgentRuntime]-fake die dispatches en log-captures registreert i.p.v. containers te starten.
 * Via [runningStories] kan een test simuleren dat er al een agent voor een story draait
 * (concurrency-gate); via [runningByRole] zijn role-tellingen instelbaar.
 */
class FakeAgentRuntime(
    private val now: OffsetDateTime,
    private val runningStories: Set<String> = emptySet(),
) : AgentRuntime {
    val dispatches: MutableList<AgentDispatchRequest> = mutableListOf()
    val logCaptures: MutableList<Pair<String, Long>> = mutableListOf()
    val runningByRole: MutableMap<AgentRole, Int> = mutableMapOf()

    override fun dispatch(request: AgentDispatchRequest): AgentDispatchResult {
        dispatches += request
        return AgentDispatchResult(
            containerName = "factory-${request.storyKey}-${request.role.markerKeyPart}",
            startedAt = now,
        )
    }

    override fun captureLogs(containerName: String, agentRunId: Long) {
        logCaptures += containerName to agentRunId
    }

    override fun isAgentRunning(storyKey: String, role: AgentRole): Boolean =
        false

    override fun isContainerRunning(containerName: String): Boolean =
        false

    override fun isAnyAgentRunningForStory(storyKey: String): Boolean =
        storyKey in runningStories

    override fun runningCount(role: AgentRole?): Int =
        if (role == null) runningByRole.values.sum() else runningByRole[role] ?: 0

    override fun killForStory(storyKey: String): Int =
        0
}
