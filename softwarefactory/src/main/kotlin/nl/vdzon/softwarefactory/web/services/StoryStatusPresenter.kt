package nl.vdzon.softwarefactory.web.services

import nl.vdzon.softwarefactory.core.FinishedStatus
import nl.vdzon.softwarefactory.core.StoryPhase
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.TrackerIssue

/** Status-buckets voor het classificeren van de tracker board-lane (`State`-veld). */
enum class StatusBucket(val attr: String) {
    FINISHED("finished"), IN_PROGRESS("in-progress"), TODO("todo")
}

internal object StoryStatusPresenter {

    data class StoryStatusView(val label: String, val kind: String)

    private val refiningPhases = setOf(
        StoryPhase.REFINING, StoryPhase.REFINED_WITH_QUESTIONS, StoryPhase.QUESTIONS_ANSWERED,
        StoryPhase.REFINED, StoryPhase.REFINED_REJECTED, StoryPhase.REFINED_APPROVED,
    )
    private val planningPhases = setOf(
        StoryPhase.PLANNING, StoryPhase.PLANNED_WITH_QUESTIONS, StoryPhase.PLANNING_QUESTIONS_ANSWERED,
        StoryPhase.PLANNED, StoryPhase.PLANNING_REJECTED,
    )

    fun classifyStatus(status: String?): StatusBucket {
        val normalized = status?.trim()?.lowercase() ?: return StatusBucket.TODO
        return when {
            normalized in FinishedStatus.VALUES -> StatusBucket.FINISHED
            normalized in setOf("in progress", "to verify", "develop", "developing") -> StatusBucket.IN_PROGRESS
            else -> StatusBucket.TODO
        }
    }

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
            phase == StoryPhase.PLANNING_APPROVED -> {
                val started = subtasks.any { !it.fields.subtaskPhase.isNullOrBlank() }
                if (started) StoryStatusView("In progress", "info")
                else StoryStatusView("Klaar om te starten", "warn")
            }
            else -> StoryStatusView("In progress", "info")
        }
    }

    fun subtaskIsDone(issue: TrackerIssue): Boolean =
        SubtaskPhase.fromTracker(issue.fields.subtaskPhase)?.isTerminal == true

    fun subtaskHasError(issue: TrackerIssue): Boolean =
        !issue.fields.error.isNullOrBlank()

    fun subtaskIsActive(issue: TrackerIssue): Boolean {
        val phase = SubtaskPhase.fromTracker(issue.fields.subtaskPhase)
        return phase?.isActive == true && phase.isTerminal == false
    }
}
