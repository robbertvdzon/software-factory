package nl.vdzon.softwarefactory.orchestrator

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import nl.vdzon.softwarefactory.orchestrator.services.OrchestratorService
import nl.vdzon.softwarefactory.testsupport.FakeTrackerApi
import nl.vdzon.softwarefactory.testsupport.InMemoryStoryRunRepository
import nl.vdzon.softwarefactory.testsupport.OrchestratorTestHarness
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * SF-1460 — zichtbaarheid voor een start-next-promotie die geblokkeerd blijft doordat een andere
 * story de repo al langer dan [nl.vdzon.softwarefactory.core.contracts.OrchestratorSettings.blockedQueueWarnThreshold]
 * open houdt ([nl.vdzon.softwarefactory.core.contracts.StoryRunRepository.activeRunForRepo]). Geen
 * automatische sluiting — alleen een WARN-logregel, zodat dit niet uren onopgemerkt kan blijven.
 */
class QueuedStoryBlockedWarningTest : OrchestratorTestHarness() {

    private val appender = ListAppender<ILoggingEvent>()

    private fun logger(): Logger =
        LoggerFactory.getLogger(OrchestratorService::class.java) as Logger

    @AfterEach
    fun tearDown() {
        logger().detachAppender(appender)
    }

    private fun startCapture() {
        appender.start()
        logger().addAppender(appender)
    }

    @Test
    fun `poll logs a WARN when a queued story stays blocked by a long-open run for its repo`() {
        startCapture()
        val storyRuns = InMemoryStoryRunRepository()
        // Ruim voorbij de default blockedQueueWarnThreshold (4 uur).
        storyRuns.openOrCreate("SF-1", "git@example/repo.git", now.minusHours(10))
        val issueTracker = FakeTrackerApi(listOf(issue("SF-3", storyPhase = "start-next")))
        val service = service(issueTracker, storyRuns = storyRuns)

        service.pollOnce()

        val warnings = appender.list.filter { it.level == Level.WARN }
        assertTrue(
            warnings.any { it.formattedMessage.contains("SF-1") && it.formattedMessage.contains("git@example/repo.git") },
            "verwachtte een WARN over de geblokkeerde wachtrij, kreeg: ${appender.list.map { it.formattedMessage }}",
        )
    }

    @Test
    fun `poll does not warn when the blocking run is still within the threshold`() {
        startCapture()
        val storyRuns = InMemoryStoryRunRepository()
        storyRuns.openOrCreate("SF-1", "git@example/repo.git", now.minusMinutes(5))
        val issueTracker = FakeTrackerApi(listOf(issue("SF-3", storyPhase = "start-next")))
        val service = service(issueTracker, storyRuns = storyRuns)

        service.pollOnce()

        val warnings = appender.list.filter { it.level == Level.WARN }
        assertFalse(
            warnings.any { it.formattedMessage.contains("geblokkeerd") },
            "onverwachte blokkade-WARN: ${appender.list.map { it.formattedMessage }}",
        )
    }
}
