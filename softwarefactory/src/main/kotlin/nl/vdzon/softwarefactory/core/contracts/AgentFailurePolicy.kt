package nl.vdzon.softwarefactory.core.contracts

enum class AgentFailureClassification { QUOTA, RETRYABLE, FATAL }

/**
 * Public failure policy shared by the orchestrator and runtime modules.
 *
 * Retryable failures are infrastructure or transport failures where a later
 * attempt can reasonably succeed without human changes to the story.
 */
object AgentFailurePolicy {
    private val quotaFailureTokens = listOf("usage limit reached", "quota", "credit balance")
    private val retryableFailureTokens = listOf(
        "http 429",
        "api error 500",
        "rate limit",
        "timeout",
        "without writing /work/agent-result.json",
        "container stopped without writing",
    )

    fun classify(
        outcome: String?,
        summaryText: String?,
        rateLimitStatus: String? = null,
        failed: Boolean = true,
    ): AgentFailureClassification {
        if (!failed) return AgentFailureClassification.FATAL
        val text = listOfNotNull(outcome, summaryText).joinToString(" ").lowercase()
        val blockingSignal = rateLimitStatus
            ?.let { !it.equals("allowed", true) && !it.equals("allowed_warning", true) }
            ?: false
        return when {
            blockingSignal || quotaFailureTokens.any { it in text } -> AgentFailureClassification.QUOTA
            retryableFailureTokens.any { it in text } -> AgentFailureClassification.RETRYABLE
            else -> AgentFailureClassification.FATAL
        }
    }

    fun isQuota(
        outcome: String?,
        summaryText: String?,
        rateLimitStatus: String? = null,
        failed: Boolean = true,
    ): Boolean = classify(outcome, summaryText, rateLimitStatus, failed) == AgentFailureClassification.QUOTA

    fun isRetryable(outcome: String?, summaryText: String?): Boolean =
        classify(outcome, summaryText) == AgentFailureClassification.RETRYABLE
}
