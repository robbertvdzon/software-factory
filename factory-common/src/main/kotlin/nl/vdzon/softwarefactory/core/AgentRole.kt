package nl.vdzon.softwarefactory.core

/**
 * Canonieke agentrollen van de factory, gedeeld door domein, Runtime-adapter en dashboard.
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
    ORCHESTRATOR("[ORCHESTRATOR]"),
    /** Read-only audit-agent: schrijft een rapport, past nooit zelf code aan. Geen tracker-story. */
    AUDITOR("[AUDITOR]");

    val markerKeyPart: String = name.lowercase().replace("_", "-")
}
