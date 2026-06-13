package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.youtrack.TrackerIssue

/**
 * Contract van de story/subtask-verwerkingsengine: gegeven één issue, bepaal en voer de volgende
 * stap uit (dispatch / recovery / keten-doorzetten / auto-approve / promote) en geef de uitkomst.
 *
 * Geëxposeerd in het base-package zodat de orchestrator-shell de engine kan aanroepen zónder de
 * implementatie (`pipeline`-module) te kennen — de pijl wijst van `pipeline` naar `orchestrator`,
 * niet andersom.
 */
interface Pipeline {
    fun process(issue: TrackerIssue): IssueProcessResult
}
