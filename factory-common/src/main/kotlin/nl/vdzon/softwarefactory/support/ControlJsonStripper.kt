package nl.vdzon.softwarefactory.support

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Een agent eindigt zijn output met 1-2 losse JSON-controleblokken (`agent_tips_update` en/of
 * `phase`/`score`/`proposedStory`) — die horen niet in tekst die een mens te lezen krijgt (audit-
 * rapporten, testrapporten, story-detail-samenvattingen). Knipt herhaald het laatste top-level
 * `{...}`-blok van de tekst af zolang het geldige JSON is met een van die herkenbare sleutels.
 */
object ControlJsonStripper {
    private val objectMapper = ObjectMapper()

    fun stripTrailingControlJson(text: String): String {
        val spans = topLevelJsonObjectSpans(text)
        var cut = text.length
        for ((start, end) in spans.asReversed()) {
            if (text.substring(end, cut).isNotBlank()) break // er staat nog iets anders ná dit blok
            val node = runCatching { objectMapper.readTree(text.substring(start, end)) }.getOrNull() ?: break
            if (!node.has("phase") && !node.has("agent_tips_update")) break
            cut = start
        }
        return text.substring(0, cut).trimEnd()
    }

    /**
     * Alle top-level `{...}`-blokken in [text], quote-bewust (rapporten citeren vaak brokjes code met
     * eigen `{`/`}`, die mogen de blok-herkenning niet verstoren) — als (startindex, eindindex-exclusief).
     */
    private fun topLevelJsonObjectSpans(text: String): List<Pair<Int, Int>> {
        val spans = mutableListOf<Pair<Int, Int>>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        text.forEachIndexed { index, char ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                return@forEachIndexed
            }
            when (char) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            spans += start to (index + 1)
                            start = -1
                        }
                    }
                }
            }
        }
        return spans
    }
}
