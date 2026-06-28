package nl.vdzon.softwarefactory.core

/** Een story die af is (alle subtaken terminaal) en een open, nog niet gemergede PR heeft. */
data class MergeReadyInfo(val storyKey: String, val prNumber: Int?, val prUrl: String?)

/**
 * De dashboard-/orchestratie-operaties die lagere modules (zoals `telegram`) nodig hebben, als poort in
 * `core`. Zo hangt `telegram` van deze interface af i.p.v. direct van `web` (`FactoryDashboardService`),
 * wat de module-cyclus telegram↔web (en orchestrator→telegram→web→orchestrator) doorbreekt.
 * `web.FactoryDashboardService` is de implementatie.
 */
interface FactoryOperations {
    /** De openstaande agent-vraag voor [issue] (zelfde tekst als het dashboard), of null. */
    fun questionFor(issue: TrackerIssue): String?

    /** Geldt auto-approve voor [issue] (eigen vlag of die van de parent-story; silent telt mee)? */
    fun autoApproveActive(issue: TrackerIssue): Boolean

    /** Is [storyKey] klaar om te mergen (alle subtaken terminaal + open, niet-gemergede PR)? */
    fun mergeReady(storyKey: String): MergeReadyInfo?

    /** Idem, startend vanaf een zojuist afgeronde subtaak (zoekt eerst de parent-story op). */
    fun mergeReadyForSubtask(subtask: TrackerIssue): MergeReadyInfo?

    /** Het laatste tester-rapport van [storyKey], of null. */
    fun testerReportFor(storyKey: String): String?

    /** De preview-/test-URL van [storyKey], of null. */
    fun previewUrlFor(storyKey: String): String?

    /** Zet de story-fase (post een comment + schuif de fase), zoals het dashboard. */
    fun setStoryPhase(storyKey: String, phase: String, comment: String?)

    /** Zet de subtaak-fase (post een comment + schuif de fase), zoals het dashboard. */
    fun setSubtaskPhase(subtaskKey: String, phase: String, comment: String?)

    /** Zet een commando (approve/reject/merge/…) in de wachtrij voor de orchestrator. */
    fun queueCommand(storyKey: String, command: FactoryCommand, reason: String? = null)
}
