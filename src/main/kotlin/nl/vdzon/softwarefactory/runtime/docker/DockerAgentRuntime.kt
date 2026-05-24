package nl.vdzon.softwarefactory.runtime.docker

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.runtime.commands.CommandRunner
import nl.vdzon.softwarefactory.runtime.logging.DockerLogFollower
import nl.vdzon.softwarefactory.runtime.workspaces.AgentWorkspace
import nl.vdzon.softwarefactory.runtime.workspaces.AgentWorkspaceFactory
import nl.vdzon.softwarefactory.support.SupportApi
import nl.vdzon.softwarefactory.youtrack.AgentRole
import nl.vdzon.softwarefactory.orchestrator.AgentDispatchRequest
import nl.vdzon.softwarefactory.orchestrator.AgentDispatchResult
import nl.vdzon.softwarefactory.orchestrator.AgentRuntime
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

data class DockerRuntimeSettings(
    val addHostGateway: Boolean,
    val memory: String?,
    val cpus: String?,
    val logCaptureEnabled: Boolean,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String>): DockerRuntimeSettings =
            DockerRuntimeSettings(
                addHostGateway = environment.boolean("SF_DOCKER_ADD_HOST_GATEWAY", default = isLinux()),
                memory = environment["SF_AGENT_DOCKER_MEMORY"]?.takeIf { it.isNotBlank() },
                cpus = environment["SF_AGENT_DOCKER_CPUS"]?.takeIf { it.isNotBlank() },
                logCaptureEnabled = environment.boolean("SF_DOCKER_LOG_CAPTURE_ENABLED", default = true),
            )

        private fun Map<String, String>.boolean(key: String, default: Boolean): Boolean =
            this[key]?.takeIf { it.isNotBlank() }?.toBooleanStrictOrNull() ?: default

        private fun isLinux(): Boolean =
            System.getProperty("os.name", "").contains("linux", ignoreCase = true)
    }
}

