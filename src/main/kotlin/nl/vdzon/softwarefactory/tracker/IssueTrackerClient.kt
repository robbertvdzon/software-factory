package nl.vdzon.softwarefactory.tracker

interface IssueTrackerClient {
    fun ensureConfiguredProjects(): List<TrackerProject> = emptyList()

    fun findWorkIssues(maxResults: Int = 50): List<TrackerIssue> =
        findAiIssues(maxResults = maxResults)

    fun findAiIssues(projectKey: String = "KAN", maxResults: Int = 50): List<TrackerIssue> = emptyList()

    fun getIssue(issueKey: String): TrackerIssue

    fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate)

    fun updateIssueSummary(issueKey: String, summary: String) {
        throw UnsupportedOperationException("Updating issue tracker summary is not supported by this IssueTrackerClient.")
    }

    fun transitionIssue(issueKey: String, statusName: String)

    fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment

    fun postComment(issueKey: String, message: String): TrackerComment {
        throw UnsupportedOperationException("Posting plain issue tracker comments is not supported by this IssueTrackerClient.")
    }

    fun hasProcessedCommentMarker(issueKey: String, commentId: String, role: AgentRole): Boolean =
        hasProcessedCommentMarker(commentId, role)

    fun hasProcessedCommentMarker(commentId: String, role: AgentRole): Boolean = false

    fun markCommentProcessed(issueKey: String, commentId: String, role: AgentRole): Boolean =
        markCommentProcessed(commentId, role)

    fun markCommentProcessed(commentId: String, role: AgentRole): Boolean = false

    fun deleteAgentComments(issueKey: String): Int {
        throw UnsupportedOperationException("Deleting issue tracker agent comments is not supported by this IssueTrackerClient.")
    }
}

data class TrackerProject(
    val id: String,
    val key: String,
    val name: String,
    val targetRepo: String?,
)
