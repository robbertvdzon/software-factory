package nl.vdzon.softwarefactory.core

import nl.vdzon.softwarefactory.core.contracts.*
import nl.vdzon.softwarefactory.core.*
import nl.vdzon.softwarefactory.core.contracts.*

import nl.vdzon.softwarefactory.config.services.OrchestratorSettingsFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

class OrchestratorSettingsTest {
    @Test
    fun `fromEnvironment falls back to the documented defaults for an empty environment`() {
        val settings = OrchestratorSettingsFactory.fromEnvironment(emptyMap())

        assertEquals(Duration.ofMillis(60000), settings.pollInterval)
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
}
