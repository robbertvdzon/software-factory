package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.youtrack.TrackerIssue

/**
 * Past handmatige commando's (pause/resume/kill/clear-error/…) op een issue toe vóór de
 * normale verwerking. Geëxposeerd in het base-package zodat de pipeline-module 'm kan gebruiken;
 * de implementatie (`ManualCommandService`) blijft module-intern.
 */
interface ManualCommandProcessor {
    fun apply(issue: TrackerIssue): ManualCommandApplication
}

data class ManualCommandApplication(
    val issue: TrackerIssue,
    val stopResult: IssueProcessResult? = null,
)
