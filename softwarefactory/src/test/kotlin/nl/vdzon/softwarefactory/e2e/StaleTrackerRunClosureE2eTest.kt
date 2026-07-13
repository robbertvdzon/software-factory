package nl.vdzon.softwarefactory.e2e

import nl.vdzon.softwarefactory.core.contracts.CostMonitor
import nl.vdzon.softwarefactory.core.contracts.StoryRunRepository
import nl.vdzon.softwarefactory.orchestrator.services.CostMonitorService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals

class StaleTrackerRunClosureE2eTest : E2eTestBase() {

    @Autowired
    private lateinit var costMonitor: CostMonitor

    @Autowired
    private lateinit var storyRuns: StoryRunRepository

    @Test
    fun `stale run zonder Postgres issue sluit eenmaal en blijft bij tweede poll stil`() {
        val storyKey = "${state.projectKey}-990"
        state.createIssue(summary = "wordt verwijderd", key = storyKey)
        val run = storyRuns.openOrCreate(storyKey, "git@example/repo.git")
        state.deleteIssue(storyKey)

        costMonitor.checkAllActiveStories()

        val closedAfterFirstPoll = state.storyRunClosure(run.id)
        assertEquals(CostMonitorService.MISSING_TRACKER_STATUS, closedAfterFirstPoll.first)
        val endedAt = requireNotNull(closedAfterFirstPoll.second)
        assertEquals(emptyList(), storyRuns.activeRuns())

        costMonitor.checkAllActiveStories()

        val closedAfterSecondPoll = state.storyRunClosure(run.id)
        assertEquals(endedAt, closedAfterSecondPoll.second)
        assertEquals(CostMonitorService.MISSING_TRACKER_STATUS, closedAfterSecondPoll.first)
        assertEquals(emptyList(), storyRuns.activeRuns())
    }
}
