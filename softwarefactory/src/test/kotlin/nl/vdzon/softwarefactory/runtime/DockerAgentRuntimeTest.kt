package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.runtime.commands.*
import nl.vdzon.softwarefactory.runtime.docker.*
import nl.vdzon.softwarefactory.runtime.logging.*
import nl.vdzon.softwarefactory.runtime.repositories.*
import nl.vdzon.softwarefactory.runtime.services.*
import nl.vdzon.softwarefactory.runtime.workspaces.*

import nl.vdzon.softwarefactory.runtime.*
import nl.vdzon.softwarefactory.runtime.*
import nl.vdzon.softwarefactory.runtime.*
import nl.vdzon.softwarefactory.runtime.*
import nl.vdzon.softwarefactory.runtime.*

import nl.vdzon.softwarefactory.config.services.FactoryEnvironmentProvider
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.youtrack.AgentRole
import nl.vdzon.softwarefactory.orchestrator.AgentDispatchRequest
import nl.vdzon.softwarefactory.orchestrator.AiPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.readText

class DockerAgentRuntimeTest {
    @Test
    fun `dispatch builds docker command with labels workspace and only SF env file values`() {
        val commandRunner = FakeCommandRunner()
        val runtime = DockerAgentRuntime(
            factorySecrets = secrets(),
            factoryEnvironmentProvider = FakeEnvironmentProvider(
                mapOf(
                    "SF_YOUTRACK_BASE_URL" to "https://youtrack.example",
                    "SF_DATABASE_URL" to "postgresql://secret",
                    "SF_GITHUB_TOKEN" to "github-secret",
                    "PATH" to "/usr/bin",
                ),
            ),
            commandRunner = commandRunner,
            workspaceFactory = AgentWorkspaceFactory(),
            dockerRuntimeSettings = DockerRuntimeSettings(
                addHostGateway = true,
                memory = "2g",
                cpus = "2",
                logCaptureEnabled = true,
            ),
            dockerLogFollower = FakeDockerLogFollower(),
        )
        val request = AgentDispatchRequest(
            storyKey = "KAN-69",
            targetRepo = "git@github.com:robbertvdzon/sample-build-project.git",
            storyRunId = 1,
            role = AgentRole.DEVELOPER,
            phase = AiPhase.DEVELOPING,
            agentMode = "comment",
            trackerContext = "## Issue Context\n\n- Summary: test",
            prCommentContext = "## PR Comment Task Bundle\n\n@factory pas dit aan",
            aiLevel = 7,
            aiModel = "dummy-ai-client",
            aiEffort = "medium",
            aiSupplier = "claude",
        )

        val result = runtime.dispatch(request)

        val command = commandRunner.commands.single()
        assertEquals("docker", command[0])
        assertTrue(command.containsAll(listOf("run", "-d", "--rm", "--label", "story-key=KAN-69")))
        assertTrue(command.containsAll(listOf("--add-host", "host.docker.internal:host-gateway")))
        assertTrue(command.contains("--memory=2g"))
        assertTrue(command.contains("--cpus=2"))
        assertTrue(command.contains("SF_AGENT_TYPE=developer"))
        assertTrue(command.contains("SF_AGENT_MODE=comment"))
        assertTrue(command.contains("SF_AI_LEVEL=7"))
        assertTrue(command.contains("SF_AI_MODEL=dummy-ai-client"))
        assertTrue(command.contains("SF_AI_EFFORT=medium"))
        assertTrue(command.contains("SF_AI_SUPPLIER=claude"))
        assertTrue(command.contains("SF_REPO_ROOT=/work/repo"))
        assertTrue(command.contains("SF_CONTAINER_NAME=${result.containerName}"))
        assertTrue(command.contains("agent-base:local"))
        assertFalse(command.contains("PATH=/usr/bin"))

        val envFile = command[command.indexOf("--env-file") + 1]
        val envContent = java.nio.file.Path.of(envFile).readText()
        assertTrue(envContent.contains("SF_YOUTRACK_BASE_URL=https://youtrack.example"))
        assertTrue(envContent.contains("SF_DATABASE_URL=postgresql://secret"))
        assertFalse(envContent.contains("SF_GITHUB_TOKEN"))
        assertFalse(envContent.contains("PATH=/usr/bin"))

        val mount = command[command.indexOf("-v") + 1]
        val workspacePath = mount.substringBefore(":/work")
        val task = java.nio.file.Path.of(workspacePath).resolve("task.md").readText()
        assertTrue(task.contains("KAN-69"))
        assertTrue(task.contains("developer"))
        assertTrue(task.contains("Issue Context"))
        assertTrue(task.contains("PR Comment Task Bundle"))

        val aiCredentialsMount = command.windowed(2)
            .mapNotNull { (flag, value) -> value.takeIf { flag == "-v" } }
            .single { it.endsWith(":/home/runner/.claude") }
        assertTrue(aiCredentialsMount.startsWith(System.getProperty("user.home")))
    }

