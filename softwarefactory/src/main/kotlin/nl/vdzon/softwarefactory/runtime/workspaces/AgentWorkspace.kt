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
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

@Component
class AgentWorkspaceFactory(
    private val knowledgeApi: KnowledgeApi = EmptyKnowledgeApi,
) {
    fun create(request: AgentDispatchRequest, sfEnvironment: Map<String, String>): AgentWorkspace {
        val root = workspaceRoot()
        root.createDirectories()
        val workspace = Files.createTempDirectory(root, "${request.storyKey}-${request.role.markerKeyPart}-")
        val taskFile = workspace.resolve("task.md")
        taskFile.writeText(taskPayload(request))
        val tipsFile = workspace.resolve("agent-tips.md")
        tipsFile.writeText(tipsPayload(request))

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
            tipsFile = tipsFile,
            envFile = envFile,
        )
    }

    private fun taskPayload(request: AgentDispatchRequest): String =
        """
        # Factory Task

        - Story: `${request.storyKey}`
        - Role: `${request.role.markerKeyPart}`
        - Phase: `${request.phase.trackerValue}`
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
        fun workspaceRoot(): Path =
            Path.of(System.getProperty("user.home"), ".cache", "software-factory", "workspaces")
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
