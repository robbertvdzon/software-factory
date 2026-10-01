package nl.vdzon.softwarefactory.orchestrator.schedulers

import nl.vdzon.softwarefactory.core.contracts.ChangeNotifier
import nl.vdzon.softwarefactory.core.contracts.OrchestratorPollResult
import nl.vdzon.softwarefactory.core.contracts.OrchestratorSettings
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

class OrchestratorPollerTest {
    private val settings = OrchestratorSettings(
        pollInterval = Duration.ofMillis(10),
        maxParallelRefiner = 1,
        maxParallelDeveloper = 1,
        maxParallelReviewer = 1,
        maxParallelTester = 1,
        maxParallelTotal = 4,
        maxDeveloperLoopbacks = 3,
        maxTransientRetries = 2,
        hardTimeout = Duration.ofMinutes(60),
        costMonitorInterval = Duration.ofMinutes(5),
        creditsPauseDefault = Duration.ofMinutes(30),
    )

    @Test
    fun `poller keeps polling after an Error such as unable to create native thread`() {
        val orchestrator = mock(OrchestratorApi::class.java)
        val secondPoll = CountDownLatch(1)
        `when`(orchestrator.pollOnce())
            .thenThrow(OutOfMemoryError("unable to create native thread"))
            .thenAnswer {
                secondPoll.countDown()
                OrchestratorPollResult(emptyList())
            }
        val poller = OrchestratorPoller(orchestrator, settings, ChangeNotifier.Noop)

        poller.start()
        try {
            assertTrue(secondPoll.await(5, TimeUnit.SECONDS), "poller moet na een Error doorgaan met pollen")
        } finally {
            poller.stop()
        }
    }
}
