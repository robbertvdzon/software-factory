package nl.vdzon.softwarefactory.nightly.models

import nl.vdzon.softwarefactory.nightly.types.NightlyOutcomeStatus
import java.time.OffsetDateTime

data class NightlyStoryOutcome(
    val status: NightlyOutcomeStatus,
    val startedAt: OffsetDateTime?,
    val endedAt: OffsetDateTime?,
    val costUsd: Double,
    val error: String? = null,
)
