package nl.vdzon.softwarefactory.docs

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class StoryLogWriter {
    fun recordDeveloperRunStart(
        repoRoot: Path,
        jiraKey: String,
        storyText: String,
    ): Path {
        val logFile = ensureInitialLog(repoRoot, jiraKey, storyText, DEFAULT_DEVELOPER_STEPS)
        markStepDone(logFile, DEFAULT_DEVELOPER_STEPS.first())
        appendDone(
            logFile,
            "Developer-run gestart: story en factory-docs gelezen zodat het plan in de target-repo zichtbaar is.",
        )
        return logFile
    }

    fun ensureInitialLog(
        repoRoot: Path,
        jiraKey: String,
        storyText: String,
        steps: List<String>,
    ): Path {
        val storiesDir = repoRoot.resolve("docs").resolve("stories")
        storiesDir.createDirectories()
        val logFile = storiesDir.resolve("$jiraKey-description.md")
        if (!logFile.exists()) {
            logFile.writeText(renderInitialLog(jiraKey, storyText, steps))
        }
        return logFile
    }

    fun markStepDone(logFile: Path, step: String): Boolean {
        if (!logFile.exists()) {
            return false
        }
        val text = logFile.readText()
        val unchecked = "[ ]: $step"
        if (!text.contains(unchecked)) {
            return false
        }
        logFile.writeText(text.replaceFirst(unchecked, "[x]: $step"))
        return true
    }

    fun appendDone(logFile: Path, message: String) {
        val text = logFile.readText()
        val entry = "- $message"
        if (text.contains(entry)) {
            return
        }
        val withSection = if (text.contains("Done / rationale:")) {
            text.trimEnd()
        } else {
            text.trimEnd() + "\n\nDone / rationale:"
        }
        logFile.writeText("$withSection\n$entry\n")
    }

    private fun renderInitialLog(jiraKey: String, storyText: String, steps: List<String>): String =
        buildString {
            appendLine("# $jiraKey - Story Log")
            appendLine()
            appendLine("Story:")
            appendLine(storyText.trim().ifBlank { "Nog geen story-context beschikbaar." })
            appendLine()
            appendLine("Stappenplan:")
            steps.forEach { step -> appendLine("[ ]: $step") }
            appendLine()
            appendLine("Done / rationale:")
            appendLine("- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.")
        }

    companion object {
        val DEFAULT_DEVELOPER_STEPS = listOf(
            "read Jira story and target docs",
            "implement requested changes",
            "run relevant tests",
            "update story-log with results",
        )
    }
}
