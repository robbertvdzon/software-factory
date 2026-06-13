package nl.vdzon.softwarefactory.docs

import nl.vdzon.softwarefactory.docs.services.*

import nl.vdzon.softwarefactory.docs.*

import nl.vdzon.softwarefactory.core.AgentRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FactoryDocsLoaderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `missing docs factory returns bootstrap notice instead of failing`() {
        val context = FactoryDocsLoader().loadFactoryDocs(AgentRole.REFINER, tempDir)

        assertFalse(context.hasFactoryDocs)
        assertNull(context.deploymentConfig)
        assertTrue(context.promptMarkdown().contains("docs/factory/"))
        assertTrue(context.promptMarkdown().contains("/usr/local/share/factory/docs-skeleton/"))
    }

    @Test
    fun `loads docs index role instructions and deployment config`() {
        DocsSkeletonInstaller().install(tempDir)

        val context = FactoryDocsLoader().loadFactoryDocs(AgentRole.DEVELOPER, tempDir)

        assertTrue(context.hasFactoryDocs)
        assertTrue(context.indexMarkdown.contains("development.md"))
        assertTrue(context.indexMarkdown.contains("agents/developer.md"))
        assertTrue(context.roleInstructions.contains("story-worklog"))
        assertEquals("main", context.deploymentConfig?.defaultBaseBranch)
        assertEquals("ai/", context.deploymentConfig?.branchPrefix)
        assertEquals("https://example-pr-{pr_num}.example.com", context.deploymentConfig?.previewUrlTemplate)
    }
}
