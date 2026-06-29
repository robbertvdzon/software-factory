package nl.vdzon.softwarefactory.core

import nl.vdzon.softwarefactory.core.AgentRole

data class AiRoute(
    val level: Int,
    val model: String?,
    val effort: String,
)

object AiRouting {
    fun resolve(level: Int?, supplier: String?, role: AgentRole): AiRoute {
        val normalizedLevel = (level ?: DEFAULT_LEVEL).coerceIn(MIN_LEVEL, MAX_LEVEL)
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
            MIN_LEVEL -> ModelBucket("gpt-4.1", "low")
            in 1..COPILOT_HAIKU_MAX_LEVEL -> ModelBucket("claude-haiku-4.5", effortFor(level))
            in COPILOT_SONNET_MIN_LEVEL..COPILOT_SONNET_MAX_LEVEL ->
                ModelBucket("claude-sonnet-4.5", effortFor(level))
            else -> ModelBucket("claude-opus-4.5", "high")
        }

    private fun effortFor(level: Int): String =
        when (level) {
            in MIN_LEVEL..EFFORT_LOW_MAX_LEVEL -> "low"
            in EFFORT_MEDIUM_MIN_LEVEL..EFFORT_MEDIUM_MAX_LEVEL -> "medium"
            else -> "high"
        }

    private data class ModelBucket(
        val model: String?,
        val effort: String,
    )

    private const val DEFAULT_LEVEL = 3
    private const val MIN_LEVEL = 0
    private const val MAX_LEVEL = 10
    private const val COPILOT_HAIKU_MAX_LEVEL = 3
    private const val COPILOT_SONNET_MIN_LEVEL = 4
    private const val COPILOT_SONNET_MAX_LEVEL = 9
    private const val EFFORT_LOW_MAX_LEVEL = 2
    private const val EFFORT_MEDIUM_MIN_LEVEL = 3
    private const val EFFORT_MEDIUM_MAX_LEVEL = 7
    private const val DUMMY_MODEL = "dummy-ai-client"
    private const val DEFAULT_CLAUDE_MODEL = "claude-opus-4-8"
}
