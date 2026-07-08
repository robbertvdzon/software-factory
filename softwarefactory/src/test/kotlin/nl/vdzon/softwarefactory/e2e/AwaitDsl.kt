package nl.vdzon.softwarefactory.e2e

import org.awaitility.Awaitility.await
import org.awaitility.core.ConditionTimeoutException
import java.time.Duration

/**
 * De async-kern van de end-to-end integratietest (bouwstap 4 uit het e2e-plan).
 *
 * Awaitility-helpers die de verwachte eindtoestand pollen via [TrackerTestState] — precies wat de echte
 * `PostgresTrackerClient` ernaartoe schrijft. De pollers in de app draaien op lage intervallen
 * (`SF_POLL_INTERVAL_MS=100`, `softwarefactory.agent-result-poll-ms=100`), dus ruime timeouts met korte
 * poll-intervallen geven snel groen zonder races.
 */
class AwaitDsl(
    private val state: TrackerTestState,
    private val timeout: Duration = Duration.ofSeconds(10),
    private val pollInterval: Duration = Duration.ofMillis(100),
) {
    /**
     * Wacht tot de Story Phase van [key] [expected] is — of sinds de vorige await opnieuw werd.
     * Bij auto-approve schuift een fase soms binnen één poll-venster door naar de opvolger
     * (bv. `planning-approved` → `in-progress`); de write-historie ([TrackerTestState.fieldValueCount])
     * vangt dat moment altijd. De await is verbruik-gebaseerd: een tweede await op dezelfde waarde
     * (reject-loops wachten meermaals op bv. `tested`) eist een níeuwe schrijf, anders zou hij direct
     * slagen op de vorige ronde.
     */
    fun awaitStoryPhase(key: String, expected: String) =
        awaitPhaseReached(key, STORY_PHASE_FIELD, expected, "story-phase van $key")

    /** Wacht tot de Subtask Phase van [key] [expected] is/werd (zie [awaitStoryPhase]). */
    fun awaitSubtaskPhase(key: String, expected: String) =
        awaitPhaseReached(key, SUBTASK_PHASE_FIELD, expected, "subtask-phase van $key")

    /** Per (issue, veld, waarde): hoeveel historie-schrijvingen eerdere awaits al "verbruikten". */
    private val consumedWrites = HashMap<Triple<String, String, String>, Int>()

    private fun awaitPhaseReached(key: String, field: String, expected: String, description: String) {
        val historyKey = Triple(key, field, expected)
        val alreadyConsumed = consumedWrites.getOrDefault(historyKey, 0)
        awaitCondition("$description == $expected") {
            state.fieldValueCount(key, field, expected) > alreadyConsumed
        }
        consumedWrites[historyKey] = state.fieldValueCount(key, field, expected)
    }

    /** Wacht tot de board-lane (`status`, bv. "Done") van [key] gelijk is aan [expected]. */
    fun awaitIssueState(key: String, expected: String) =
        awaitCondition("board-lane van $key == $expected") { state.issue(key)?.status == expected }

    /** Wacht tot het `Error`-veld van subtaak/story [key] de tekst [contains] bevat. */
    fun awaitErrorContains(key: String, contains: String) {
        awaitCondition("error van $key bevat '$contains'") {
            state.issue(key)?.fields?.error?.contains(contains) == true
        }
    }

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
            children.isNotEmpty() && children.all { isApproved(it.fields.subtaskPhase) }
        }
    }

    /**
     * Wacht tot alle **AI**-subtaken onder [parentKey] approved zijn. De factory-afgedwongen, niet-AI
     * afsluiters (merge/deploy/manual-approve) worden overgeslagen: die kunnen in de e2e-harness niet
     * afronden (geen GitHub-PR/merge), en horen niet bij wat deze test bewijst.
     */
    fun awaitAllAiSubtasksApproved(parentKey: String) {
        awaitCondition("alle AI-subtaken van $parentKey approved") {
            val ai = state.childrenOf(parentKey).filter { it.fields.subtaskType !in NON_AI_SUBTASK_TYPES }
            ai.isNotEmpty() && ai.all { isApproved(it.fields.subtaskPhase) }
        }
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

    private fun isApproved(phase: String?): Boolean =
        phase != null && (phase.endsWith("-approved") || phase == "summarized")

    companion object {
        private const val STORY_PHASE_FIELD = "Story Phase"
        private const val SUBTASK_PHASE_FIELD = "Subtask Phase"

        /** Niet-AI afsluit-subtaken die in de e2e-harness niet afronden (geen GitHub-PR/merge). */
        private val NON_AI_SUBTASK_TYPES = setOf("merge", "deploy", "manual-approve")
    }
}
