package nl.vdzon.softwarefactory.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.contract.AgentResultEvent
import nl.vdzon.softwarefactory.contract.AgentResultFile
import nl.vdzon.softwarefactory.contract.AgentResultKnowledgeUpdate
import nl.vdzon.softwarefactory.contract.AgentResultSubtask
import nl.vdzon.softwarefactory.core.AgentDispatchRequest
import nl.vdzon.softwarefactory.core.AgentDispatchResult
import nl.vdzon.softwarefactory.core.AgentRuntime
import nl.vdzon.softwarefactory.runtime.workspaces.AgentWorkspaceFactory
import nl.vdzon.softwarefactory.core.AgentRole
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Scripted [AgentRuntime] voor de end-to-end-test: vervangt
 * [nl.vdzon.softwarefactory.runtime.docker.DockerAgentRuntime] zonder Docker of
 * LLM. `dispatch()` maakt een echt temp-workspace, schrijft daar meteen
 * `agent-result.json` op basis van [AgentScript], en retourneert dat pad.
 *
 * `isContainerRunning()` is altijd `false`, zodat de echte
 * [nl.vdzon.softwarefactory.runtime.services.AgentResultFileCompletionPoller]
 * het resultaat direct oppakt en het productie-completion-pad ongewijzigd
 * doorloopt. Geen mock van completion nodig.
 */
class TestAgentRuntime(
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) : AgentRuntime {

    /** Poging-teller per `(serializationKey, role)`, 1-based. */
    private val attempts = ConcurrentHashMap<String, Int>()

    /**
     * De agent-workspaces komen onder `<project>/work` (de root die de echte
     * [nl.vdzon.softwarefactory.runtime.workspaces.FileSystemAgentWorkspaceCleaner] accepteert),
     * niet in de systeem-temp. Anders weigert de cleaner ze ("outside workspace root") en spamt
     * de log met stacktraces — en blijven ze liggen.
     */
    private val workspaceRoot: Path =
        AgentWorkspaceFactory.projectRoot().resolve("work").also { Files.createDirectories(it) }

    /** Dispatches in volgorde, zodat de test de pipeline-volgorde kan asserten. */
    val dispatched: MutableList<Pair<String, AgentRole>> = java.util.Collections.synchronizedList(mutableListOf())

    /** Het script dat de outcomes bepaalt — per test instelbaar. */
    @Volatile
    var script: AgentScript = AgentScript()

    /** Reset de gedeelde static-state tussen tests (poging-teller, dispatch-log, script). */
    fun reset() {
        attempts.clear()
        dispatched.clear()
        script = AgentScript()
    }

    override fun dispatch(request: AgentDispatchRequest): AgentDispatchResult {
        val attempt = attempts.merge(attemptKey(request.serializationKey, request.role), 1, Int::plus)!!
        dispatched += request.serializationKey to request.role

        val containerName = containerName(request, attempt)
        val workspace = Files.createTempDirectory(workspaceRoot, "test-agent-${request.role.markerKeyPart}-")
        val result = script.resultFor(request, attempt).copy(
            containerName = containerName,
        )
        Files.writeString(
            workspace.resolve("agent-result.json"),
            objectMapper.writeValueAsString(resultJson(result)),
        )
        return AgentDispatchResult(
            containerName = containerName,
            startedAt = OffsetDateTime.now(),
            workspacePath = workspace.toAbsolutePath().normalize().toString(),
        )
    }

    /**
     * Schrijft het result in het **echte wire-formaat**: het gedeelde contract-DTO
     * [AgentResultFile] uit factory-common — hetzelfde type dat de agentworker-CLI
     * serialiseert en de echte
     * [nl.vdzon.softwarefactory.runtime.services.AgentResultFileCompletionPoller] leest.
     * Zo test de e2e-flow het productie-leespad zonder ObjectNode-trucs.
     */
    private fun resultJson(result: nl.vdzon.softwarefactory.runtime.AgentRunCompleteRequest): AgentResultFile =
        AgentResultFile(
            storyKey = result.storyKey,
            role = result.role,
            containerName = result.containerName,
            phase = result.phase,
            outcome = result.outcome,
            summaryText = result.summaryText,
            exitCode = result.exitCode,
            inputTokens = result.inputTokens,
            outputTokens = result.outputTokens,
            cacheReadInputTokens = result.cacheReadInputTokens,
            cacheCreationInputTokens = result.cacheCreationInputTokens,
            numTurns = result.numTurns,
            durationMs = result.durationMs,
            costUsdEst = result.costUsdEst,
            events = result.events.map { AgentResultEvent(it.kind, it.payload) },
            knowledgeUpdates = result.knowledgeUpdates.map { AgentResultKnowledgeUpdate(it.category, it.key, it.content) },
            subtasks = result.subtasks.map { AgentResultSubtask(it.type, it.title, it.description, it.model, it.effort) },
        )

    /** Container draait nooit echt → de poller verwerkt het result direct. */
    override fun isContainerRunning(containerName: String): Boolean = false

    override fun isAgentRunning(storyKey: String, role: AgentRole): Boolean = false

    override fun isAnyAgentRunningForStory(storyKey: String): Boolean = false

    override fun runningCount(role: AgentRole?): Int = 0

    override fun killForStory(storyKey: String): Int = 0

    private fun attemptKey(serializationKey: String, role: AgentRole): String =
        "$serializationKey/${role.markerKeyPart}"

    private fun containerName(request: AgentDispatchRequest, attempt: Int): String =
        "test-${request.storyKey.lowercase()}-${request.role.markerKeyPart}-$attempt"
            .replace(Regex("[^a-z0-9_.-]"), "-")

    companion object {
        fun workspaceResult(workspacePath: String): Path =
            Path.of(workspacePath).resolve("agent-result.json")
    }
}
