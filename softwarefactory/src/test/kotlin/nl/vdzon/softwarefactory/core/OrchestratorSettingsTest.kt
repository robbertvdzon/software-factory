package nl.vdzon.softwarefactory.core

import nl.vdzon.softwarefactory.config.OrchestratorSettingsFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

class OrchestratorSettingsTest {
    @Test
    fun `fromEnvironment falls back to the documented defaults for an empty environment`() {
        val settings = OrchestratorSettingsFactory.fromEnvironment(emptyMap())

        assertEquals(Duration.ofMillis(1000), settings.pollInterval)
        assertEquals(Duration.ofMillis(1000), settings.pollIntervalIdle)
        assertEquals(1, settings.maxParallelRefiner)
        assertEquals(2, settings.maxParallelDeveloper)
        assertEquals(2, settings.maxParallelReviewer)
        assertEquals(1, settings.maxParallelTester)
        assertEquals(4, settings.maxParallelTotal)
        assertEquals(OrchestratorSettings.DEFAULT_MAX_DEVELOPER_LOOPBACKS, settings.maxDeveloperLoopbacks)
        assertEquals(OrchestratorSettings.DEFAULT_MAX_TEST_CHAIN_RESETS, settings.maxTestChainResets)
        assertEquals(2, settings.maxTransientRetries)
        assertEquals(Duration.ofMinutes(60), settings.hardTimeout)
        assertEquals(Duration.ofMillis(60000), settings.activePhaseRecoveryDelay)
        assertEquals(Duration.ofMillis(300000), settings.costMonitorInterval)
        assertEquals(Duration.ofMinutes(30), settings.creditsPauseDefault)
    }

    @Test
    fun `fromEnvironment reads provided overrides and ignores blank or invalid values`() {
        val settings = OrchestratorSettingsFactory.fromEnvironment(
            mapOf(
                "SF_MAX_PARALLEL_DEVELOPER" to "7",
                "SF_MAX_DEVELOPER_LOOPBACKS" to "",
                "SF_MAX_TEST_CHAIN_RESETS" to "not-a-number",
                "SF_COST_MONITOR_INTERVAL_MS" to "120000",
            ),
        )

        assertEquals(7, settings.maxParallelDeveloper)
        // Blank/invalid env values fall back to the defaults.
        assertEquals(OrchestratorSettings.DEFAULT_MAX_DEVELOPER_LOOPBACKS, settings.maxDeveloperLoopbacks)
        assertEquals(OrchestratorSettings.DEFAULT_MAX_TEST_CHAIN_RESETS, settings.maxTestChainResets)
        assertEquals(Duration.ofMillis(120000), settings.costMonitorInterval)
    }

    @Test
    fun `data class default keeps the idle poll interval at 45 seconds`() {
        val settings = OrchestratorSettings(
            pollInterval = Duration.ofSeconds(1),
            maxParallelRefiner = 1,
            maxParallelDeveloper = 1,
            maxParallelReviewer = 1,
            maxParallelTester = 1,
            maxParallelTotal = 1,
            maxDeveloperLoopbacks = 1,
            maxTransientRetries = 1,
            hardTimeout = Duration.ofMinutes(1),
            costMonitorInterval = Duration.ofMinutes(1),
            creditsPauseDefault = Duration.ofMinutes(1),
        )

        assertEquals(Duration.ofSeconds(45), settings.pollIntervalIdle)
    }
}
