package nl.vdzon.softwarefactory.dsl

// ---------------------------------------------------------------------------
// Data model: een flowchart is een set nodes met (optioneel gelabelde) edges.
// ---------------------------------------------------------------------------

data class Node(
    val id: String,
    val label: String,
)

data class Edge(
    val from: Node,
    val to: Node,
    val label: String? = null,
)

data class Flowchart(
    val nodes: List<Node>,
    val edges: List<Edge>,
)

// ---------------------------------------------------------------------------
// DSL: type-safe builder om een Flowchart op te bouwen.
//
// Genest / declaratief, zoals build.gradle.kts: per node een blok, verwijzen
// naar andere nodes via hun naam.
//
//   flowchart {
//       node("Start") {
//           to("User Submits Order")
//       }
//       node("Check Warehouse Stock") {
//           to("Show Error")       labeled "No Stock"
//           to("Process Payment")  labeled "In Stock"
//       }
//       node("Finished")
//   }
// ---------------------------------------------------------------------------

@DslMarker
annotation class FlowchartDsl

@FlowchartDsl
class FlowchartBuilder {
    private val nodes = mutableListOf<Node>()
    private val pendingEdges = mutableListOf<PendingEdge>()

    /** Declareert een node (id afgeleid van het label, uniek gemaakt) met z'n uitgaande pijlen. */
    fun node(label: String, block: NodeBuilder.() -> Unit = {}): Node {
        val node = createNode(label)
        NodeBuilder(node).apply(block).specs.forEach { spec ->
            pendingEdges.add(PendingEdge(node, spec.target, spec.label))
        }
        return node
    }

    private fun createNode(label: String): Node {
        val baseId = label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        var id = baseId
        var counter = 2
        while (nodes.any { it.id == id }) {
            id = "${baseId}_${counter++}"
        }
        return Node(id, label).also { nodes.add(it) }
    }

    fun build(): Flowchart {
        val byLabel = nodes.associateBy { it.label }
        val edges = pendingEdges.map { pending ->
            val target = byLabel[pending.targetLabel]
                ?: error("Onbekende node '${pending.targetLabel}' (verwezen vanuit '${pending.from.label}')")
            Edge(pending.from, target, pending.label)
        }
        return Flowchart(nodes.toList(), edges)
    }
}

/** Builder voor het blok binnen een node { } : verzamelt uitgaande pijlen. */
@FlowchartDsl
class NodeBuilder(val node: Node) {
    val specs = mutableListOf<EdgeSpec>()

    /** Verbindt deze node met een doelnode (op naam). Optioneel `labeled "..."` erachter. */
    fun to(target: String): EdgeSpec = EdgeSpec(target).also { specs.add(it) }
}

/** Een nog-niet-gelabelde pijl naar een doelnode; label toe te voegen met `labeled`. */
class EdgeSpec(val target: String, var label: String? = null) {
    infix fun labeled(label: String): EdgeSpec {
        this.label = label
        return this
    }
}

private data class PendingEdge(val from: Node, val targetLabel: String, val label: String?)

fun flowchart(block: FlowchartBuilder.() -> Unit): Flowchart =
    FlowchartBuilder().apply(block).build()
