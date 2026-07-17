package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.runtime.models.AgentLogLine

/**
 * Ontsluit de al gecapturede `docker-stdout`/`docker-stderr`-events van een agent-run als een
 * chronologisch geordende logfeed (SF-1038). Losse poort i.p.v. de dashboard-module rechtstreeks
 * `runtime.repositories.AgentEventRepository` te laten injecteren — dat sub-package is niet
 * geëxposeerd over de Spring-Modulith module-grens.
 */
interface AgentLogApi {
    fun recentLogLines(agentRunId: Long, limit: Int = 500): List<AgentLogLine>
}
