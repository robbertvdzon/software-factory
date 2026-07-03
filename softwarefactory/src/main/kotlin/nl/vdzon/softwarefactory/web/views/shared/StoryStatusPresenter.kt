package nl.vdzon.softwarefactory.web.views.shared

import nl.vdzon.softwarefactory.core.StoryPhase
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.web.views.FactoryDashboardViews.StatusBucket

/**
 * Eén plek voor de status-classificatie van stories: de bucket-indeling van de YouTrack
 * `State`-lane en de afgeleide "echte" story-status. Voorheen dupliceerden
 * FactoryDashboardViews en FactoryDashboardService deze logica; beide delegeren nu hierheen.
 *
 * Het [StatusBucket]-enum zelf blijft genest in FactoryDashboardViews: afnemers (waaronder
 * FactoryDashboardViewsTest) spreken 'm aan als `FactoryDashboardViews.StatusBucket.X` en
 * een genest type is in Kotlin niet te aliassen — de logica leeft hier, het type daar.
 */
internal object StoryStatusPresenter {

    /** Afgeleide, leesbare "echte" status van een story + bijbehorende badge-kind. */
    data class StoryStatusView(val label: String, val kind: String)

    /** Story-fasen die tot het refinement- resp. planning-deel van de lifecycle horen. */
    private val refiningPhases = setOf(
        StoryPhase.REFINING, StoryPhase.REFINED_WITH_QUESTIONS, StoryPhase.QUESTIONS_ANSWERED,
        StoryPhase.REFINED, StoryPhase.REFINED_REJECTED, StoryPhase.REFINED_APPROVED,
    )
    private val planningPhases = setOf(
        StoryPhase.PLANNING, StoryPhase.PLANNED_WITH_QUESTIONS, StoryPhase.PLANNING_QUESTIONS_ANSWERED,
        StoryPhase.PLANNED, StoryPhase.PLANNING_REJECTED,
    )

    /**
     * Classificeert de board-lane (`State`-veld) van een story case-insensitive in een bucket.
     * De factory zet die lane zelf: `In Progress` bij de eerste agent, `Done` als alle subtaken
     * klaar zijn (zie transitionIssue + SubtaskExecutionCoordinator). `To Verify` telt als nog
     * bezig. Onbekende/lege statussen → [StatusBucket.TODO].
     */
    fun classifyStatus(status: String?): StatusBucket {
        val normalized = status?.trim()?.lowercase() ?: return StatusBucket.TODO
        return when (normalized) {
            "done", "fixed", "verified", "closed", "resolved" -> StatusBucket.FINISHED
            "in progress", "to verify", "develop", "developing" -> StatusBucket.IN_PROGRESS
            "open", "submitted", "backlog", "to do" -> StatusBucket.TODO
            else -> StatusBucket.TODO
        }
    }

    /**
     * De "echte" status van een STORY, voorbij het platte `planning-approved`. Combineert de
     * refinement-fase, de YouTrack `State`-lane (die de factory zelf op In Progress/Done zet) en —
     * waar beschikbaar — de subtaken en de merged-vlag tot één leesbaar label:
     * Merged · Fout · Gepauzeerd · Done · Todo · Refining · Planning · In progress.
     * [subtasks] mag leeg zijn (overzicht); dan leunt Done/Fout op de lane resp. story-error.
     */
    fun realStatus(issue: TrackerIssue, subtasks: List<TrackerIssue>, merged: Boolean): StoryStatusView {
        val phase = StoryPhase.fromTracker(issue.fields.storyPhase)
        val laneDone = classifyStatus(issue.status) == StatusBucket.FINISHED
        val done = laneDone || (subtasks.isNotEmpty() && subtasks.all { subtaskIsDone(it) })
        val hasError = !issue.fields.error.isNullOrBlank() || subtasks.any { !it.fields.error.isNullOrBlank() }
        return when {
            merged -> StoryStatusView("Merged", "ok")
            hasError -> StoryStatusView("Fout", "bad")
            issue.fields.paused -> StoryStatusView("Gepauzeerd", "warn")
            done -> StoryStatusView("Done", "ok")
            phase == null || phase == StoryPhase.START -> StoryStatusView("Todo", "neutral")
            phase in refiningPhases -> StoryStatusView("Refining", "info")
            phase in planningPhases -> StoryStatusView("Planning", "info")
            phase == StoryPhase.IN_PROGRESS -> StoryStatusView("In progress", "info")
            // Planning goedgekeurd maar nog niets opgepakt → wacht op de "Start developing"-knop.
            // Zodra dat gebeurt zet de orchestrator de fase op `in-progress` (zie startDeveloping),
            // dus hier hoeven we de subtaken niet te kennen — werkt ook in het overzicht. De
            // subtaak-check blijft als vangnet voor stories van vóór deze wijziging.
            phase == StoryPhase.PLANNING_APPROVED -> {
                val started = subtasks.any { !it.fields.subtaskPhase.isNullOrBlank() }
                if (started) StoryStatusView("In progress", "info")
                else StoryStatusView("Klaar om te starten", "warn")
            }
            else -> StoryStatusView("In progress", "info")
        }
    }

    /** Of een subtask z'n eindfase heeft bereikt (review/test/summary-approved of manual-action-done). */
    fun subtaskIsDone(issue: TrackerIssue): Boolean =
        SubtaskPhase.fromTracker(issue.fields.subtaskPhase)?.isTerminal == true

    /** Of een subtask een fout bevat. */
    fun subtaskHasError(issue: TrackerIssue): Boolean =
        !issue.fields.error.isNullOrBlank()

    /** Of een subtask actief is (agent draait) en nog niet in terminale fase. */
    fun subtaskIsActive(issue: TrackerIssue): Boolean {
        val phase = SubtaskPhase.fromTracker(issue.fields.subtaskPhase)
        return phase?.isActive == true && phase.isTerminal == false
    }
}
