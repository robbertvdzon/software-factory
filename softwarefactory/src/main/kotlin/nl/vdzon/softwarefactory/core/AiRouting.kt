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

    private fun claudeBucket(level: Int, role: AgentRole): ModelBucket {
        val tier = when (level) {
            0 -> mapOf(
                AgentRole.REFINER to "cheap",
                AgentRole.DEVELOPER to "cheap",
                AgentRole.REVIEWER to "cheap",
                AgentRole.TESTER to "cheap",
            )
            1 -> mapOf(
                AgentRole.REFINER to "cheap",
                AgentRole.DEVELOPER to "cheap+",
                AgentRole.REVIEWER to "cheap",
                AgentRole.TESTER to "cheap",
            )
            2 -> mapOf(
                AgentRole.REFINER to "cheap",
                AgentRole.DEVELOPER to "mid",
                AgentRole.REVIEWER to "cheap",
                AgentRole.TESTER to "cheap",
            )
            3 -> mapOf(
                AgentRole.REFINER to "cheap",
                AgentRole.DEVELOPER to "mid",
                AgentRole.REVIEWER to "cheap+",
                AgentRole.TESTER to "cheap+",
            )
            4 -> mapOf(
                AgentRole.REFINER to "cheap+",
                AgentRole.DEVELOPER to "mid+",
                AgentRole.REVIEWER to "mid",
                AgentRole.TESTER to "mid",
            )
            5 -> mapOf(
                AgentRole.REFINER to "cheap+",
                AgentRole.DEVELOPER to "mid+",
                AgentRole.REVIEWER to "mid+",
                AgentRole.TESTER to "mid+",
            )
            6 -> mapOf(
                AgentRole.REFINER to "mid",
                AgentRole.DEVELOPER to "mid++",
                AgentRole.REVIEWER to "mid+",
                AgentRole.TESTER to "mid+",
            )
            7 -> mapOf(
                AgentRole.REFINER to "mid",
                AgentRole.DEVELOPER to "premium",
                AgentRole.REVIEWER to "mid+",
                AgentRole.TESTER to "mid+",
            )
            8 -> mapOf(
                AgentRole.REFINER to "mid",
                AgentRole.DEVELOPER to "premium",
                AgentRole.REVIEWER to "mid++",
                AgentRole.TESTER to "mid+",
            )
            9 -> mapOf(
                AgentRole.REFINER to "mid+",
                AgentRole.DEVELOPER to "premium+",
                AgentRole.REVIEWER to "premium",
                AgentRole.TESTER to "mid++",
            )
            else -> mapOf(
                AgentRole.REFINER to "mid+",
                AgentRole.DEVELOPER to "premium+",
                AgentRole.REVIEWER to "premium+",
                AgentRole.TESTER to "premium",
            )
        }.getOrDefault(role, "mid+")
        return requireNotNull(CLAUDE_TIERS[tier]) { "Unknown Claude AI tier '$tier'." }
    }

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

    private val CLAUDE_TIERS = mapOf(
        "cheap" to ModelBucket("claude-haiku-4-5", "low"),
        "cheap+" to ModelBucket("claude-haiku-4-5", "medium"),
        "mid" to ModelBucket("claude-sonnet-4-6", "low"),
        "mid+" to ModelBucket("claude-sonnet-4-6", "medium"),
        "mid++" to ModelBucket("claude-sonnet-4-6", "high"),
        "premium" to ModelBucket("claude-opus-4-7", "medium"),
        "premium+" to ModelBucket("claude-opus-4-7", "high"),
    )
}
