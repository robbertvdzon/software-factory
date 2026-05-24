package nl.vdzon.softwarefactory.jira

object AgentCommentContext {
    fun taskComments(
        issue: JiraIssue,
        role: AgentRole,
        isProcessed: (JiraComment, AgentRole) -> Boolean,
    ): List<JiraComment> =
        issue.comments.filter { comment ->
            when (role) {
                AgentRole.REFINER -> !comment.isAgentComment && !isProcessed(comment, role)
                AgentRole.DEVELOPER -> developerContextComment(comment, isProcessed)
                AgentRole.REVIEWER -> JiraCommentParser.agentRole(comment.body) in setOf(AgentRole.REFINER, AgentRole.DEVELOPER)
                AgentRole.TESTER -> JiraCommentParser.agentRole(comment.body) in setOf(
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
        issue: JiraIssue,
        role: AgentRole,
        isProcessed: (JiraComment, AgentRole) -> Boolean,
    ): List<JiraComment> =
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
        comment: JiraComment,
        isProcessed: (JiraComment, AgentRole) -> Boolean,
    ): Boolean =
        when (JiraCommentParser.agentRole(comment.body)) {
            AgentRole.REFINER -> true
            AgentRole.REVIEWER,
            AgentRole.TESTER,
            -> !isProcessed(comment, AgentRole.DEVELOPER)
            else -> false
        }

    private fun developerFeedbackComment(comment: JiraComment): Boolean =
        JiraCommentParser.agentRole(comment.body) in setOf(AgentRole.REVIEWER, AgentRole.TESTER)
}
