package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AgentDispatchRequest
import nl.vdzon.softwarefactory.core.contracts.AgentDispatchResult
import nl.vdzon.softwarefactory.core.contracts.AgentRuntime
import nl.vdzon.softwarefactory.runtime.RuntimeApi
import nl.vdzon.softwarefactory.runtime.models.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.types.CompletionOutcome
import nl.vdzon.softwarefactory.runtime.v2.RuntimePublicationStatus
import nl.vdzon.softwarefactory.runtime.v2.RuntimeRepositoryResult
import nl.vdzon.softwarefactory.runtime.v2.RuntimeVerificationResult
import nl.vdzon.softwarefactory.runtime.v2.RuntimeVerificationStatus
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Scripted Runtime voor de end-to-end-tests. De dubbel levert dezelfde getypeerde completion en
 * repositorybewijzen als Agent Runtime v2, zonder resultbestand, gedeelde workspace of AI-provider.
 * Completion gebeurt asynchroon via [completeNext], zodat de agent-run eerst duurzaam kan worden
 * vastgelegd voordat het resultaat binnenkomt.
 */
class TestAgentRuntime(
    private val remote: LocalGitRemote = E2eTestConfig.LOCAL_REMOTE,
) : AgentRuntime {
    private val attempts = ConcurrentHashMap<String, Int>()
    private val completions = ConcurrentLinkedQueue<AgentRunCompleteRequest>()

    val dispatched: MutableList<Pair<String, AgentRole>> = java.util.Collections.synchronizedList(mutableListOf())

    @Volatile
    var script: AgentScript = AgentScript()

    fun reset() {
        attempts.clear()
        completions.clear()
        dispatched.clear()
        script = AgentScript()
    }

    override fun dispatch(request: AgentDispatchRequest): AgentDispatchResult {
        val attempt = attempts.merge(attemptKey(request.serializationKey, request.role), 1, Int::plus)!!
        dispatched += request.serializationKey to request.role
        val jobId = UUID.randomUUID().toString()
        val scripted = script.resultFor(request, attempt).copy(containerName = jobId)
        completions += withRuntimeProof(request, attempt, scripted)
        return AgentDispatchResult(
            containerName = jobId,
            startedAt = OffsetDateTime.now(),
            idempotencyKey = jobId,
            executionVendorId = "mock",
            executionModel = "mock",
            executionMode = "MOCK",
        )
    }

    /** Probeert de oudste completion; bij een dispatch-race blijft die staan voor de volgende poll. */
    fun completeNext(runtimeApi: RuntimeApi): Boolean {
        val request = completions.peek() ?: return false
        val outcome = runtimeApi.complete(request)
        if (outcome is CompletionOutcome.NoActiveRun) return false
        completions.poll()
        return true
    }

    private fun withRuntimeProof(
        request: AgentDispatchRequest,
        attempt: Int,
        result: AgentRunCompleteRequest,
    ): AgentRunCompleteRequest = when (request.role) {
        AgentRole.DEVELOPER, AgentRole.DOCUMENTER -> mutatingProof(request, attempt, result)
        AgentRole.REVIEWER, AgentRole.TESTER, AgentRole.AUDITOR -> readOnlyProof(request, result)
        else -> result
    }

    private fun mutatingProof(
        request: AgentDispatchRequest,
        attempt: Int,
        result: AgentRunCompleteRequest,
    ): AgentRunCompleteRequest {
        val branch = requireNotNull(request.branchName)
        val finalPhase = result.phase in setOf("developed", "documented")
        val verificationFailed =
            request.role == AgentRole.DEVELOPER && script.developerVerificationFails(attempt) && finalPhase
        val (checkoutSha, commitSha) = if (finalPhase && !verificationFailed) {
            remote.commitAgentOutput(branch, "${request.storyKey}-${request.role.markerKeyPart}-$attempt")
        } else {
            remote.latestCommitSha(branch).let { it to null }
        }
        return result.copy(
            runtimeRepositoryResult = RuntimeRepositoryResult(
                alias = RUNTIME_ALIAS,
                branch = branch,
                checkoutCommitSha = checkoutSha,
                publicationStatus = if (commitSha == null) RuntimePublicationStatus.NO_CHANGES else RuntimePublicationStatus.PUSHED,
                commitSha = commitSha,
                diffStat = if (commitSha == null) null else "1 file changed, 1 insertion(+)",
            ),
            runtimeVerificationResult = RuntimeVerificationResult(
                status = if (verificationFailed) RuntimeVerificationStatus.FAILED else RuntimeVerificationStatus.PASSED,
                configVersion = 1,
                agentRounds = if (verificationFailed) 3 else 0,
            ),
        )
    }

    private fun readOnlyProof(request: AgentDispatchRequest, result: AgentRunCompleteRequest): AgentRunCompleteRequest {
        val branch = if (request.role == AgentRole.AUDITOR) request.baseBranch ?: "main" else requireNotNull(request.branchName)
        return result.copy(
            runtimeRepositoryResult = RuntimeRepositoryResult(
                alias = RUNTIME_ALIAS,
                branch = branch,
                checkoutCommitSha = remote.latestCommitSha(branch),
                publicationStatus = RuntimePublicationStatus.NONE,
            ),
        )
    }

    override fun isAgentRunning(storyKey: String, role: AgentRole): Boolean = false

    override fun isAnyAgentRunningForStory(storyKey: String): Boolean = false

    override fun runningCount(role: AgentRole?): Int = 0

    override fun killForStory(storyKey: String): Int = 0

    private fun attemptKey(serializationKey: String, role: AgentRole): String =
        "$serializationKey/${role.markerKeyPart}"

    companion object {
        const val RUNTIME_ALIAS = "e2e-repository"
    }
}
