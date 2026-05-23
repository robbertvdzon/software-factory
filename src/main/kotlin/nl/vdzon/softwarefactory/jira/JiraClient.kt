package nl.vdzon.softwarefactory.jira

interface JiraClient {
    fun findAiIssues(projectKey: String = "KAN", maxResults: Int = 50): List<JiraIssue>

    fun getIssue(issueKey: String): JiraIssue

    fun updateIssueFields(issueKey: String, update: JiraFieldUpdate)

    fun transitionIssue(issueKey: String, statusName: String)

    fun postAgentComment(issueKey: String, role: AgentRole, message: String): JiraComment

    fun hasProcessedCommentMarker(commentId: String, role: AgentRole): Boolean

    fun markCommentProcessed(commentId: String, role: AgentRole): Boolean
}
