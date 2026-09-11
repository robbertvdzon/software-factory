package nl.vdzon.softwarefactory.runtime.v2

import nl.vdzon.softwarefactory.config.ProjectRepositoryCatalog
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AgentDispatchRequest
import nl.vdzon.softwarefactory.core.contracts.AgentDispatchResult
import nl.vdzon.softwarefactory.core.contracts.AgentRunRepository
import nl.vdzon.softwarefactory.core.contracts.AgentRuntime
import nl.vdzon.softwarefactory.core.contracts.OrchestratorSettings
import nl.vdzon.softwarefactory.core.contracts.StoryRunRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

@Component
@ConditionalOnProperty(name = ["softwarefactory.runtime"], havingValue = "v2", matchIfMissing = true)
class AgentRuntimeV2Adapter(
    private val client: AgentRuntimeV2HttpClient,
    private val jobContent: AgentRuntimeJobContentFactory,
    private val executions: AgentRoleExecutionConfigService,
    private val projects: ProjectRepositoryCatalog,
    private val agentRuns: AgentRunRepository,
    private val storyRuns: StoryRunRepository,
    private val settings: OrchestratorSettings,
    private val runtimeSettings: AgentRuntimeV2Settings,
) : AgentRuntime {
    override fun dispatch(request: AgentDispatchRequest): AgentDispatchResult {
        require(!runtimeSettings.token.isNullOrBlank()) {
            "SF_AGENT_RUNTIME_TOKEN is required for Agent Runtime v2 dispatch"
        }
        val executionProject = projects.projectNameFor(request.targetRepo)
        val execution = executions.resolve(request.role, executionProject).execution
        val mapping = roleMapping(request.role)
        val repositoryCheckout = repositoryCheckout(request, mapping)
        val sequence = logicalSequence(request)
        val idempotencyKey = idempotencyKey(request, sequence)
        val created = client.createJob(
            RuntimeCreateJobRequest(
                idempotencyKey = idempotencyKey,
                jobKind = mapping.jobKind,
                taskType = mapping.taskType,
                execution = execution,
                input = jobContent.input(idempotencyKey, request),
                output = jobContent.output(request.role),
                repositoryCheckout = repositoryCheckout,
                verification = if (mapping.mutating) {
                    RuntimeJobVerification(
                        mode = RuntimeVerificationMode.REPOSITORY_CONFIG,
                        maxRepairAttempts = runtimeSettings.maxRepairAttempts,
                        repairInstruction = "Herstel de verificatiefouten zonder Gitmetadata te wijzigen.",
                    )
                } else {
                    null
                },
                environmentKeys = emptyList(),
                executionTimeoutSeconds = settings.hardTimeout.seconds.toInt().coerceIn(600, 86_400),
            ),
        )
        return AgentDispatchResult(
            containerName = created.id.toString(),
            startedAt = created.createdAt,
            workspacePath = null,
            idempotencyKey = idempotencyKey,
            executionVendorId = execution.vendorId,
            executionModel = execution.model,
            executionMode = execution.mode.name,
        )
    }

    override fun isAgentRunning(storyKey: String, role: AgentRole): Boolean =
        activeRuns(storyKey).any { it.role == role }

    override fun isAnyAgentRunningForStory(storyKey: String): Boolean =
        activeRuns(storyKey).isNotEmpty()

    override fun runningCount(role: AgentRole?): Int =
        agentRuns.activeRuns().count { role == null || it.role == role }

    override fun killForStory(storyKey: String): Int {
        val jobs = activeRuns(storyKey).mapNotNull { runtimeJobId(it.containerName) }
        jobs.forEach(client::cancel)
        return jobs.size
    }

    private fun activeRuns(storyKey: String) =
        agentRuns.activeRuns().filter { run -> storyRuns.get(run.storyRunId)?.storyKey == storyKey }

    private fun logicalSequence(request: AgentDispatchRequest): Int =
        if (request.storyKey != request.serializationKey) {
            agentRuns.countForRoleAndSubtask(request.storyRunId, request.role, request.storyKey) + 1
        } else {
            agentRuns.countForRole(request.storyRunId, request.role) + 1
        }

    private fun idempotencyKey(request: AgentDispatchRequest, sequence: Int): String =
        "sf-${request.storyRunId}-${request.storyKey}-${request.role.markerKeyPart}-$sequence"
            .lowercase()
            .replace(Regex("[^a-z0-9._-]"), "-")
            .take(160)

    private fun repositoryCheckout(
        request: AgentDispatchRequest,
        mapping: RoleMapping,
    ): RuntimeRepositoryCheckout? {
        if (!mapping.repository) return null
        val alias = requireNotNull(projects.runtimeAliasFor(request.targetRepo)) {
            "No Agent Runtime repository alias configured for ${request.targetRepo}"
        }
        val branch = if (request.role == AgentRole.AUDITOR) request.baseBranch else request.branchName
        require(!branch.isNullOrBlank()) { "No branch available for ${request.role.markerKeyPart}" }
        return RuntimeRepositoryCheckout(
            alias = alias,
            branch = branch,
            publicationMode = if (mapping.mutating) {
                RuntimePublicationMode.COMMIT_AND_PUSH
            } else {
                RuntimePublicationMode.NONE
            },
        )
    }

    private fun roleMapping(role: AgentRole): RoleMapping = when (role) {
        AgentRole.REFINER, AgentRole.PLANNER, AgentRole.SUMMARIZER ->
            RoleMapping(RuntimeJobKind.APPLICATION_WORK, RuntimeTaskType.STRUCTURED_GENERATION)
        AgentRole.DEVELOPER, AgentRole.DOCUMENTER ->
            RoleMapping(RuntimeJobKind.REPOSITORY_WORK, RuntimeTaskType.REPOSITORY_AGENT, repository = true, mutating = true)
        AgentRole.REVIEWER, AgentRole.TESTER, AgentRole.AUDITOR ->
            RoleMapping(RuntimeJobKind.APPLICATION_WORK, RuntimeTaskType.REPOSITORY_AGENT, repository = true)
        else -> error("Role ${role.markerKeyPart} is not dispatched through Agent Runtime v2")
    }

    private fun runtimeJobId(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

    private data class RoleMapping(
        val jobKind: RuntimeJobKind,
        val taskType: RuntimeTaskType,
        val repository: Boolean = false,
        val mutating: Boolean = false,
    )
}
