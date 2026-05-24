package nl.vdzon.softwarefactory.docs.services

import nl.vdzon.softwarefactory.docs.DeploymentConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

object DeploymentConfigParser {
    private val keyValuePattern = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(.*)$""")

    fun parseFile(path: Path): DeploymentConfig? =
        path.takeIf { it.exists() }?.readText()?.let(::parse)

    fun parse(markdown: String): DeploymentConfig? {
        val lines = markdown.lines()
        if (lines.firstOrNull()?.trim() != "---") {
            return null
        }
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) {
            return null
        }

        val values = parseFrontmatter(lines.subList(1, end + 1))
        return DeploymentConfig(
            defaultBaseBranch = values["default_base_branch"] ?: "main",
            branchPrefix = values["branch_prefix"] ?: "ai/",
            previewUrlTemplate = values["preview_url_template"],
            previewNamespaceTemplate = values["preview_namespace_template"],
            previewDbSecretRecipe = values["preview_db_secret_recipe"],
        )
    }

    private fun parseFrontmatter(lines: List<String>): Map<String, String> {
        val values = linkedMapOf<String, String>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val match = keyValuePattern.matchEntire(line.trim())
            if (match == null) {
                index++
                continue
            }

            val key = match.groupValues[1]
            val rawValue = match.groupValues[2].trim()
            if (rawValue == "|") {
                val blockLines = mutableListOf<String>()
                index++
                while (index < lines.size && !isNextTopLevelKey(lines[index])) {
                    blockLines += lines[index]
                    index++
                }
                values[key] = stripCommonIndent(blockLines)
            } else {
                values[key] = unquote(rawValue)
                index++
            }
        }
        return values
    }

    private fun isNextTopLevelKey(line: String): Boolean =
        line.isNotBlank() && !line.startsWith(" ") && keyValuePattern.matches(line.trim())

    private fun stripCommonIndent(lines: List<String>): String {
        val commonIndent = lines
            .filter { it.isNotBlank() }
            .minOfOrNull { line -> line.takeWhile { it == ' ' }.length }
            ?: 0
        return lines.joinToString("\n") { line ->
            if (line.length >= commonIndent) line.drop(commonIndent) else ""
        }.trimEnd()
    }

    private fun unquote(value: String): String =
        value.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
}
