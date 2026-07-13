package nl.vdzon.softwarefactory.core.contracts

import nl.vdzon.softwarefactory.core.AgentComments
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.DeploymentConfig
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.*

/**
 * De tracker-board-lanes (het `State`-veld) waar de factory issues naartoe verplaatst.
 * Eén bron voor de lane-namen: die stonden voorheen als losse `"Done"`/`"Open"`-literals
 * verspreid over coordinator, command-service en orchestrator.
 */
enum class BoardState(val laneName: String) {
    TODO("Open"),
    IN_PROGRESS("In Progress"),
    DONE("Done"),
}

/**
 * Genormaliseerde (lowercase, getrimd) tracker-`status`-waarden die als "afgerond" gelden.
 * Single source of truth voor zowel `StoryStatusPresenter.classifyStatus` (dashboard-classificatie)
 * als `PostgresTrackerClient.findAiIssues` (poll-filter): niet alleen de letterlijke [BoardState.DONE]
 * ("Done"), maar ook legacy/handmatige synoniemen die dezelfde afgeronde lane vertegenwoordigen.
 */
object FinishedStatus {
    val VALUES: Set<String> = setOf("done", "fixed", "verified", "closed", "resolved")

    fun isFinished(status: String?): Boolean = status?.trim()?.lowercase() in VALUES
}
