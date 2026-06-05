package nl.vdzon.softwarefactory.dsl.puml

// ---------------------------------------------------------------------------
// Parser voor een subset van PlantUML-activity-diagrammen:
//
//   start
//   switch (vraag?) case (antwoord) <body> ... endswitch
//   if (conditie) then (antwoord) <body> else (antwoord) <body> endif
//   :actie;   (gevolgd door detach -> einde van een tak)
//
// De flow is een boom (geen loops/merges): elk pad eindigt in een actie.
// Daardoor is er per pad precies één uitkomst -> een waarheidstabel is compleet.
// ---------------------------------------------------------------------------

// --- AST: een beslissing (vraag met antwoord-takken) of een actie (blad) -----

sealed interface FlowNode

data class ActionNode(val text: String) : FlowNode

data class DecisionNode(val question: String, val branches: List<Branch>) : FlowNode

data class Branch(val answer: String, val target: FlowNode)

// --- Parser ----------------------------------------------------------------

object PlantUmlActivityParser {

    private val SWITCH = Regex("""^switch\s*\((.*)\)$""")
    private val CASE = Regex("""^case\s*\((.*)\)$""")
    private val IF = Regex("""^if\s*\((.*)\)\s*then\s*\((.*)\)$""")
    private val ELSE = Regex("""^else\s*\((.*)\)$""")
    private val ACTION = Regex("""^:(.*);$""")

    fun parse(plantUml: String): FlowNode {
        val tokens = plantUml.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { line ->
                line.startsWith("@startuml") ||
                    line.startsWith("@enduml") ||
                    line.startsWith("title") ||
                    line == "start" ||
                    line == "detach"
            }
            .toList()

        val cursor = Cursor(tokens)
        val root = cursor.parseNode()
        check(cursor.atEnd()) { "Onverwachte regel na het diagram: '${cursor.current()}'" }
        return root
    }

    private class Cursor(private val lines: List<String>) {
        private var index = 0

        fun atEnd(): Boolean = index >= lines.size
        fun current(): String = if (atEnd()) "<eof>" else lines[index]
        private fun advance(): String = lines[index++]

        fun parseNode(): FlowNode {
            val line = current()
            return when {
                SWITCH.matches(line) -> parseSwitch()
                IF.matches(line) -> parseIf()
                ACTION.matches(line) -> ActionNode(normalize(ACTION.find(line)!!.groupValues[1]))
                else -> error("Kan regel niet parsen: '$line'")
            }.also {
                if (it is ActionNode) advance()
            }
        }

        private fun parseSwitch(): FlowNode {
            val question = SWITCH.find(advance())!!.groupValues[1].trim()
            val branches = mutableListOf<Branch>()
            while (CASE.matches(current())) {
                val answer = CASE.find(advance())!!.groupValues[1].trim()
                branches += Branch(answer, parseNode())
            }
            check(current() == "endswitch") { "Verwachtte 'endswitch', kreeg '${current()}'" }
            advance()
            return DecisionNode(question, branches)
        }

        private fun parseIf(): FlowNode {
            val ifMatch = IF.find(advance())!!
            val condition = ifMatch.groupValues[1].trim()
            val thenAnswer = ifMatch.groupValues[2].trim()
            val thenBody = parseNode()

            check(ELSE.matches(current())) { "Verwachtte 'else (...)', kreeg '${current()}'" }
            val elseAnswer = ELSE.find(advance())!!.groupValues[1].trim()
            val elseBody = parseNode()

            check(current() == "endif") { "Verwachtte 'endif', kreeg '${current()}'" }
            advance()
            return DecisionNode(condition, listOf(Branch(thenAnswer, thenBody), Branch(elseAnswer, elseBody)))
        }

        private fun normalize(actionText: String): String =
            actionText.replace("\\n", " ").trim()
    }
}
