package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.runtime.models.AgentLogLine

/**
 * Geëxposeerde runtime-poort om de gecapturede docker-stdout/stderr-regels van een agent-run te lezen
 * (SF-1010). Implementatie ([nl.vdzon.softwarefactory.runtime.services.AgentLogService]) leeft in een
 * niet-geëxposeerd sub-package en gebruikt `runtime.repositories.AgentEventRepository` direct; de
 * `dashboard`-module mag die repository niet zelf injecteren (Spring-Modulith-grens, alleen
 * `runtime`/`runtime :: models` zijn toegestaan), dus injecteert deze poort in plaats daarvan.
 */
interface AgentLogApi {
    /** Laatste [limit] gelogde regels (docker-stdout/docker-stderr) van [agentRunId], oud naar nieuw. */
    fun recentLines(agentRunId: Long, limit: Int = 500): List<AgentLogLine>
}
