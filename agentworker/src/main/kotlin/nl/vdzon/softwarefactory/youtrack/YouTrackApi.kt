package nl.vdzon.softwarefactory.youtrack

interface YouTrackApi {
    fun isAgentComment(body: String): Boolean =
        AgentRole.entries.any { body.trimStart().startsWith(it.commentPrefix) }

    companion object {
        fun isAgentComment(body: String): Boolean =
            AgentRole.entries.any { body.trimStart().startsWith(it.commentPrefix) }

        fun default(): YouTrackApi = object : YouTrackApi {}
    }
}
