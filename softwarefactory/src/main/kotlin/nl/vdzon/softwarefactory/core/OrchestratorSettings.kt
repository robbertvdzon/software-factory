package nl.vdzon.softwarefactory.core

import nl.vdzon.softwarefactory.core.AgentRole
import java.time.Duration

data class OrchestratorSettings(
    /** Vast backup-poll-interval (vangnet); de poller wordt daarnaast event-driven gewekt bij elke DB-write. */
    val pollInterval: Duration,
    val maxParallelRefiner: Int,
    val maxParallelDeveloper: Int,
    val maxParallelReviewer: Int,
    val maxParallelTester: Int,
    val maxParallelTotal: Int,
    val maxDeveloperLoopbacks: Int,
    /**
     * SF-200 — cap op het aantal keren dat een test-bevinding de hele subtaak-keten opnieuw mag
     * resetten. Analoog aan [maxDeveloperLoopbacks], maar conceptueel een andere grens (test-chain
     * reset i.p.v. gerichte developer-loopback). Default veilig laag zodat een story niet eindeloos
     * de keten blijft herstarten.
     */
    val maxTestChainResets: Int = DEFAULT_MAX_TEST_CHAIN_RESETS,
    val maxTransientRetries: Int,
    val hardTimeout: Duration,
    val activePhaseRecoveryDelay: Duration = Duration.ofMinutes(1),
    val costMonitorInterval: Duration,
    val creditsPauseDefault: Duration,
) {
    fun maxParallelFor(role: AgentRole): Int =
        when (role) {
            AgentRole.REFINER -> maxParallelRefiner
            AgentRole.PLANNER -> maxParallelRefiner
            AgentRole.DEVELOPER -> maxParallelDeveloper
            AgentRole.REVIEWER -> maxParallelReviewer
            AgentRole.TESTER -> maxParallelTester
            AgentRole.SUMMARIZER -> maxParallelTester
            AgentRole.DOCUMENTER -> maxParallelTester
            AgentRole.ASSISTANT,
            AgentRole.COST_MONITOR,
            AgentRole.ORCHESTRATOR,
            -> 1
        }

    companion object {
        // De factory die deze settings uit de (factory-)omgeving leest, leeft in het config-package
        // (OrchestratorSettingsFactory): core kent bewust geen infrastructuur/omgevingsresolutie.
        const val DEFAULT_MAX_DEVELOPER_LOOPBACKS = 5
        const val DEFAULT_MAX_TEST_CHAIN_RESETS = 3
    }
}
