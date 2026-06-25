package nl.vdzon.softwarefactory.youtrack.services

import nl.vdzon.softwarefactory.youtrack.*
import nl.vdzon.softwarefactory.core.*
import nl.vdzon.softwarefactory.core.TrackerCommentParser
object AgentCommentContext {
    fun taskComments(
        issue: TrackerIssue,
        role: AgentRole,
        isProcessed: (TrackerComment, AgentRole) -> Boolean,
    ): List<TrackerComment> =
        issue.comments.filter { comment ->
            // Een onverwerkte user-comment (jouw antwoord op een vraag — geen agent-prefix) telt voor
            // ELKE vragende rol mee. Anders ziet bv. de summarizer/tester je antwoord niet en stelt
            // 'ie dezelfde vraag opnieuw. Voorheen kregen alleen refiner/planner user-comments.
            val userComment = !comment.isAgentComment && !isProcessed(comment, role)
            when (role) {
                AgentRole.REFINER -> userComment
                // De planner leest de (gerefinede) story + user-comments en refiner-output.
                AgentRole.PLANNER ->
                    userComment || TrackerCommentParser.agentRole(comment.body) == AgentRole.REFINER
                AgentRole.DEVELOPER -> userComment || developerContextComment(comment, isProcessed)
                AgentRole.REVIEWER -> userComment ||
                    TrackerCommentParser.agentRole(comment.body) in setOf(AgentRole.REFINER, AgentRole.DEVELOPER)
                AgentRole.TESTER -> userComment ||
                    TrackerCommentParser.agentRole(comment.body) in setOf(
                        AgentRole.REFINER,
                        AgentRole.DEVELOPER,
                        AgentRole.REVIEWER,
                    )
                AgentRole.SUMMARIZER -> userComment ||
                    TrackerCommentParser.agentRole(comment.body) in setOf(
                        AgentRole.REFINER,
                        AgentRole.DEVELOPER,
                        AgentRole.REVIEWER,
                        AgentRole.TESTER,
                    )
                // De documenter werkt de documentatie bij obv wat er gebeurd is: lees alle agent-output.
                AgentRole.DOCUMENTER -> userComment ||
                    TrackerCommentParser.agentRole(comment.body) in setOf(
                        AgentRole.REFINER,
                        AgentRole.DEVELOPER,
                        AgentRole.REVIEWER,
                        AgentRole.TESTER,
                        AgentRole.SUMMARIZER,
                    )
                AgentRole.ASSISTANT,
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
                AgentRole.REFINER,
                AgentRole.PLANNER,
                -> !comment.isAgentComment && !isProcessed(comment, role)
                AgentRole.DEVELOPER -> developerFeedbackComment(comment) && !isProcessed(comment, role)
                AgentRole.REVIEWER,
                AgentRole.TESTER,
                AgentRole.SUMMARIZER,
                AgentRole.DOCUMENTER,
                AgentRole.ASSISTANT,
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
