package nl.vdzon.softwarefactory.core.contracts

import nl.vdzon.softwarefactory.core.*
import nl.vdzon.softwarefactory.core.contracts.*

import java.time.OffsetDateTime

interface CreditsPauseCoordinator {
    fun activePause(now: OffsetDateTime): CreditsPause?

    fun handleCreditsExhausted(storyKey: String, summaryText: String?)
}

data class CreditsPause(
    val until: OffsetDateTime,
    val reason: String?,
)
