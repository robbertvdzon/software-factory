package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.IssueProcessResult
import nl.vdzon.softwarefactory.testsupport.FakeTrackerApi
import nl.vdzon.softwarefactory.testsupport.InMemoryStoryRunRepository
import nl.vdzon.softwarefactory.testsupport.OrchestratorTestHarness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Per-repo-wachtrij (`start-next`): promotor in [nl.vdzon.softwarefactory.orchestrator.services.OrchestratorService].
 * Zie [nl.vdzon.softwarefactory.core.contracts.StoryPhase.START_NEXT] en
 * [nl.vdzon.softwarefactory.core.contracts.StoryRunRepository.activeRunForRepo].
 */
class QueuedStoryPromotionTest : OrchestratorTestHarness() {

    @Test
    fun `poll promotes the lowest-numbered queued story once its repo is free`() {
        val issueTracker = FakeTrackerApi(
            listOf(
                issue("SF-5", storyPhase = "start-next"),
                issue("SF-2", storyPhase = "start-next"),
            ),
        )
        val service = service(issueTracker)

        val result = service.pollOnce()

        // Promotie zelf gebeurt vóór processIssue, op de al opgehaalde (dus nog niet-verse) batch:
        // beide kandidaten worden deze cyclus nog als "queued-start-next" geskipt. SF-2 pakt de
        // orchestrator pas de volgende poll op (nu het veld `start` is).
        assertEquals(
            listOf(
                IssueProcessResult.Skipped("SF-5", "queued-start-next"),
                IssueProcessResult.Skipped("SF-2", "queued-start-next"),
            ),
            result.issueResults,
        )
        assertEquals("start", issueTracker.lastUpdate("SF-2").values[TrackerField.STORY_PHASE])
        assertFalse(issueTracker.updates.containsKey("SF-5"), "SF-5 heeft het hoogste nummer en mag niet gepromoot worden")
    }

    @Test
    fun `poll does not promote a queued story while another story still has an open run for the same repo`() {
        val storyRuns = InMemoryStoryRunRepository()
        storyRuns.openOrCreate("SF-1", "git@example/repo.git")
        val issueTracker = FakeTrackerApi(listOf(issue("SF-3", storyPhase = "start-next")))
        val service = service(issueTracker, storyRuns = storyRuns)

        service.pollOnce()

        assertFalse(issueTracker.updates.containsKey("SF-3"), "Repo is nog bezig (SF-1); SF-3 mag niet gepromoot worden")
    }

    @Test
    fun `poll promotes a queued story that is blocked only by its own dangling run and closes that run`() {
        val storyRuns = InMemoryStoryRunRepository()
        val danglingRun = storyRuns.openOrCreate("SF-6", "git@example/repo.git")
        val issueTracker = FakeTrackerApi(listOf(issue("SF-6", storyPhase = "start-next")))
        val service = service(issueTracker, storyRuns = storyRuns)

        service.pollOnce()

        assertEquals(
            "start",
            issueTracker.lastUpdate("SF-6").values[TrackerField.STORY_PHASE],
            "SF-6 blokkeert alleen zichzelf via haar eigen achtergebleven run en moet gewoon gepromoot worden",
        )
        assertEquals(listOf(danglingRun.id to "requeued"), storyRuns.closed)
    }

    @Test
    fun `start still bypasses the queue and dispatches immediately even while another run is open for the repo`() {
        val storyRuns = InMemoryStoryRunRepository()
        storyRuns.openOrCreate("SF-1", "git@example/repo.git")
        val issueTracker = FakeTrackerApi(listOf(issue("SF-4", storyPhase = "start")))
        val service = service(issueTracker, storyRuns = storyRuns)

        service.pollOnce()

        assertEquals("refining", issueTracker.lastUpdate("SF-4").values[TrackerField.STORY_PHASE])
    }
}
