package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.runtime.commands.*
import nl.vdzon.softwarefactory.runtime.docker.*
import nl.vdzon.softwarefactory.runtime.logging.*
import nl.vdzon.softwarefactory.runtime.repositories.*
import nl.vdzon.softwarefactory.runtime.workspaces.*

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

class AgentWorkspaceCleanerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `deletes workspace under configured root`() {
        val root = tempDir.resolve("workspaces").createDirectories()
        val workspace = root.resolve("KAN-1-developer").createDirectories()
        workspace.resolve("task.md").writeText("task")
        val cleaner = FileSystemAgentWorkspaceCleaner(
            settings = AgentWorkspaceCleanupSettings(enabled = true, preserveFailed = false),
            workspaceRoot = root,
        )

        assertTrue(cleaner.cleanup(workspace.toString(), failed = false))

        assertFalse(workspace.exists())
    }

    @Test
    fun `preserves failed workspace when configured and refuses paths outside root`() {
        val root = tempDir.resolve("workspaces").createDirectories()
        val failedWorkspace = root.resolve("KAN-2-developer").createDirectories()
        val cleaner = FileSystemAgentWorkspaceCleaner(
            settings = AgentWorkspaceCleanupSettings(enabled = true, preserveFailed = true),
            workspaceRoot = root,
        )

        assertFalse(cleaner.cleanup(failedWorkspace.toString(), failed = true))
        assertTrue(failedWorkspace.exists())
        assertThrows(IllegalArgumentException::class.java) {
            cleaner.cleanup(tempDir.resolve("outside").toString(), failed = false)
        }
    }
}
