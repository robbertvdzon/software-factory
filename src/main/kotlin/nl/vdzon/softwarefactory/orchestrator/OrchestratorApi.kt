package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.youtrack.parsers.FactoryCommand
import nl.vdzon.softwarefactory.youtrack.TrackerIssue

/**
 * Public API of the orchestrator module.
 *
 * The orchestrator module owns the story state machine. It polls work from
 * YouTrack, maps AI phases to agent roles, dispatches agent runs, applies
 * manual commands, monitors PR feedback and enforces budget/retry/time limits.
 */
interface OrchestratorApi {
    fun pollOnce(projectKey: String = "KAN"): OrchestratorPollResult

    fun processIssue(issue: TrackerIssue): IssueProcessResult

    fun queueCommand(storyKey: String, command: FactoryCommand)
}

