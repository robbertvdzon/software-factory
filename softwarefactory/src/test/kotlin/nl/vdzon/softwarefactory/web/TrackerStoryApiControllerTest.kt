package nl.vdzon.softwarefactory.web

import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.contracts.TrackerIssueFields
import nl.vdzon.softwarefactory.testsupport.FakeTrackerApi
import nl.vdzon.softwarefactory.web.controllers.TrackerStoryApiController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest

/** Unit-tests voor [TrackerStoryApiController.status] (SF-918: status/done-veld naast phase). */
class TrackerStoryApiControllerTest {

    private val envProvider: ConfigApi = object : ConfigApi {
        override fun resolvedValues(): Map<String, String> = mapOf("SF_FACTORY_API_TOKEN" to "test-token")
    }

    private fun authorizedRequest(): MockHttpServletRequest =
        MockHttpServletRequest("GET", "/api/tracker/stories/SF-1").apply {
            addHeader("Authorization", "Bearer test-token")
        }

    private fun story(key: String, status: String, storyPhase: String? = "in-progress"): TrackerIssue =
        TrackerIssue(
            key = key,
            summary = "Story $key",
            status = status,
            fields = TrackerIssueFields(
                targetRepo = null,
                repo = "softwarefactory",
                aiSupplier = "claude",
                aiPhase = null,
                aiLevel = null,
                aiTokenBudget = null,
                aiTokensUsed = null,
                agentStartedAt = null,
                paused = false,
                error = null,
                type = "User Story",
                storyPhase = storyPhase,
            ),
            comments = emptyList(),
        )

    @Test
    fun `status reports done true and the raw status for a finished story`() {
        val issue = story(key = "SF-1", status = "Done", storyPhase = "in-progress")
        val trackerApi = FakeTrackerApi(issues = listOf(issue))
        val controller = TrackerStoryApiController(trackerApi, envProvider)

        val response = controller.status(authorizedRequest(), "SF-1")

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body as Map<*, *>
        assertEquals("Done", body["status"])
        assertEquals(true, body["done"])
        // phase blijft ongewijzigd aanwezig naast het nieuwe status/done-veld.
        assertEquals("in-progress", body["phase"])
    }

    @Test
    fun `status reports done false and the raw status for an in-progress story`() {
        val issue = story(key = "SF-2", status = "In Progress", storyPhase = "in-progress")
        val trackerApi = FakeTrackerApi(issues = listOf(issue))
        val controller = TrackerStoryApiController(trackerApi, envProvider)

        val response = controller.status(authorizedRequest(), "SF-2")

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body as Map<*, *>
        assertEquals("In Progress", body["status"])
        assertEquals(false, body["done"])
    }

    @Test
    fun `status treats other finished-synonym statuses as done too`() {
        // Consistent met StoryStatusPresenter.classifyStatus/FinishedStatus: niet alleen de
        // letterlijke "Done"-lane, maar ook legacy-synoniemen tellen als afgerond.
        val issue = story(key = "SF-3", status = "resolved", storyPhase = "in-progress")
        val trackerApi = FakeTrackerApi(issues = listOf(issue))
        val controller = TrackerStoryApiController(trackerApi, envProvider)

        val response = controller.status(authorizedRequest(), "SF-3")

        val body = response.body as Map<*, *>
        assertEquals(true, body["done"])
    }

    @Test
    fun `status returns 401 when the bearer token does not match`() {
        val issue = story(key = "SF-1", status = "Done")
        val trackerApi = FakeTrackerApi(issues = listOf(issue))
        val controller = TrackerStoryApiController(trackerApi, envProvider)

        val request = MockHttpServletRequest("GET", "/api/tracker/stories/SF-1").apply {
            addHeader("Authorization", "Bearer wrong-token")
        }

        val response = controller.status(request, "SF-1")

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }
}
