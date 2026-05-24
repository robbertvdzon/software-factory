package nl.vdzon.softwarefactory.docs

import nl.vdzon.softwarefactory.docs.services.*

import nl.vdzon.softwarefactory.docs.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class DocsSkeletonInstallerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `installs docs skeleton without overwriting existing files`() {
        val installer = DocsSkeletonInstaller()

        val firstResult = installer.install(tempDir)

        assertTrue(firstResult.created.contains("docs/factory/README.md"))
        assertTrue(tempDir.resolve("docs/factory/deployment.md").exists())
        assertTrue(tempDir.resolve("docs/factory/agents/developer.md").exists())
        assertTrue(tempDir.resolve("docs/stories/.gitkeep").exists())

        val readme = tempDir.resolve("docs/factory/README.md")
        readme.writeText("custom readme")

        val secondResult = installer.install(tempDir)

        assertTrue(secondResult.skipped.contains("docs/factory/README.md"))
        assertEquals("custom readme", readme.readText())
    }
}
