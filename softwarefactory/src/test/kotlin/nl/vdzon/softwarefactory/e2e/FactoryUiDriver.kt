package nl.vdzon.softwarefactory.e2e

/**
 * Simuleert "de gebruiker" in de end-to-end integratietests door YouTrack-veldomzettingen
 * direct in de [FakeYouTrackState] te schrijven.
 *
 * Vóór SF-825 werkte deze driver via HTTP-calls naar de Kotlin-dashboardcontroller
 * (FactoryDashboardController). Dat controller is verwijderd; nu zet de driver de
 * YouTrack custom-fields rechtstreeks zodat de orchestrator ze ophaalt via zijn poll-cyclus.
 */
class FactoryUiDriver(private val state: FakeYouTrackState) {

    /** No-op: er is geen dashboard-auth meer. */
    fun login(): FactoryUiDriver = this

    /** Zet de eerste non-gestarte subtaak op `start` en de story op `in-progress`. */
    fun startDeveloping(storyKey: String) {
        val firstUnstarted = state.childrenOf(storyKey).firstOrNull { child ->
            (child.customFields["Subtask Phase"] as? com.fasterxml.jackson.databind.node.ObjectNode)
                ?.get("name")?.asText()
                .isNullOrBlank()
        }
        firstUnstarted?.let { state.setEnumField(it.key, "Subtask Phase", "start") }
        state.setEnumField(storyKey, "Story Phase", "in-progress")
    }

    /** Zet de `Story Phase` van een story + voeg optioneel een antwoord-comment toe. */
    fun answerStory(storyKey: String, answer: String, phase: String = "questions-answered") {
        if (answer.isNotBlank()) state.addComment(storyKey, answer)
        state.setEnumField(storyKey, "Story Phase", phase)
    }

    /** Zet de `Subtask Phase` van een subtaak + voeg optioneel een antwoord-comment toe. */
    fun answerSubtask(subtaskKey: String, answer: String, phase: String = "development-questions-answered") {
        if (answer.isNotBlank()) state.addComment(subtaskKey, answer)
        state.setEnumField(subtaskKey, "Subtask Phase", phase)
    }

    /** Zet de `Story Phase` zonder comment. */
    fun setStoryPhase(storyKey: String, phase: String) =
        state.setEnumField(storyKey, "Story Phase", phase)

    /** Zet de `Subtask Phase` zonder comment. */
    fun setSubtaskPhase(subtaskKey: String, phase: String) =
        state.setEnumField(subtaskKey, "Subtask Phase", phase)
}