    @Test
    fun `oauth token keeps claude home writable by skipping credentials mount`() {
        val commandRunner = FakeCommandRunner()
        val runtime = DockerAgentRuntime(
            factorySecrets = secrets(aiOauthToken = "oauth-token"),
            factoryEnvironmentProvider = FakeEnvironmentProvider(emptyMap()),
            commandRunner = commandRunner,
            workspaceFactory = AgentWorkspaceFactory(),
            dockerRuntimeSettings = DockerRuntimeSettings(false, null, null, true),
            dockerLogFollower = FakeDockerLogFollower(),
        )
        val request = AgentDispatchRequest(
            storyKey = "KAN-69",
            targetRepo = "git@github.com:robbertvdzon/sample-build-project.git",
            storyRunId = 1,
            role = AgentRole.DEVELOPER,
            phase = AiPhase.DEVELOPING,
            aiSupplier = "claude",
        )

        runtime.dispatch(request)

        val command = commandRunner.commands.single()
        val mounts = command.windowed(2)
            .mapNotNull { (flag, value) -> value.takeIf { flag == "-v" } }
        assertFalse(mounts.any { it.contains(":/home/runner/.claude") })
    }

    @Test
    fun `copilot supplier mounts copilot credentials dir and skips claude mount`() {
        val commandRunner = FakeCommandRunner()
        val runtime = DockerAgentRuntime(
            factorySecrets = secrets(copilotCredentialsDir = "~/.copilot"),
            factoryEnvironmentProvider = FakeEnvironmentProvider(emptyMap()),
            commandRunner = commandRunner,
            workspaceFactory = AgentWorkspaceFactory(),
            dockerRuntimeSettings = DockerRuntimeSettings(false, null, null, true),
            dockerLogFollower = FakeDockerLogFollower(),
        )
        val request = AgentDispatchRequest(
            storyKey = "KAN-69",
            targetRepo = "git@github.com:robbertvdzon/sample-build-project.git",
            storyRunId = 1,
            role = AgentRole.DEVELOPER,
            phase = AiPhase.DEVELOPING,
            aiSupplier = "copilot",
        )

        runtime.dispatch(request)

        val mounts = commandRunner.commands.last().windowed(2)
            .mapNotNull { (flag, value) -> value.takeIf { flag == "-v" } }
        val copilotMount = mounts.single { it.endsWith(":/home/runner/.copilot") }
        assertTrue(copilotMount.startsWith(System.getProperty("user.home")))
        assertFalse(mounts.any { it.contains(":/home/runner/.claude") })
    }

    @Test
    fun `copilot supplier passes host gh auth token through transient env file`() {
        val commandRunner = FakeCommandRunner(ghAuthToken = "gho-host-token")
        val runtime = DockerAgentRuntime(
            factorySecrets = secrets(copilotCredentialsDir = "~/.copilot"),
            factoryEnvironmentProvider = FakeEnvironmentProvider(emptyMap()),
            commandRunner = commandRunner,
            workspaceFactory = AgentWorkspaceFactory(),
            dockerRuntimeSettings = DockerRuntimeSettings(false, null, null, true),
            dockerLogFollower = FakeDockerLogFollower(),
        )
        val request = AgentDispatchRequest(
            storyKey = "KAN-69",
            targetRepo = "git@github.com:robbertvdzon/sample-build-project.git",
            storyRunId = 1,
            role = AgentRole.DEVELOPER,
            phase = AiPhase.DEVELOPING,
            aiSupplier = "copilot",
        )

        runtime.dispatch(request)

        assertEquals(listOf("gh", "auth", "token"), commandRunner.commands.first())
        val dockerCommand = commandRunner.commands.last()
        assertFalse(dockerCommand.any { it.contains("gho-host-token") })
        assertTrue(commandRunner.envFileSnapshots.any { it == "COPILOT_GITHUB_TOKEN=gho-host-token\n" })
    }

