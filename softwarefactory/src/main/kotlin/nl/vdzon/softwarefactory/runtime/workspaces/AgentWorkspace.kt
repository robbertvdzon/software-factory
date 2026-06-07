package nl.vdzon.softwarefactory.runtime.workspaces

import nl.vdzon.softwarefactory.orchestrator.AgentDispatchRequest
import nl.vdzon.softwarefactory.knowledge.AgentKnowledgeEntry
import nl.vdzon.softwarefactory.knowledge.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.OffsetDateTime
import java.util.Comparator
import kotlin.io.path.deleteIfExists
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

@Component
class AgentWorkspaceFactory(
    private val knowledgeApi: KnowledgeApi = EmptyKnowledgeApi,
) {
    fun create(request: AgentDispatchRequest, sfEnvironment: Map<String, String>): AgentWorkspace {
        val workspace = request.workspacePath
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it).toAbsolutePath().normalize().also { path -> path.createDirectories() } }
            ?: Files.createTempDirectory(workspaceRoot().also { it.createDirectories() }, "${request.storyKey}-${request.role.markerKeyPart}-")
        workspace.resolve(STORY_WORKSPACE_MARKER).takeIf { workspace.resolve("repo").exists() }?.writeText("software-factory story workspace\n")
        workspace.resolve("agent-result.json").deleteIfExists()
        if (request.role.markerKeyPart == "tester") {
            deleteRecursively(workspace.resolve("screenshots"))
        }
        val taskFile = workspace.resolve("task.md")
        taskFile.writeText(taskPayload(request))
        val tipsFile = workspace.resolve("agent-tips.md")
        tipsFile.writeText(tipsPayload(request))

        val envFile = workspace.resolve("factory.env")
        envFile.writeText(
            sfEnvironment
                .filterKeys { it.startsWith("SF_") }
                .filterKeys { it !in AGENT_ENV_DENYLIST }
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
            tipsFile = tipsFile,
            envFile = envFile,
        )
    }

    private fun taskPayload(request: AgentDispatchRequest): String =
        """
        # Factory Task

        - Story: `${request.storyKey}`
        - Role: `${request.role.markerKeyPart}`
        - Phase: `${request.phase}`
        - Target repo: `${request.targetRepo}`
        - Created at: `${OffsetDateTime.now()}`
        ${request.agentMode?.let { "- Mode: `$it`" } ?: ""}

        The agent must use the issue and target repository context for this run.
        """.trimIndent() + "\n" +
            taskContextPayload(request)

    private fun taskContextPayload(request: AgentDispatchRequest): String =
        request.trackerContext?.takeIf { it.isNotBlank() }?.let { "\n$it\n" }.orEmpty() +
            request.prCommentContext?.takeIf { it.isNotBlank() }?.let { "\n$it\n" }.orEmpty()

    private fun tipsPayload(request: AgentDispatchRequest): String =
        runCatching {
            knowledgeApi.find(request.targetRepo, request.role.markerKeyPart)
        }.getOrDefault(emptyList())
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n\n", postfix = "\n") { entry ->
                """
                ## ${entry.category} / ${entry.key}

                ${entry.content.trim()}
                """.trimIndent()
            }
            .orEmpty()

    companion object {
        const val STORY_WORKSPACE_MARKER = ".factory-story-workspace"

        val AGENT_ENV_DENYLIST = setOf(
            "SF_GITHUB_TOKEN",
            "SF_COPILOT_TOKEN",
        )

        fun workspaceRoot(): Path =
            projectRoot().resolve("work").resolve("agent-workspaces")

        fun storyWorkspaceRoot(): Path =
            projectRoot().resolve("work").resolve("stories")

        fun projectRoot(): Path {
            val cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
            val parent = cwd.parent
            return if (cwd.fileName?.toString() == "softwarefactory" && parent != null && parent.resolve("agentworker").exists()) {
                parent
            } else {
                cwd
            }
        }

        private fun deleteRecursively(path: Path) {
            if (!path.exists()) {
                return
            }
            Files.walk(path).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
            }
        }
    }
}

private object EmptyKnowledgeApi : KnowledgeApi {
    override fun find(targetRepo: String, role: String): List<AgentKnowledgeEntry> = emptyList()

    override fun upsert(request: AgentKnowledgeUpdateRequest): AgentKnowledgeEntry =
        error("The empty knowledge API cannot persist updates.")
}

data class AgentWorkspace(
    val path: Path,
    val taskFile: Path,
    val tipsFile: Path,
    val envFile: Path,
)
