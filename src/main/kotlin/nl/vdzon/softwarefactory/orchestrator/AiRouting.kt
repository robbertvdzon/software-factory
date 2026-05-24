package nl.vdzon.softwarefactory.orchestrator

data class AiRoute(
    val level: Int,
    val model: String,
    val effort: String,
)

object AiRouting {
    fun resolve(level: Int?): AiRoute {
        val normalizedLevel = (level ?: DEFAULT_LEVEL).coerceIn(0, 10)
        val effort = when (normalizedLevel) {
            in 0..2 -> "low"
            in 3..7 -> "medium"
            else -> "high"
        }
        return AiRoute(
            level = normalizedLevel,
            model = DUMMY_MODEL,
            effort = effort,
        )
    }

    private const val DEFAULT_LEVEL = 3
    private const val DUMMY_MODEL = "dummy-ai-client"
}
