package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.youtrack.AgentRole

data class OrchestratorPollResult(
    val issueResults: List<IssueProcessResult>,
)

sealed interface IssueProcessResult {
    val storyKey: String

    data class Dispatched(
        override val storyKey: String,
        val role: AgentRole,
        val containerName: String,
    ) : IssueProcessResult

    data class Skipped(
        override val storyKey: String,
        val reason: String,
    ) : IssueProcessResult

    data class Recovered(
        override val storyKey: String,
        val phase: String,
    ) : IssueProcessResult

    data class Errored(
        override val storyKey: String,
        val message: String,
    ) : IssueProcessResult

    data class Merged(
        override val storyKey: String,
        val prNumber: Int,
    ) : IssueProcessResult

    data class PrCommentTriggered(
        override val storyKey: String,
        val prNumber: Int,
        val commentCount: Int,
    ) : IssueProcessResult
}

