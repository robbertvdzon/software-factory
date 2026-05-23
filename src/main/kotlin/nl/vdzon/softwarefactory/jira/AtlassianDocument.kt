package nl.vdzon.softwarefactory.jira

import com.fasterxml.jackson.databind.JsonNode

object AtlassianDocument {
    fun plainTextDocument(text: String): Map<String, Any> =
        mapOf(
            "type" to "doc",
            "version" to 1,
            "content" to text.split("\n").map { line ->
                mapOf(
                    "type" to "paragraph",
                    "content" to if (line.isEmpty()) {
                        emptyList<Map<String, String>>()
                    } else {
                        listOf(
                            mapOf(
                                "type" to "text",
                                "text" to line,
                            ),
                        )
                    },
                )
            },
        )

    fun toPlainText(body: JsonNode?): String {
        if (body == null || body.isNull) {
            return ""
        }
        if (body.isTextual) {
            return body.asText()
        }

        val builder = StringBuilder()
        appendPlainText(body, builder)
        return builder.toString()
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .trim()
    }

    private fun appendPlainText(node: JsonNode, builder: StringBuilder) {
        when {
            node.isTextual -> builder.append(node.asText())
            node.isObject -> appendObjectPlainText(node, builder)
            node.isArray -> node.forEach { appendPlainText(it, builder) }
        }
    }

    private fun appendObjectPlainText(node: JsonNode, builder: StringBuilder) {
        when (node.path("type").asText()) {
            "text" -> builder.append(node.path("text").asText())
            "hardBreak" -> builder.append('\n')
            "emoji" -> builder.append(
                node.path("attrs").path("text").asText(node.path("attrs").path("shortName").asText()),
            )
            else -> {
                node.path("content").takeIf { it.isArray }?.forEach { appendPlainText(it, builder) }
                if (node.path("type").asText() in blockNodeTypes && builder.lastOrNull() != '\n') {
                    builder.append('\n')
                }
            }
        }
    }

    private val blockNodeTypes = setOf("paragraph", "heading", "blockquote", "listItem", "codeBlock")
}
