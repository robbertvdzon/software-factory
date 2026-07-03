package nl.vdzon.softwarefactory.core

/**
 * De YouTrack-board-lanes (het `State`-veld) waar de factory issues naartoe verplaatst.
 * Eén bron voor de lane-namen: die stonden voorheen als losse `"Done"`/`"Open"`-literals
 * verspreid over coordinator, command-service en orchestrator.
 */
enum class BoardState(val laneName: String) {
    TODO("Open"),
    IN_PROGRESS("In Progress"),
    DONE("Done"),
}
