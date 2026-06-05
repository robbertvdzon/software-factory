package nl.vdzon.softwarefactory.dsl

// ---------------------------------------------------------------------------
// Activity diagram (PlantUML-stijl): gestructureerde control-flow.
//
// Anders dan de flowchart-graaf (losse nodes + edges) modelleer je hier de
// structuur zelf: een lijst statements, met geneste if/then/else. De DSL volgt
// die structuur letterlijk, net als gewone Kotlin-code.
//
//   activityDiagram {
//       start()
//       action(SUBMIT_ORDER)
//       decision(CHECK_WAREHOUSE_STOCK) {
//           then(IN_STOCK) {
//               action(PROCESS_PAYMENT)
//           }
//           otherwise(NO_STOCK) {
//               action(SHOW_ERROR)
//           }
//       }
//       stop()
//   }
// ---------------------------------------------------------------------------

// --- Vocabulaire: enums i.p.v. losse strings --------------------------------
// Elke constante draagt z'n PlantUML-label, zodat de rendering ongewijzigd blijft.

enum class Action(val label: String) {
    SUBMIT_ORDER("User Submits Order"),
    SHOW_SUCCESS("Show Success"),
    SHOW_ERROR("Show Error"),
}

enum class Condition(val label: String) {
    CHECK_WAREHOUSE_STOCK("Check Warehouse Stock?"),
    PROCESS_PAYMENT("Process Payment"),
    DISPATCH_ORDER("Dispatch Order"),
}

enum class Answer(val label: String) {
    IN_STOCK("In Stock"),
    NO_STOCK("No Stock"),
    PAYMENT_SUCCESS("Payment Success"),
    PAYMENT_FAILED("Payment Failed"),
    DISPATCH_SUCCESS("Dispatch Success"),
    DISPATCH_FAILED("Dispatch Failed"),
}

// --- AST -------------------------------------------------------------------

sealed interface Statement

data object Start : Statement

data object Stop : Statement

/** Een activiteit-blok ( :tekst; in PlantUML ). */
data class ActionStep(val action: Action) : Statement

/** Een keuze ( if (cond) then (..) .. else (..) .. endif ). */
data class Decision(
    val condition: Condition,
    val thenAnswer: Answer,
    val thenBranch: List<Statement>,
    val elseAnswer: Answer,
    val elseBranch: List<Statement>,
) : Statement

data class ActivityDiagram(val statements: List<Statement>)

// --- DSL -------------------------------------------------------------------

@DslMarker
annotation class ActivityDiagramDsl

@ActivityDiagramDsl
class ActivityBuilder {
    private val statements = mutableListOf<Statement>()

    fun start() {
        statements.add(Start)
    }

    fun stop() {
        statements.add(Stop)
    }

    fun action(action: Action) {
        statements.add(ActionStep(action))
    }

    fun decision(condition: Condition, block: DecisionBuilder.() -> Unit) {
        statements.add(DecisionBuilder(condition).apply(block).build())
    }

    fun build(): List<Statement> = statements.toList()
}

@ActivityDiagramDsl
class DecisionBuilder(private val condition: Condition) {
    private var thenAnswer: Answer? = null
    private var thenBranch: List<Statement> = emptyList()
    private var elseAnswer: Answer? = null
    private var elseBranch: List<Statement> = emptyList()

    /** De 'then'-tak ( then (answer) in PlantUML ). */
    fun then(answer: Answer, block: ActivityBuilder.() -> Unit) {
        thenAnswer = answer
        thenBranch = ActivityBuilder().apply(block).build()
    }

    /** De 'else'-tak ( else (answer) in PlantUML ). 'else' is een Kotlin-keyword, vandaar 'otherwise'. */
    fun otherwise(answer: Answer, block: ActivityBuilder.() -> Unit) {
        elseAnswer = answer
        elseBranch = ActivityBuilder().apply(block).build()
    }

    fun build(): Decision = Decision(
        condition = condition,
        thenAnswer = thenAnswer ?: error("decision '${condition.label}' mist een then(...)-tak"),
        thenBranch = thenBranch,
        elseAnswer = elseAnswer ?: error("decision '${condition.label}' mist een otherwise(...)-tak"),
        elseBranch = elseBranch,
    )
}

fun activityDiagram(block: ActivityBuilder.() -> Unit): ActivityDiagram =
    ActivityDiagram(ActivityBuilder().apply(block).build())

// --- Renderer: terug naar PlantUML -----------------------------------------

fun ActivityDiagram.toPlantUml(): String {
    val sb = StringBuilder()
    sb.appendLine("@startuml")
    sb.appendLine()
    statements.forEach { renderStatement(it, indent = 0, sb = sb) }
    sb.appendLine()
    sb.append("@enduml")
    return sb.toString()
}

private fun renderStatement(statement: Statement, indent: Int, sb: StringBuilder) {
    val pad = "    ".repeat(indent)
    when (statement) {
        Start -> sb.appendLine("${pad}start")
        Stop -> sb.appendLine("${pad}stop")
        is ActionStep -> sb.appendLine("$pad:${statement.action.label};")
        is Decision -> {
            sb.appendLine("${pad}if (${statement.condition.label}) then (${statement.thenAnswer.label})")
            statement.thenBranch.forEach { renderStatement(it, indent + 1, sb) }
            sb.appendLine("${pad}else (${statement.elseAnswer.label})")
            statement.elseBranch.forEach { renderStatement(it, indent + 1, sb) }
            sb.appendLine("${pad}endif")
        }
    }
}
