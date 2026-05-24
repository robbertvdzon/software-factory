package nl.vdzon.softwarefactory.orchestrator.services

data class AiRoute(
    val level: Int,
    val model: String?,
    val effort: String,
)

object AiRouting {
    fun resolve(level: Int?, supplier: String?): AiRoute {
        val normalizedLevel = (level ?: DEFAULT_LEVEL).coerceIn(0, 10)
        val effort = when (normalizedLevel) {
            in 0..2 -> "low"
            in 3..7 -> "medium"
            else -> "high"
        }
        return AiRoute(
            level = normalizedLevel,
            model = modelForSupplier(supplier),
            effort = effort,
        )
    }

    private fun modelForSupplier(supplier: String?): String? =
        when (supplier?.trim()?.lowercase()) {
            null,
            "",
            "mock",
            "dummy",
            "none",
            -> DUMMY_MODEL
            else -> null
        }

    private const val DEFAULT_LEVEL = 3
    private const val DUMMY_MODEL = "dummy-ai-client"
}
