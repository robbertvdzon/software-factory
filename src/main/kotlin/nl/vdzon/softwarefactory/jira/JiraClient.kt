package nl.vdzon.softwarefactory.jira

interface JiraClient {
    fun findAiIssues(projectKey: String = "KAN", maxResults: Int = 50): List<JiraIssue>

    fun getIssue(issueKey: String): JiraIssue

    fun updateIssueFields(issueKey: String, update: JiraFieldUpdate)

    fun updateIssueSummary(issueKey: String, summary: String) {
        throw UnsupportedOperationException("Updating Jira summary is not supported by this JiraClient.")
    }

    fun transitionIssue(issueKey: String, statusName: String)

    fun postAgentComment(issueKey: String, role: AgentRole, message: String): JiraComment

    fun hasProcessedCommentMarker(commentId: String, role: AgentRole): Boolean

    fun markCommentProcessed(commentId: String, role: AgentRole): Boolean

    fun deleteAgentComments(issueKey: String): Int {
        throw UnsupportedOperationException("Deleting Jira agent comments is not supported by this JiraClient.")
    }
}
