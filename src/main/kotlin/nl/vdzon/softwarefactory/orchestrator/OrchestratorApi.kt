package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.orchestrator.repositories.JdbcSystemStateRepository
import nl.vdzon.softwarefactory.youtrack.FactoryCommand
import nl.vdzon.softwarefactory.youtrack.TrackerIssue
import org.springframework.jdbc.core.JdbcTemplate

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

    companion object {
        fun systemStateRepository(jdbcTemplate: JdbcTemplate, factorySecrets: FactorySecrets): SystemStateRepository =
            JdbcSystemStateRepository(jdbcTemplate, factorySecrets)
    }
}
