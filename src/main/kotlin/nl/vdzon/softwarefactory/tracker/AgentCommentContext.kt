package nl.vdzon.softwarefactory.tracker

object AgentCommentContext {
    fun taskComments(
        issue: TrackerIssue,
        role: AgentRole,
        isProcessed: (TrackerComment, AgentRole) -> Boolean,
    ): List<TrackerComment> =
        issue.comments.filter { comment ->
            when (role) {
                AgentRole.REFINER -> !comment.isAgentComment && !isProcessed(comment, role)
                AgentRole.DEVELOPER -> developerContextComment(comment, isProcessed)
                AgentRole.REVIEWER -> TrackerCommentParser.agentRole(comment.body) in setOf(AgentRole.REFINER, AgentRole.DEVELOPER)
                AgentRole.TESTER -> TrackerCommentParser.agentRole(comment.body) in setOf(
                    AgentRole.REFINER,
                    AgentRole.DEVELOPER,
                    AgentRole.REVIEWER,
                )
                AgentRole.COST_MONITOR,
                AgentRole.ORCHESTRATOR,
                -> false
            }
        }

    fun processableComments(
        issue: TrackerIssue,
        role: AgentRole,
        isProcessed: (TrackerComment, AgentRole) -> Boolean,
    ): List<TrackerComment> =
        issue.comments.filter { comment ->
            when (role) {
                AgentRole.REFINER -> !comment.isAgentComment && !isProcessed(comment, role)
                AgentRole.DEVELOPER -> developerFeedbackComment(comment) && !isProcessed(comment, role)
                AgentRole.REVIEWER,
                AgentRole.TESTER,
                AgentRole.COST_MONITOR,
                AgentRole.ORCHESTRATOR,
                -> false
            }
        }

    private fun developerContextComment(
        comment: TrackerComment,
        isProcessed: (TrackerComment, AgentRole) -> Boolean,
    ): Boolean =
        when (TrackerCommentParser.agentRole(comment.body)) {
            AgentRole.REFINER -> true
            AgentRole.REVIEWER,
            AgentRole.TESTER,
            -> !isProcessed(comment, AgentRole.DEVELOPER)
            else -> false
        }

    private fun developerFeedbackComment(comment: TrackerComment): Boolean =
        TrackerCommentParser.agentRole(comment.body) in setOf(AgentRole.REVIEWER, AgentRole.TESTER)
}
