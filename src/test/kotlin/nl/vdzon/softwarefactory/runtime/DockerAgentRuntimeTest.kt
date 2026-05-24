package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.config.FactoryEnvironmentProvider
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.jira.AgentRole
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
                    "SF_JIRA_BASE_URL" to "https://jira.example",
                    "SF_DATABASE_URL" to "postgresql://secret",
                    "PATH" to "/usr/bin",
                ),
            ),
            commandRunner = commandRunner,
            workspaceFactory = AgentWorkspaceFactory(),
        )
        val request = AgentDispatchRequest(
            storyKey = "KAN-69",
            targetRepo = "git@github.com:robbertvdzon/sample-build-project.git",
            storyRunId = 1,
            role = AgentRole.DEVELOPER,
            phase = AiPhase.DEVELOPING,
        )

        val result = runtime.dispatch(request)

        val command = commandRunner.commands.single()
        assertEquals("docker", command[0])
        assertTrue(command.containsAll(listOf("run", "-d", "--rm", "--label", "story-key=KAN-69")))
        assertTrue(command.contains("SF_AGENT_TYPE=developer"))
        assertTrue(command.contains("SF_REPO_ROOT=/work/repo"))
        assertTrue(command.contains("SF_CONTAINER_NAME=${result.containerName}"))
        assertTrue(command.contains("agent-base:local"))
        assertFalse(command.contains("PATH=/usr/bin"))

        val envFile = command[command.indexOf("--env-file") + 1]
        val envContent = java.nio.file.Path.of(envFile).readText()
        assertTrue(envContent.contains("SF_JIRA_BASE_URL=https://jira.example"))
        assertTrue(envContent.contains("SF_DATABASE_URL=postgresql://secret"))
        assertFalse(envContent.contains("PATH=/usr/bin"))

        val mount = command[command.indexOf("-v") + 1]
        val workspacePath = mount.substringBefore(":/work")
        val task = java.nio.file.Path.of(workspacePath).resolve("task.md").readText()
        assertTrue(task.contains("KAN-69"))
        assertTrue(task.contains("developer"))

        val aiCredentialsMount = command.windowed(2)
            .mapNotNull { (flag, value) -> value.takeIf { flag == "-v" } }
            .single { it.endsWith(":/home/runner/.claude:ro") }
        assertTrue(aiCredentialsMount.startsWith(System.getProperty("user.home")))
    }

    @Test
    fun `running count uses docker labels`() {
        val commandRunner = FakeCommandRunner(psOutput = "factory-kan-1-refiner\nfactory-kan-2-refiner\n")
        val runtime = DockerAgentRuntime(
            factorySecrets = secrets(),
            factoryEnvironmentProvider = FakeEnvironmentProvider(emptyMap()),
            commandRunner = commandRunner,
            workspaceFactory = AgentWorkspaceFactory(),
        )

        assertEquals(2, runtime.runningCount(AgentRole.REFINER))
        assertTrue(commandRunner.commands.single().contains("label=role=refiner"))
    }

    @Test
    fun `tester dispatch adds preview env and readonly kubeconfig mount`() {
        val commandRunner = FakeCommandRunner()
        val runtime = DockerAgentRuntime(
            factorySecrets = secrets(),
            factoryEnvironmentProvider = FakeEnvironmentProvider(emptyMap()),
            commandRunner = commandRunner,
            workspaceFactory = AgentWorkspaceFactory(),
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

    private fun secrets(): FactorySecrets =
        FactorySecrets(
            jiraBaseUrl = "https://jira.example",
            jiraEmail = "robbert@example.com",
            jiraApiKey = "jira-token",
            githubToken = "github-token",
            factoryDatabaseUrl = "postgresql://example/db",
            factoryDatabaseSchema = "software_factory",
            kubeconfig = "~/.kube/config",
            aiCredentialsDir = "~/.claude",
            aiOauthToken = null,
            loadedFrom = "test",
        )

    private class FakeEnvironmentProvider(
        private val values: Map<String, String>,
    ) : FactoryEnvironmentProvider {
        override fun resolvedValues(): Map<String, String> = values
    }

    private class FakeCommandRunner(
        private val psOutput: String = "container-id\n",
    ) : CommandRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
            commands += command
            return if (command.getOrNull(1) == "ps") {
                CommandResult(0, psOutput, "")
            } else {
                CommandResult(0, "container-id\n", "")
            }
        }
    }
}
