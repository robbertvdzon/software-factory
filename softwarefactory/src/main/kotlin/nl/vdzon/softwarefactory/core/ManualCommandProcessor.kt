package nl.vdzon.softwarefactory.core

import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.core.TrackerIssue

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