    @Test
    fun `explicit copilot token is passed through transient env file and omitted from workspace env file`() {
        val commandRunner = FakeCommandRunner()
        val runtime = DockerAgentRuntime(
            factorySecrets = secrets(copilotCredentialsDir = "~/.copilot"),
            factoryEnvironmentProvider = FakeEnvironmentProvider(mapOf("SF_COPILOT_TOKEN" to "copilot-secret")),
            commandRunner = commandRunner,
            workspaceFactory = AgentWorkspaceFactory(),
            dockerRuntimeSettings = DockerRuntimeSettings(false, null, null, true),
            dockerLogFollower = FakeDockerLogFollower(),
        )
        val request = AgentDispatchRequest(
            storyKey = "KAN-69",
            targetRepo = "git@github.com:robbertvdzon/sample-build-project.git",
            storyRunId = 1,
            role = AgentRole.DEVELOPER,
            phase = AiPhase.DEVELOPING,
            aiSupplier = "copilot",
        )

        runtime.dispatch(request)

        assertTrue(commandRunner.commands.none { it == listOf("gh", "auth", "token") })
        val dockerCommand = commandRunner.commands.single()
        assertFalse(dockerCommand.any { it.contains("copilot-secret") })
        assertTrue(commandRunner.envFileSnapshots.any { it == "COPILOT_GITHUB_TOKEN=copilot-secret\n" })
        assertTrue(commandRunner.envFileSnapshots.none { it.contains("SF_COPILOT_TOKEN") })
    }

    @Test
    fun `running count uses docker labels`() {
        val commandRunner = FakeCommandRunner(psOutput = "factory-kan-1-refiner\nfactory-kan-2-refiner\n")
        val runtime = DockerAgentRuntime(
            factorySecrets = secrets(),
            factoryEnvironmentProvider = FakeEnvironmentProvider(emptyMap()),
            commandRunner = commandRunner,
            workspaceFactory = AgentWorkspaceFactory(),
            dockerRuntimeSettings = DockerRuntimeSettings(false, null, null, true),
            dockerLogFollower = FakeDockerLogFollower(),
        )

        assertEquals(2, runtime.runningCount(AgentRole.REFINER))
        assertTrue(commandRunner.commands.single().contains("label=role=refiner"))
    }

    @Test
    fun `container running check uses docker inspect state`() {
        val commandRunner = FakeCommandRunner(inspectOutput = "true\n")
        val runtime = DockerAgentRuntime(
            factorySecrets = secrets(),
            factoryEnvironmentProvider = FakeEnvironmentProvider(emptyMap()),
            commandRunner = commandRunner,
            workspaceFactory = AgentWorkspaceFactory(),
            dockerRuntimeSettings = DockerRuntimeSettings(false, null, null, true),
            dockerLogFollower = FakeDockerLogFollower(),
        )

        assertTrue(runtime.isContainerRunning("factory-kan-69-refiner"))
        assertEquals(
            listOf("docker", "inspect", "--format", "{{.State.Running}}", "factory-kan-69-refiner"),
            commandRunner.commands.single(),
        )
    }

    @Test
    fun `container running check returns false when inspect cannot find container`() {
        val runtime = DockerAgentRuntime(
            factorySecrets = secrets(),
            factoryEnvironmentProvider = FakeEnvironmentProvider(emptyMap()),
            commandRunner = FakeCommandRunner(inspectExitCode = 1, inspectOutput = ""),
            workspaceFactory = AgentWorkspaceFactory(),
            dockerRuntimeSettings = DockerRuntimeSettings(false, null, null, true),
            dockerLogFollower = FakeDockerLogFollower(),
        )

        assertFalse(runtime.isContainerRunning("factory-kan-69-refiner"))
    }

    @Test
    fun `kill for story kills all containers with story label`() {
        val commandRunner = FakeCommandRunner(psOutput = "factory-kan-69-developer\nfactory-kan-69-reviewer\n")
        val runtime = DockerAgentRuntime(
            factorySecrets = secrets(),
            factoryEnvironmentProvider = FakeEnvironmentProvider(emptyMap()),
            commandRunner = commandRunner,
            workspaceFactory = AgentWorkspaceFactory(),
            dockerRuntimeSettings = DockerRuntimeSettings(false, null, null, true),
            dockerLogFollower = FakeDockerLogFollower(),
        )

        val killed = runtime.killForStory("KAN-69")

        assertEquals(2, killed)
        assertTrue(commandRunner.commands[0].contains("label=story-key=KAN-69"))
        assertEquals(listOf("docker", "kill", "factory-kan-69-developer"), commandRunner.commands[1])
        assertEquals(listOf("docker", "kill", "factory-kan-69-reviewer"), commandRunner.commands[2])
    }

