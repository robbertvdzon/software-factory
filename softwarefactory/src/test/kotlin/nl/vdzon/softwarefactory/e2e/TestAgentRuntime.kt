package nl.vdzon.softwarefactory.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.contract.AgentResultEvent
import nl.vdzon.softwarefactory.contract.AgentResultFile
import nl.vdzon.softwarefactory.contract.AgentResultKnowledgeUpdate
import nl.vdzon.softwarefactory.contract.AgentResultSubtask
import nl.vdzon.softwarefactory.contract.AgentResultVerificationCommand
import nl.vdzon.softwarefactory.contract.AgentResultVerificationEvidence
import nl.vdzon.softwarefactory.verification.CheckoutIdentityResolver
import nl.vdzon.softwarefactory.core.contracts.AgentDispatchRequest
import nl.vdzon.softwarefactory.core.contracts.AgentDispatchResult
import nl.vdzon.softwarefactory.core.contracts.AgentRuntime
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
        val storyWorkspace = request.workspacePath?.let(Path::of)
            ?: Files.createTempDirectory(workspaceRoot, "test-agent-${request.role.markerKeyPart}-")
        Files.createDirectories(storyWorkspace)
        // Productiecontainers hebben elk hun eigen /work/agent-result.json. De E2E-runtime kreeg
        // voor sibling-subtaken echter hetzelfde storyworkspace-pad, waardoor twee snelle
        // dispatches elkaars resultaat konden overschrijven voordat de completionpoller het las.
        // Houd het repo-/storyworkspace gedeeld, maar geef iedere gesimuleerde container een eigen
        // resultworkspace onder de toegestane work-root.
        val resultWorkspace = Files.createTempDirectory(workspaceRoot, "$containerName-result-")
        val storyRepo = storyWorkspace.resolve("repo")
        if (Files.exists(storyRepo)) {
            Files.createSymbolicLink(resultWorkspace.resolve("repo"), storyRepo.toAbsolutePath().normalize())
        }
        var result = script.resultFor(request, attempt).copy(
            containerName = containerName,
        )
        if (request.role == AgentRole.TESTER && result.phase == "tested") {
            result = result.copy(verificationEvidence = testerEvidence(storyRepo, attempt))
        }
        Files.writeString(
            resultWorkspace.resolve("agent-result.json"),
            objectMapper.writeValueAsString(resultJson(result)),
        )
        return AgentDispatchResult(
            containerName = containerName,
            startedAt = OffsetDateTime.now(),
            workspacePath = resultWorkspace.toAbsolutePath().normalize().toString(),
        )
    }

    /**
     * Schrijft het result in het **echte wire-formaat**: het gedeelde contract-DTO
     * [AgentResultFile] uit factory-common — hetzelfde type dat de agentworker-CLI
     * serialiseert en de echte
     * [nl.vdzon.softwarefactory.runtime.services.AgentResultFileCompletionPoller] leest.
     * Zo test de e2e-flow het productie-leespad zonder ObjectNode-trucs.
     */
    private fun resultJson(result: nl.vdzon.softwarefactory.runtime.models.AgentRunCompleteRequest): AgentResultFile =
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
            verificationEvidence = result.verificationEvidence,
        )

    private fun testerEvidence(repoRoot: Path, attempt: Int): AgentResultVerificationEvidence? {
        val mode = script.testerEvidenceMode(attempt)
        if (mode == "missing") return null
        val identity = requireNotNull(CheckoutIdentityResolver().resolve(repoRoot)) {
            "scripted testercheckout heeft geen Git-identiteit: $repoRoot"
        }
        val command = AgentResultVerificationCommand(
            commandId = "e2e-verification",
            startedAt = "2026-07-11T13:00:00Z",
            endedAt = "2026-07-11T13:00:01Z",
            durationMs = 1000,
            exitCode = if (mode == "failed") 1 else 0,
            status = if (mode == "failed") "failed" else "passed",
            summary = if (mode == "failed") "scripted failure" else "scripted green verification",
        )
        return AgentResultVerificationEvidence(
            configVersion = 1,
            testedHeadSha = if (mode == "mismatch") "f".repeat(40) else identity.headSha,
            testedTreeSha = identity.treeSha,
            commands = listOf(command),
        )
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
