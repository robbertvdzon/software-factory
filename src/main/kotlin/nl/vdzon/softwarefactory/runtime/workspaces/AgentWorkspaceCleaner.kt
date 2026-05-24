package nl.vdzon.softwarefactory.runtime.workspaces

import nl.vdzon.softwarefactory.config.ConfigApi
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

data class AgentWorkspaceCleanupSettings(
    val enabled: Boolean,
    val preserveFailed: Boolean,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String>): AgentWorkspaceCleanupSettings =
            AgentWorkspaceCleanupSettings(
                enabled = environment.boolean("SF_AGENT_WORKSPACE_CLEANUP_ENABLED", default = true),
                preserveFailed = environment.boolean("SF_AGENT_WORKSPACE_PRESERVE_ON_FAILURE", default = false),
            )

        private fun Map<String, String>.boolean(key: String, default: Boolean): Boolean =
            this[key]?.takeIf { it.isNotBlank() }?.toBooleanStrictOrNull() ?: default
    }
}

interface AgentWorkspaceCleaner {
    fun cleanup(workspacePath: String?, failed: Boolean): Boolean
}

@Component
class FileSystemAgentWorkspaceCleaner(
    private val settings: AgentWorkspaceCleanupSettings,
    private val workspaceRoot: Path = AgentWorkspaceFactory.workspaceRoot(),
) : AgentWorkspaceCleaner {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun cleanup(workspacePath: String?, failed: Boolean): Boolean {
        if (!settings.enabled || workspacePath.isNullOrBlank()) {
            return false
        }
        if (failed && settings.preserveFailed) {
            return false
        }

        val root = workspaceRoot.toAbsolutePath().normalize()
        val workspace = Path.of(workspacePath).toAbsolutePath().normalize()
        require(workspace.startsWith(root)) {
            "Refusing to delete workspace outside software-factory workspace root: $workspace"
        }
        if (!Files.exists(workspace)) {
            return false
        }

        return runCatching {
            Files.walk(workspace).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    Files.deleteIfExists(path)
                }
            }
            true
        }.onFailure { exception ->
            logger.warn("Workspace cleanup failed for {}", workspace, exception)
        }.getOrDefault(false)
    }
}

@Configuration
class AgentWorkspaceCleanupConfiguration {
    @Bean
    fun agentWorkspaceCleanupSettings(factoryEnvironmentProvider: ConfigApi): AgentWorkspaceCleanupSettings =
        AgentWorkspaceCleanupSettings.fromEnvironment(factoryEnvironmentProvider.resolvedValues())
}
