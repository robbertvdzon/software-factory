package nl.vdzon.softwarefactory.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.orchestrator.AgentDispatchRequest
import nl.vdzon.softwarefactory.orchestrator.AgentDispatchResult
import nl.vdzon.softwarefactory.orchestrator.AgentRuntime
import nl.vdzon.softwarefactory.youtrack.AgentRole
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

    /** Dispatches in volgorde, zodat de test de pipeline-volgorde kan asserten. */
    val dispatched: MutableList<Pair<String, AgentRole>> = java.util.Collections.synchronizedList(mutableListOf())

    override fun dispatch(request: AgentDispatchRequest): AgentDispatchResult {
        val attempt = attempts.merge(attemptKey(request.serializationKey, request.role), 1, Int::plus)!!
        dispatched += request.serializationKey to request.role

        val containerName = containerName(request, attempt)
        val workspace = Files.createTempDirectory("test-agent-${request.role.markerKeyPart}-")
        val result = AgentScript.resultFor(request, attempt).copy(
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
     * Serialiseert het result naar JSON met **alleen** de constructor-velden.
     * Het productiemodel [nl.vdzon.softwarefactory.runtime.AgentRunCompleteRequest]
     * heeft afgeleide getters (`isSuccessful()`, `totalTokens`) die Jackson anders
     * als extra JSON-velden schrijft; de echte
     * [nl.vdzon.softwarefactory.runtime.services.AgentResultFileCompletionPoller]
     * leest met `FAIL_ON_UNKNOWN_PROPERTIES` aan en zou daarop struikelen.
     */
    private fun resultJson(result: nl.vdzon.softwarefactory.runtime.AgentRunCompleteRequest): ObjectNode {
        val node = objectMapper.valueToTree<ObjectNode>(result)
        node.remove(listOf("isSuccessful", "totalTokens", "summaryForLog"))
        return node
    }

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
