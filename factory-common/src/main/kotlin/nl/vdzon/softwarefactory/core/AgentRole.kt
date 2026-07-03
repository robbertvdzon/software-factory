package nl.vdzon.softwarefactory.core

/**
 * De agent-rollen van de factory. Dit is het stukje tracker-domein dat óók in de agent-container
 * nodig is (prompts en comment-prefixes per rol), daarom leeft het in factory-common en niet in
 * het core-domein van de server. De agentworker had hiervoor een eigen (verouderde) kopie in
 * `youtrack/TrackerModels.kt`; die is vervangen door deze ene bron van waarheid.
 */
enum class AgentRole(val commentPrefix: String) {
    REFINER("[REFINER]"),
    PLANNER("[PLANNER]"),
    DEVELOPER("[DEVELOPER]"),
    REVIEWER("[REVIEWER]"),
    TESTER("[TESTER]"),
    SUMMARIZER("[SUMMARIZER]"),
    DOCUMENTER("[DOCUMENTER]"),
    ASSISTANT("[ASSISTANT]"),
    COST_MONITOR("[COST-MONITOR]"),
    ORCHESTRATOR("[ORCHESTRATOR]");

    val markerKeyPart: String = name.lowercase().replace("_", "-")
}
