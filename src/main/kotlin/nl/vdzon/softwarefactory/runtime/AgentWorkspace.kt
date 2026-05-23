package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.orchestrator.AgentDispatchRequest
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.OffsetDateTime
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

@Component
class AgentWorkspaceFactory {
    fun create(request: AgentDispatchRequest, sfEnvironment: Map<String, String>): AgentWorkspace {
        val root = workspaceRoot()
        root.createDirectories()
        val workspace = Files.createTempDirectory(root, "${request.storyKey}-${request.role.markerKeyPart}-")
        val taskFile = workspace.resolve("task.md")
        taskFile.writeText(taskPayload(request))

        val envFile = workspace.resolve("factory.env")
        envFile.writeText(
            sfEnvironment
                .filterKeys { it.startsWith("SF_") }
                .toSortedMap()
                .entries
                .joinToString(separator = "\n", postfix = "\n") { (key, value) -> "$key=$value" },
        )
        runCatching {
            Files.setPosixFilePermissions(envFile, PosixFilePermissions.fromString("rw-------"))
        }

        return AgentWorkspace(
            path = workspace,
            taskFile = taskFile,
            envFile = envFile,
        )
    }

    private fun workspaceRoot(): Path =
        Path.of(System.getProperty("user.home"), ".cache", "software-factory", "workspaces")

    private fun taskPayload(request: AgentDispatchRequest): String =
        """
        # Factory Task

        - Story: `${request.storyKey}`
        - Role: `${request.role.markerKeyPart}`
        - Phase: `${request.phase.jiraValue}`
        - Target repo: `${request.targetRepo}`
        - Created at: `${OffsetDateTime.now()}`

        The agent must use the Jira story and target repository context for this run.
        """.trimIndent() + "\n"
}

data class AgentWorkspace(
    val path: Path,
    val taskFile: Path,
    val envFile: Path,
)
