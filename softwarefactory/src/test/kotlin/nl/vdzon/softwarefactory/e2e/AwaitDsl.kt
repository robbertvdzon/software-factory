package nl.vdzon.softwarefactory.e2e

import org.awaitility.Awaitility.await
import org.awaitility.core.ConditionTimeoutException
import java.time.Duration

/**
 * De async-kern van de end-to-end integratietest (bouwstap 4 uit het e2e-plan).
 *
 * Awaitility-helpers die de verwachte eindtoestand pollen via de **state** van de
 * embedded [FakeYouTrackServer] — precies wat de echte `YouTrackClient` ernaartoe
 * schrijft. De pollers in de app draaien op lage intervallen (`SF_POLL_INTERVAL_MS=100`,
 * `softwarefactory.agent-result-poll-ms=100`), dus ruime timeouts met korte poll-intervallen
 * geven snel groen zonder races.
 */
class AwaitDsl(
    private val youtrack: FakeYouTrackServer,
    private val timeout: Duration = Duration.ofSeconds(10),
    private val pollInterval: Duration = Duration.ofMillis(100),
) {
    private val state get() = youtrack.state

    /** Wacht tot de Story Phase van [key] gelijk is aan [expected]. */
    fun awaitStoryPhase(key: String, expected: String) =
        awaitField(key, STORY_PHASE_FIELD, expected, "story-phase van $key")

    /** Wacht tot de Subtask Phase van [key] gelijk is aan [expected]. */
    fun awaitSubtaskPhase(key: String, expected: String) =
        awaitField(key, SUBTASK_PHASE_FIELD, expected, "subtask-phase van $key")

    /** Wacht tot er minstens [count] subtaken (children) onder [parentKey] hangen. */
    fun awaitSubtasksCreated(parentKey: String, count: Int) {
        awaitCondition("$count subtaken onder $parentKey") {
            state.childrenOf(parentKey).size >= count
        }
    }

    /** Wacht tot álle subtaken onder [parentKey] een `*-approved` of `summarized` phase hebben. */
    fun awaitAllSubtasksApproved(parentKey: String) {
        awaitCondition("alle subtaken van $parentKey approved") {
            val children = state.childrenOf(parentKey)
            children.isNotEmpty() && children.all { isApproved(phaseOf(it.key, SUBTASK_PHASE_FIELD)) }
        }
    }

    private fun awaitField(key: String, field: String, expected: String, description: String) {
        awaitCondition("$description == $expected") { phaseOf(key, field) == expected }
    }

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        try {
            await(description)
                .atMost(timeout)
                .pollInterval(pollInterval)
                .until(condition)
        } catch (e: ConditionTimeoutException) {
            throw AssertionError("Timeout wachtend op: $description", e)
        }
    }

    private fun phaseOf(key: String, field: String): String? {
        val value = state.issue(key)?.customFields?.get(field) ?: return null
        // Enum-velden komen als {"name": "..."} binnen; tekst-velden als plain string.
        return value.path("name").asText(null) ?: value.takeIf { it.isTextual }?.asText()
    }

    private fun isApproved(phase: String?): Boolean =
        phase != null && (phase.endsWith("-approved") || phase == "summarized")

    companion object {
        private const val STORY_PHASE_FIELD = "Story Phase"
        private const val SUBTASK_PHASE_FIELD = "Subtask Phase"
    }
}
