package nl.vdzon.softwarefactory.agent.ai.shared

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

/** Beheert tijdelijke agentbestanden en lokale git-excludes voor alle CLI-suppliers. */
object TaskFileManager {
    fun write(repoRoot: Path, taskMarkdown: String, extraIgnoredFiles: List<String> = emptyList()): Path {
        val taskFile = repoRoot.resolve(".task.md")
        taskFile.writeText(taskMarkdown.trimEnd() + "\n")
        val exclude = repoRoot.resolve(".git/info/exclude")
        runCatching {
            if (Files.exists(exclude)) {
                val current = Files.readString(exclude)
                val missing = (listOf(".task.md") + extraIgnoredFiles).filterNot(current.lines()::contains)
                if (missing.isNotEmpty()) {
                    Files.writeString(exclude, current.trimEnd() + "\n" + missing.joinToString("\n") + "\n")
                }
            }
        }
        return taskFile
    }

    fun cleanup(vararg files: Path) {
        files.forEach(Path::deleteIfExists)
    }
}
