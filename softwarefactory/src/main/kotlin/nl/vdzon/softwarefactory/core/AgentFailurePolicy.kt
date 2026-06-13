package nl.vdzon.softwarefactory.core

/**
 * Public failure policy shared by the orchestrator and runtime modules.
 *
 * Retryable failures are infrastructure or transport failures where a later
 * attempt can reasonably succeed without human changes to the story.
 */
object AgentFailurePolicy {
    private val retryableFailureTokens = listOf(
        "http 429",
        "api error 500",
        "rate limit",
        "timeout",
        "without writing /work/agent-result.json",
        "container stopped without writing",
    )

    fun isRetryable(outcome: String?, summaryText: String?): Boolean {
        val text = listOfNotNull(outcome, summaryText).joinToString(" ").lowercase()
        return retryableFailureTokens.any { it in text }
    }
}