    @Test
    fun `tester dispatch adds preview env and readonly kubeconfig mount`() {
        val commandRunner = FakeCommandRunner()
        val runtime = DockerAgentRuntime(
            factorySecrets = secrets(),
            factoryEnvironmentProvider = FakeEnvironmentProvider(emptyMap()),
            commandRunner = commandRunner,
            workspaceFactory = AgentWorkspaceFactory(),
            dockerRuntimeSettings = DockerRuntimeSettings(false, null, null, true),
            dockerLogFollower = FakeDockerLogFollower(),
        )
        val request = AgentDispatchRequest(
            storyKey = "KAN-69",
            targetRepo = "git@github.com:robbertvdzon/sample-build-project.git",
            storyRunId = 1,
            role = AgentRole.TESTER,
            phase = AiPhase.TESTING,
            baseBranch = "main",
            branchPrefix = "ai/",
            prNumber = 42,
            previewUrl = "https://app-pr-42.example.com",
            previewNamespace = "app-pr-42",
            previewDbUrl = "postgresql://preview",
        )

        runtime.dispatch(request)

        val command = commandRunner.commands.single()
        assertTrue(command.contains("SF_BASE_BRANCH=main"))
        assertTrue(command.contains("SF_BRANCH_PREFIX=ai/"))
        assertTrue(command.contains("SF_PR_NUMBER=42"))
        assertTrue(command.contains("SF_PREVIEW_URL=https://app-pr-42.example.com"))
        assertTrue(command.contains("SF_PREVIEW_NAMESPACE=app-pr-42"))
        assertTrue(command.contains("SF_PREVIEW_DB_URL=postgresql://preview"))
        assertTrue(command.contains("agent-tester:local"))

        val kubeconfigMount = command.windowed(2)
            .mapNotNull { (flag, value) -> value.takeIf { flag == "-v" } }
            .single { it.endsWith(":/home/runner/.kube/config:ro") }
        assertTrue(kubeconfigMount.startsWith(System.getProperty("user.home")))
    }

    @Test
    fun `capture logs delegates to docker log follower when enabled`() {
        val logFollower = FakeDockerLogFollower()
        val runtime = DockerAgentRuntime(
            factorySecrets = secrets(),
            factoryEnvironmentProvider = FakeEnvironmentProvider(emptyMap()),
            commandRunner = FakeCommandRunner(),
            workspaceFactory = AgentWorkspaceFactory(),
            dockerRuntimeSettings = DockerRuntimeSettings(false, null, null, true),
            dockerLogFollower = logFollower,
        )

        runtime.captureLogs("factory-kan-69-developer", 123)

        assertEquals(listOf("factory-kan-69-developer" to 123L), logFollower.followed)
    }

    @Test
    fun `docker runtime settings read optional resource limits from environment`() {
        val settings = DockerRuntimeSettings.fromEnvironment(
            mapOf(
                "SF_DOCKER_ADD_HOST_GATEWAY" to "true",
                "SF_AGENT_DOCKER_MEMORY" to "3g",
                "SF_AGENT_DOCKER_CPUS" to "1.5",
                "SF_DOCKER_LOG_CAPTURE_ENABLED" to "false",
            ),
        )

        assertTrue(settings.addHostGateway)
        assertEquals("3g", settings.memory)
        assertEquals("1.5", settings.cpus)
        assertFalse(settings.logCaptureEnabled)
    }

    private fun secrets(
        aiOauthToken: String? = null,
        copilotCredentialsDir: String? = null,
    ): FactorySecrets =
        FactorySecrets(
            youTrackBaseUrl = "https://youtrack.example",
            youTrackToken = "youtrack-token",
            youTrackProjects = listOf("KAN"),
            githubToken = "github-token",
            factoryDatabaseUrl = "postgresql://example/db",
            factoryDatabaseSchema = "software_factory",
            kubeconfig = "~/.kube/config",
            aiCredentialsDir = "~/.claude",
            aiOauthToken = aiOauthToken,
            copilotCredentialsDir = copilotCredentialsDir,
            loadedFrom = "test",
        )

    private class FakeEnvironmentProvider(
        private val values: Map<String, String>,
    ) : FactoryEnvironmentProvider {
        override fun resolvedValues(): Map<String, String> = values
    }

    private class FakeCommandRunner(
        private val psOutput: String = "container-id\n",
        private val inspectOutput: String = "false\n",
        private val inspectExitCode: Int = 0,
        private val ghAuthToken: String = "gho-test-token",
    ) : CommandRunner {
        val commands = mutableListOf<List<String>>()
        val envFileSnapshots = mutableListOf<String>()

        override fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
            commands += command
            if (command == listOf("gh", "auth", "token")) {
                return CommandResult(0, "$ghAuthToken\n", "")
            }
            if (command.take(2) == listOf("docker", "run")) {
                command.windowed(2)
                    .filter { (flag, _) -> flag == "--env-file" }
                    .map { (_, value) -> java.nio.file.Path.of(value) }
                    .filter { java.nio.file.Files.exists(it) }
                    .forEach { envFileSnapshots += it.readText() }
            }
            return when (command.getOrNull(1)) {
                "ps" -> CommandResult(0, psOutput, "")
                "inspect" -> CommandResult(inspectExitCode, inspectOutput, "")
                else -> CommandResult(0, "container-id\n", "")
            }
        }
    }

    private class FakeDockerLogFollower : DockerLogFollower {
        val followed = mutableListOf<Pair<String, Long>>()

        override fun follow(containerName: String, agentRunId: Long) {
            followed += containerName to agentRunId
        }
    }
}
