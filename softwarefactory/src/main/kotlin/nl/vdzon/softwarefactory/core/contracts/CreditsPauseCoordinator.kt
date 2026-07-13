package nl.vdzon.softwarefactory.core.contracts

import nl.vdzon.softwarefactory.core.AgentComments
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.DeploymentConfig
import nl.vdzon.softwarefactory.core.TrackerField
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
