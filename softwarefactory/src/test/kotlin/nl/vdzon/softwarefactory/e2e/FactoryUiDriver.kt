package nl.vdzon.softwarefactory.e2e

/**
 * Simuleert "de gebruiker" in de end-to-end integratietests door tracker-veldwijzigingen direct in
 * [TrackerTestState] te schrijven.
 *
 * Vóór SF-825 werkte deze driver via HTTP-calls naar de Kotlin-dashboardcontroller
 * (FactoryDashboardController). Dat controller is verwijderd; nu zet de driver de tracker-velden
 * rechtstreeks zodat de orchestrator ze ophaalt via zijn poll-cyclus.
 */
class FactoryUiDriver(private val state: TrackerTestState) {

    /** No-op: er is geen dashboard-auth meer. */
    fun login(): FactoryUiDriver = this

    /**
     * Zet de eerste non-gestarte subtaak op `start` en de story op `in-progress`.
     *
     * No-op als er al een subtaak een fase heeft: bij `Auto-approve=on` start de orchestrator zelf al
     * automatisch (`StoryRefinementCoordinator.autoStartDevelopment`, ook idempotent op dezelfde
     * voorwaarde) zodra de story `planning-approved` bereikt — vaak vóórdat deze aanroep ook maar
     * draait. Zonder deze guard zou "eerste non-gestarte subtaak" dan de VOLGENDE subtaak vinden (de
     * eerste is intussen al bezig) en die ten onrechte een tweede keer starten, wat de subtaak-keten
     * uit volgorde trekt (flaky dispatch-order-asserts, zie git-geschiedenis).
     */
    fun startDeveloping(storyKey: String) {
        if (state.childrenOf(storyKey).any { !it.fields.subtaskPhase.isNullOrBlank() }) return
        val firstUnstarted = state.childrenOf(storyKey).firstOrNull { it.fields.subtaskPhase.isNullOrBlank() }
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
