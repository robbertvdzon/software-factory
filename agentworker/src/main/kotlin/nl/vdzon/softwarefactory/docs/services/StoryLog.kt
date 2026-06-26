package nl.vdzon.softwarefactory.docs.services

import java.text.Normalizer
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

class StoryLogWriter {
    fun recordDeveloperRunStart(
        repoRoot: Path,
        issueTrackerKey: String,
        storyText: String,
    ): Path {
        val logFile = ensureInitialLog(repoRoot, issueTrackerKey, storyText, DEFAULT_DEVELOPER_STEPS)
        markStepDone(logFile, DEFAULT_DEVELOPER_STEPS.first())
        appendDone(
            logFile,
            "Developer-run gestart: story en factory-docs gelezen zodat het plan in de target-repo zichtbaar is.",
        )
        return logFile
    }

    fun ensureStoryWorklog(
        repoRoot: Path,
        issueTrackerKey: String,
        summary: String,
        description: String?,
    ): Path =
        ensureInitialLog(
            repoRoot = repoRoot,
            issueTrackerKey = issueTrackerKey,
            storyText = storyText(summary, description),
            steps = DEFAULT_DEVELOPER_STEPS,
        )

    fun writeFinalStory(
        repoRoot: Path,
        issueTrackerKey: String,
        summary: String,
        description: String?,
        finalSummary: String,
    ): Path {
        val storiesDir = repoRoot.resolve("docs").resolve("stories")
        storiesDir.createDirectories()
        val finalFile = existingFinalStory(storiesDir, issueTrackerKey)
            ?: storiesDir.resolve("$issueTrackerKey-${storySlug(summary)}.md")
        finalFile.writeText(renderFinalStory(issueTrackerKey, summary, description, finalSummary))
        return finalFile
    }

    fun ensureInitialLog(
        repoRoot: Path,
        issueTrackerKey: String,
        storyText: String,
        steps: List<String>,
    ): Path {
        val worklogDir = repoRoot.resolve("docs").resolve("stories").resolve("worklog")
        worklogDir.createDirectories()
        val logFile = existingStoryLog(worklogDir, issueTrackerKey)
            ?: worklogDir.resolve("$issueTrackerKey-worklog.md")
        if (!logFile.exists()) {
            logFile.writeText(renderInitialLog(issueTrackerKey, storyText, steps))
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

    private fun renderInitialLog(issueTrackerKey: String, storyText: String, steps: List<String>): String =
        buildString {
            appendLine("# $issueTrackerKey - Worklog")
            appendLine()
            appendLine("Story-context bij eerste pickup:")
            appendLine(storyText.trim().ifBlank { "Nog geen story-context beschikbaar." })
            appendLine()
            appendLine("Stappenplan:")
            steps.forEach { step -> appendLine("[ ]: $step") }
            appendLine()
            appendLine("Done / rationale:")
            appendLine("- Story-log aangemaakt zodat plan, voortgang en uitvoering onderdeel worden van de PR.")
        }

    private fun renderFinalStory(
        issueTrackerKey: String,
        summary: String,
        description: String?,
        finalSummary: String,
    ): String =
        buildString {
            appendLine("# $issueTrackerKey - ${summary.trim().ifBlank { "Story" }}")
            appendLine()
            appendLine("## Story")
            appendLine()
            appendLine(summary.trim().ifBlank { issueTrackerKey })
            description?.trim()?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine(it)
            }
            appendLine()
            appendLine("## Eindsamenvatting")
            appendLine()
            appendLine(finalSummary.trim().ifBlank { "Geen eindsamenvatting beschikbaar." })
        }

    private fun existingStoryLog(worklogDir: Path, issueTrackerKey: String): Path? {
        if (!worklogDir.exists()) {
            return null
        }
        return worklogDir.toFile()
            .listFiles { file -> file.isFile && file.name.startsWith("$issueTrackerKey-") && file.name.endsWith(".md") }
            ?.map { it.toPath() }
            ?.minByOrNull { it.name }
    }

    private fun existingFinalStory(storiesDir: Path, issueTrackerKey: String): Path? {
        if (!storiesDir.exists()) {
            return null
        }
        return storiesDir.toFile()
            .listFiles { file -> file.isFile && file.name.startsWith("$issueTrackerKey-") && file.name.endsWith(".md") }
            ?.map { it.toPath() }
            ?.minByOrNull { it.name }
    }

    private fun storyText(summary: String, description: String?): String =
        buildString {
            appendLine(summary.trim().ifBlank { "Geen summary beschikbaar." })
            description?.trim()?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine(it)
            }
        }

    private fun storySlug(storyText: String): String {
        val title = storyText
            .lineSequence()
            .map { it.trim().trimStart('#').trim() }
            .firstOrNull { it.isNotBlank() }
            ?: "story"
        val withoutKey = title.replace(Regex("""^[A-Z][A-Z0-9]+-\d+\s*[-:]\s*"""), "")
        val normalized = Normalizer.normalize(withoutKey, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""[^a-zA-Z0-9]+"""), "-")
            .trim('-')
        return normalized
            .split("-")
            .filter { it.isNotBlank() }
            .take(8)
            .joinToString("-")
            .ifBlank { "story" }
    }

    companion object {
        val DEFAULT_DEVELOPER_STEPS = listOf(
            "read issue and target docs",
            "implement requested changes",
            "run relevant tests",
            "update story-log with results",
        )
    }
}