@Component
class DockerAgentRuntime(
    private val factorySecrets: FactorySecrets,
    private val factoryEnvironmentProvider: ConfigApi,
    private val commandRunner: CommandRunner,
    private val workspaceFactory: AgentWorkspaceFactory,
    private val dockerRuntimeSettings: DockerRuntimeSettings,
    private val dockerLogFollower: DockerLogFollower,
) : AgentRuntime {
    override fun dispatch(request: AgentDispatchRequest): AgentDispatchResult {
        val workspace = workspaceFactory.create(request, factoryEnvironmentProvider.resolvedValues())
        val containerName = containerName(request)
        val command = dockerRunCommand(request, workspace, containerName)
        val result = commandRunner.run(command)
        if (result.exitCode != 0) {
            throw IllegalStateException(
                "docker run failed: ${SupportApi.default().redact(result.stderr.ifBlank { result.stdout }).take(500)}",
            )
        }
        return AgentDispatchResult(
            containerName = containerName,
            startedAt = OffsetDateTime.now(),
            workspacePath = workspace.path.toAbsolutePath().normalize().toString(),
        )
    }

    override fun captureLogs(containerName: String, agentRunId: Long) {
        if (dockerRuntimeSettings.logCaptureEnabled) {
            dockerLogFollower.follow(containerName, agentRunId)
        }
    }

    override fun isContainerRunning(containerName: String): Boolean {
        val result = commandRunner.run(
            listOf("docker", "inspect", "--format", "{{.State.Running}}", containerName),
            timeoutSeconds = 30,
        )
        return result.exitCode == 0 && result.stdout.trim().equals("true", ignoreCase = true)
    }

    override fun isAgentRunning(storyKey: String, role: AgentRole): Boolean =
        dockerPsNames(
            "label=app=factory-agent",
            "label=story-key=$storyKey",
            "label=role=${role.markerKeyPart}",
        ).isNotEmpty()

    override fun isAnyAgentRunningForStory(storyKey: String): Boolean =
        dockerPsNames(
            "label=app=factory-agent",
            "label=story-key=$storyKey",
        ).isNotEmpty()

    override fun runningCount(role: AgentRole?): Int {
        val filters = mutableListOf("label=app=factory-agent")
        if (role != null) {
            filters += "label=role=${role.markerKeyPart}"
        }
        return dockerPsNames(*filters.toTypedArray()).size
    }

    override fun killForStory(storyKey: String): Int {
        val containers = dockerPsNames(
            "label=app=factory-agent",
            "label=story-key=$storyKey",
        )
        containers.forEach { containerName ->
            val result = commandRunner.run(listOf("docker", "kill", containerName), timeoutSeconds = 30)
            if (result.exitCode != 0) {
                throw IllegalStateException(
                    "docker kill failed for $containerName: ${SupportApi.default().redact(result.stderr.ifBlank { result.stdout }).take(500)}",
                )
            }
        }
        return containers.size
    }

    fun dockerRunCommand(request: AgentDispatchRequest, workspace: AgentWorkspace, containerName: String): List<String> {
        val command = mutableListOf(
            "docker",
            "run",
            "-d",
            "--rm",
            "--name",
            containerName,
        )
        if (dockerRuntimeSettings.addHostGateway) {
            command += listOf("--add-host", "host.docker.internal:host-gateway")
        }
        dockerRuntimeSettings.memory?.let { command += "--memory=$it" }
        dockerRuntimeSettings.cpus?.let { command += "--cpus=$it" }
        request.labels.forEach { (key, value) ->
            command += listOf("--label", "$key=$value")
        }
        command += listOf("-v", "${workspace.path.toAbsolutePath()}:/work")
        command += listOf("--env-file", workspace.envFile.toAbsolutePath().toString())
        command += listOf("-e", "SF_TICKET_KEY=${request.storyKey}")
        command += listOf("-e", "SF_AGENT_TYPE=${request.role.markerKeyPart}")
        command += listOf("-e", "SF_REPO_URL=${request.targetRepo}")
        command += listOf("-e", "SF_REPO_ROOT=/work/repo")
        command += listOf("-e", "SF_CONTAINER_NAME=$containerName")
        command += listOf("-e", "SF_AGENT_RESULT_FILE=/work/agent-result.json")
        command += listOf("-e", "SF_AGENT_TIPS_FILE=/work/agent-tips.md")
        request.aiSupplier?.let { command += listOf("-e", "SF_AI_SUPPLIER=$it") }
        request.aiLevel?.let { command += listOf("-e", "SF_AI_LEVEL=$it") }
        request.aiModel?.let { command += listOf("-e", "SF_AI_MODEL=$it") }
        request.aiEffort?.let { command += listOf("-e", "SF_AI_EFFORT=$it") }
        request.agentMode?.let { command += listOf("-e", "SF_AGENT_MODE=$it") }
        request.baseBranch?.let { command += listOf("-e", "SF_BASE_BRANCH=$it") }
        request.branchPrefix?.let { command += listOf("-e", "SF_BRANCH_PREFIX=$it") }
        request.prNumber?.let { command += listOf("-e", "SF_PR_NUMBER=$it") }
        request.previewUrl?.let { command += listOf("-e", "SF_PREVIEW_URL=$it") }
        request.previewNamespace?.let { command += listOf("-e", "SF_PREVIEW_NAMESPACE=$it") }
        request.previewDbUrl?.let { command += listOf("-e", "SF_PREVIEW_DB_URL=$it") }
        request.developerLoopbackReason?.let { command += listOf("-e", "SF_DEVELOPER_LOOPBACK_REASON=$it") }

        if (factorySecrets.aiOauthToken.isNullOrBlank()) {
            factorySecrets.aiCredentialsDir?.takeIf { it.isNotBlank() }?.let {
                command += listOf("-v", "${localPath(it)}:/home/runner/.claude")
            }
        }
        if (request.role == AgentRole.TESTER) {
            factorySecrets.kubeconfig?.takeIf { it.isNotBlank() }?.let {
                command += listOf("-v", "${localPath(it)}:/home/runner/.kube/config:ro")
            }
        }

        command += imageFor(request.role)
        return command
    }

    private fun dockerPsNames(vararg filters: String): List<String> {
        val command = mutableListOf("docker", "ps", "--format", "{{.Names}}")
        filters.forEach { command += listOf("--filter", it) }
        val result = commandRunner.run(command)
        if (result.exitCode != 0) {
            return emptyList()
        }
        return result.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun imageFor(role: AgentRole): String =
        if (role == AgentRole.TESTER) "agent-tester:local" else "agent-base:local"

    private fun containerName(request: AgentDispatchRequest): String =
        "factory-${request.storyKey.lowercase()}-${request.role.markerKeyPart}-${DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(OffsetDateTime.now())}"
            .replace(Regex("[^a-z0-9_.-]"), "-")

    private fun localPath(value: String): String {
        val trimmed = value.trim()
        val expanded = when {
            trimmed == "~" -> System.getProperty("user.home")
            trimmed.startsWith("~/") -> System.getProperty("user.home") + trimmed.removePrefix("~")
            else -> trimmed
        }
        return Path.of(expanded).toAbsolutePath().normalize().toString()
    }
}

@Configuration
class DockerRuntimeConfiguration {
    @Bean
    fun dockerRuntimeSettings(factoryEnvironmentProvider: ConfigApi): DockerRuntimeSettings =
        DockerRuntimeSettings.fromEnvironment(factoryEnvironmentProvider.resolvedValues())
}
