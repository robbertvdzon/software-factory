package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.core.contracts.IssueProcessResult
import nl.vdzon.softwarefactory.core.contracts.SystemStateRepository
import nl.vdzon.softwarefactory.core.contracts.OrchestratorPollResult

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.orchestrator.repositories.JdbcSystemStateRepository
import nl.vdzon.softwarefactory.core.contracts.FactoryCommand
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Public API of the orchestrator module.
 *
 * The orchestrator module owns the story state machine. It polls work from
 * the tracker database, maps AI phases to agent roles, dispatches agent runs, applies
 * manual commands, monitors PR feedback and enforces budget/retry/time limits.
 */
interface OrchestratorApi {
    fun pollOnce(projectKey: String = "KAN"): OrchestratorPollResult

    fun processIssue(issue: TrackerIssue): IssueProcessResult

    fun queueCommand(storyKey: String, command: FactoryCommand, reason: String? = null)

    /**
     * Ruimt een hele story synchroon en hard op (tracker-issue + subtaken, branch,
     * workfolder, PR, preview, run-DB). Onomkeerbaar. Anders dan [queueCommand] draait
     * dit direct, niet via een tracker-comment.
     */
    fun purgeStory(storyKey: String) {
        throw UnsupportedOperationException("Purging stories is not supported by this OrchestratorApi.")
    }

    companion object {
        fun systemStateRepository(jdbcTemplate: JdbcTemplate, factorySecrets: FactorySecrets): SystemStateRepository =
            JdbcSystemStateRepository(jdbcTemplate, factorySecrets)
    }
}
