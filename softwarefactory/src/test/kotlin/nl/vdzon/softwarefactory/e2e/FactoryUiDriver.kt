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
     * Zet de EERSTE subtaak op `start` (alleen als die nog geen fase heeft) en de story op
     * `in-progress` — precies wat `StoryRefinementCoordinator.autoStartDevelopment` in productie doet.
     *
     * Bij `Auto-approve=on` doet de orchestrator dat zelf al zodra de story `planning-approved`
     * bereikt, vaak vóórdat deze aanroep draait. Deze driver racet daar dus per definitie mee, en die
     * race moet ONSCHULDIG zijn. Daarom:
     * - altijd de eerste subtaak als doelwit, nooit "de eerste non-gestarte": als de orchestrator de
     *   race wint wijst "eerste non-gestarte" naar de TWEEDE subtaak, die dan parallel aan de eerste
     *   gaat lopen — twee agents tegelijk, en dus een dispatch-volgorde als
     *   `[PLANNER, REVIEWER, DEVELOPER, …]` (flaky order-asserts, o.a. FullRefineToDevelopE2eTest);
     * - de fase-schrijf is één atomaire conditionele UPDATE
     *   ([TrackerTestState.startFirstSubtaskIfUnstarted]) in plaats van lezen-dan-schrijven, zodat er
     *   geen venster tussen check en write bestaat waarin de orchestrator er tussendoor kan glippen.
     *
     * Wint de orchestrator, dan is dit een no-op op subtaakniveau; winnen wij, dan slaat de
     * orchestrator zijn auto-start over (`development-already-started`). Beide paden geven dezelfde
     * sequentiële subtaak-keten.
     */
    fun startDeveloping(storyKey: String) {
        state.startFirstSubtaskIfUnstarted(storyKey)
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
