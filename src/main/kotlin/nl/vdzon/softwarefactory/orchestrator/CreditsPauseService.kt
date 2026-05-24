package nl.vdzon.softwarefactory.orchestrator

import java.time.OffsetDateTime

interface CreditsPauseCoordinator {
    fun activePause(now: OffsetDateTime): CreditsPause?

    fun handleCreditsExhausted(storyKey: String, summaryText: String?)
}

data class CreditsPause(
    val until: OffsetDateTime,
    val reason: String?,
)
