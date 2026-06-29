package nl.vdzon.softwarefactory.support

/**
 * Lichte per-thread verzamelaar van externe calls (YouTrack HTTP, Docker/Git/gh CLI) zodat er per
 * orchestrator-poll een rapport gelogd kan worden: hoeveel calls en hoe lang die duurden.
 *
 * Gebruik: roep [begin] aan bij het begin van een poll, wikkel elke externe call met [measure], en
 * lees aan het eind [end] uit voor een [report]. Buiten een actieve sessie is [measure] een no-op
 * qua registratie (de call zelf draait gewoon).
 */
object CallMetrics {
    private const val NANOS_PER_MILLI = 1_000_000
    private const val SLOWEST_CALLS_IN_REPORT = 3

    data class Call(val category: String, val label: String, val durationMs: Long)

    private val active = ThreadLocal<MutableList<Call>?>()

    /** Start het verzamelen op de huidige thread. */
    fun begin() {
        active.set(mutableListOf())
    }

    /** Meet één externe call en registreer 'm als er een sessie actief is. */
    fun <T> measure(category: String, label: String, block: () -> T): T {
        val sink = active.get() ?: return block()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            sink.add(Call(category, label, (System.nanoTime() - start) / NANOS_PER_MILLI))
        }
    }

    /** Stop het verzamelen en geef de verzamelde calls terug; wist de sessie. */
    fun end(): List<Call> {
        val calls = active.get().orEmpty()
        active.remove()
        return calls
    }

    /** Bouwt een leesbaar rapport: totaal, per categorie (aantal/ms) en de traagste calls. */
    fun report(calls: List<Call>): String {
        if (calls.isEmpty()) {
            return "0 calls"
        }
        val total = calls.sumOf { it.durationMs }
        val perCategory = calls.groupBy { it.category }
            .entries
            .sortedByDescending { (_, list) -> list.sumOf { it.durationMs } }
            .joinToString(", ") { (category, list) -> "$category ${list.size}/${list.sumOf { it.durationMs }}ms" }
        val slowest = calls.sortedByDescending { it.durationMs }
            .take(SLOWEST_CALLS_IN_REPORT)
            .joinToString("; ") { "${it.label} ${it.durationMs}ms" }
        return "${calls.size} calls / ${total}ms [$perCategory] traagste: $slowest"
    }
}
