package nl.vdzon.softwarefactory.dsl.puml

// ---------------------------------------------------------------------------
// Waarheidstabel: elk pad door de flow is één rij. Per (bereikte) vraag staat
// het gekozen antwoord; vragen die op dat pad niet bereikt worden zijn '-'
// (don't care). De laatste kolom is de actie waarin het pad eindigt.
// ---------------------------------------------------------------------------

data class TruthRow(val answers: Map<String, String>, val result: String)

data class TruthTable(val questions: List<String>, val rows: List<TruthRow>) {

    fun toMarkdown(): String {
        val columns = questions + "→ Resultaat"
        val sb = StringBuilder()
        sb.appendLine("# Waarheidstabel — gegenereerd uit flow.puml")
        sb.appendLine()
        sb.appendLine("${rows.size} paden, ${questions.size} beslissingen. ('-' = niet bereikt op dat pad)")
        sb.appendLine()
        sb.appendLine("| " + columns.joinToString(" | ") { escape(it) } + " |")
        sb.appendLine("|" + columns.joinToString("") { " --- |" })
        rows.forEach { row ->
            val cells = questions.map { row.answers[it] ?: "-" } + row.result
            sb.appendLine("| " + cells.joinToString(" | ") { escape(it) } + " |")
        }
        return sb.toString()
    }

    private fun escape(cell: String): String = cell.replace("|", "\\|")
}

/** Bouwt de waarheidstabel: alle wortel-naar-blad paden door de flow. */
fun FlowNode.toTruthTable(): TruthTable {
    val questions = LinkedHashSet<String>()
    collectQuestions(this, questions)

    val rows = mutableListOf<TruthRow>()
    enumerate(this, linkedMapOf(), rows)

    return TruthTable(questions.toList(), rows)
}

private fun collectQuestions(node: FlowNode, into: LinkedHashSet<String>) {
    when (node) {
        is ActionNode -> Unit
        is DecisionNode -> {
            into.add(node.question)
            node.branches.forEach { collectQuestions(it.target, into) }
        }
    }
}

private fun enumerate(node: FlowNode, taken: Map<String, String>, rows: MutableList<TruthRow>) {
    when (node) {
        is ActionNode -> rows.add(TruthRow(taken.toMap(), node.text))
        is DecisionNode -> node.branches.forEach { branch ->
            enumerate(branch.target, taken + (node.question to branch.answer), rows)
        }
    }
}
