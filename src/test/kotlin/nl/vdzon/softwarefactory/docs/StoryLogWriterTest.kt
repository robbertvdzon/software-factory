package nl.vdzon.softwarefactory.docs

import nl.vdzon.softwarefactory.docs.services.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText

class StoryLogWriterTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `developer run creates story log and marks first step done`() {
        val writer = StoryLogWriter()

        val logFile = writer.recordDeveloperRunStart(tempDir, "KAN-42", "Maak een endpoint voor rapportages.")
        writer.recordDeveloperRunStart(tempDir, "KAN-42", "Maak een endpoint voor rapportages.")

        assertEquals("KAN-42-maak-een-endpoint-voor-rapportages.md", logFile.fileName.toString())
        val text = logFile.readText()
        assertTrue(text.contains("# KAN-42 - Story Log"))
        assertTrue(text.contains("Maak een endpoint voor rapportages."))
        assertTrue(text.contains("[x]: read issue and target docs"))
        assertTrue(text.contains("[ ]: implement requested changes"))
        assertEquals(
            1,
            Regex("Developer-run gestart").findAll(text).count(),
            "rationale entry should be idempotent",
        )
    }

    @Test
    fun `developer run reuses existing story log for issue key`() {
        val writer = StoryLogWriter()
        val firstLog = writer.recordDeveloperRunStart(tempDir, "KAN-42", "Maak een endpoint voor rapportages.")
        val secondLog = writer.recordDeveloperRunStart(tempDir, "KAN-42", "Andere titel na refinement.")

        assertEquals(firstLog, secondLog)
    }
}
