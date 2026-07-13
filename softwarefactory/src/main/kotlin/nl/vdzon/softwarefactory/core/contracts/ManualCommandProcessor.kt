package nl.vdzon.softwarefactory.core.contracts

import nl.vdzon.softwarefactory.core.AgentComments
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.DeploymentConfig
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.*

import nl.vdzon.softwarefactory.core.contracts.IssueProcessResult
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue

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
    val retryLater: Boolean = false,
)
