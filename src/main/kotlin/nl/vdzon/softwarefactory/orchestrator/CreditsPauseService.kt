package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.tracker.AgentRole
import nl.vdzon.softwarefactory.tracker.IssueTrackerClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime

interface CreditsPauseCoordinator {
    fun activePause(now: OffsetDateTime): CreditsPause?

    fun handleCreditsExhausted(storyKey: String, summaryText: String?)
}

data class CreditsPause(
    val until: OffsetDateTime,
    val reason: String?,
)

@Service
class CreditsPauseService(
    private val systemStateRepository: SystemStateRepository,
    private val issueTrackerClient: IssueTrackerClient,
    private val settings: OrchestratorSettings,
    private val clock: Clock,
) : CreditsPauseCoordinator {
    override fun activePause(now: OffsetDateTime): CreditsPause? {
        val state = systemStateRepository.current()
        val until = state.creditsPausedUntil ?: return null
        if (!now.isBefore(until)) {
            return null
        }
        return CreditsPause(until = until, reason = state.creditsPausedReason)
    }

    override fun handleCreditsExhausted(storyKey: String, summaryText: String?) {
        val until = OffsetDateTime.now(clock).plus(settings.creditsPauseDefault)
        val reason = listOfNotNull(
            "credits-exhausted from $storyKey",
            summaryText?.takeIf { it.isNotBlank() }?.take(240),
        ).joinToString(": ")
        systemStateRepository.pauseCredits(until, reason)
        issueTrackerClient.postAgentComment(
            storyKey,
            AgentRole.ORCHESTRATOR,
            "Tijdelijk gepauzeerd: AI-credits uitgeput, retry rond $until.",
        )
    }
}
