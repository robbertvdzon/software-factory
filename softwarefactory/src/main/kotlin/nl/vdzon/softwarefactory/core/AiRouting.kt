package nl.vdzon.softwarefactory.core

import nl.vdzon.softwarefactory.core.AgentRole

data class AiRoute(
    val level: Int,
    val model: String?,
    val effort: String,
)

object AiRouting {
    fun resolve(level: Int?, supplier: String?, role: AgentRole): AiRoute {
        val normalizedLevel = (level ?: DEFAULT_LEVEL).coerceIn(0, 10)
        val bucket = bucket(normalizedLevel, supplier, role)
        return AiRoute(
            level = normalizedLevel,
            model = bucket.model,
            effort = bucket.effort,
        )
    }

    private fun bucket(level: Int, supplier: String?, role: AgentRole): ModelBucket =
        when (supplier?.trim()?.lowercase()) {
            null,
            "",
            "mock",
            "dummy",
            "none",
            -> ModelBucket(DUMMY_MODEL, effortFor(level))
            "claude" -> claudeBucket(level, role)
            "copilot",
            "github",
            -> copilotBucket(level)
            else -> ModelBucket(null, effortFor(level))
        }

    // Default voor Claude: altijd Opus 4.8, ongeacht rol of level.
    // De effort schaalt nog wel mee met het AI-level (zie effortFor).
    private fun claudeBucket(level: Int, role: AgentRole): ModelBucket =
        ModelBucket(DEFAULT_CLAUDE_MODEL, effortFor(level))

    private fun copilotBucket(level: Int): ModelBucket =
        when (level) {
            0 -> ModelBucket("gpt-4.1", "low")
            in 1..3 -> ModelBucket("claude-haiku-4.5", effortFor(level))
            in 4..9 -> ModelBucket("claude-sonnet-4.5", effortFor(level))
            else -> ModelBucket("claude-opus-4.5", "high")
        }

    private fun effortFor(level: Int): String =
        when (level) {
            in 0..2 -> "low"
            in 3..7 -> "medium"
            else -> "high"
        }

    private data class ModelBucket(
        val model: String?,
        val effort: String,
    )

    private const val DEFAULT_LEVEL = 3
    private const val DUMMY_MODEL = "dummy-ai-client"
    private const val DEFAULT_CLAUDE_MODEL = "claude-opus-4-8"
}
