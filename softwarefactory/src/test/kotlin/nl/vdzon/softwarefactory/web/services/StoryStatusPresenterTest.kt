package nl.vdzon.softwarefactory.web.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** SF-918: classifyStatus is de single source of truth die het findAiIssues-poll-filter en de
 * TrackerStoryApiController.status-response ook gebruiken (via FinishedStatus). */
class StoryStatusPresenterTest {

    @Test
    fun `classifyStatus treats Done and its legacy synonyms as FINISHED`() {
        listOf("Done", "done", " DONE ", "fixed", "verified", "closed", "resolved").forEach {
            assertEquals(StatusBucket.FINISHED, StoryStatusPresenter.classifyStatus(it), "status '$it' moet FINISHED zijn")
        }
    }

    @Test
    fun `classifyStatus treats in-progress synonyms as IN_PROGRESS`() {
        listOf("In Progress", "to verify", "develop", "developing").forEach {
            assertEquals(StatusBucket.IN_PROGRESS, StoryStatusPresenter.classifyStatus(it), "status '$it' moet IN_PROGRESS zijn")
        }
    }

    @Test
    fun `classifyStatus falls back to TODO for null, blank or unknown statuses`() {
        listOf(null, "", "open", "backlog", "something-unknown").forEach {
            assertEquals(StatusBucket.TODO, StoryStatusPresenter.classifyStatus(it), "status '$it' moet TODO zijn")
        }
    }
}
