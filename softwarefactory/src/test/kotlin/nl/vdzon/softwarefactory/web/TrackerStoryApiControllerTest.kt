package nl.vdzon.softwarefactory.web

import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.config.ProjectRepositoryCatalog
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.contracts.TrackerIssueFields
import nl.vdzon.softwarefactory.testsupport.FakeTrackerApi
import nl.vdzon.softwarefactory.web.controllers.CreateTrackerStoryRequest
import nl.vdzon.softwarefactory.web.controllers.TrackerStoryApiController
import nl.vdzon.softwarefactory.web.controllers.UpdateTrackerStoryRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest

/** Unit-tests voor [TrackerStoryApiController.status] (SF-918: status/done-veld naast phase). */
class TrackerStoryApiControllerTest {

    private val envProvider: ConfigApi = object : ConfigApi {
        override fun resolvedValues(): Map<String, String> = mapOf("SF_FACTORY_API_TOKEN" to "test-token")
    }

    /** Geen projecten geconfigureerd: resolveRepoField laat het aangeleverde Repo-veld dan ongewijzigd. */
    private val noProjects: ProjectRepositoryCatalog = object : ProjectRepositoryCatalog {
        override fun repoFor(projectName: String?): String? = null
        override fun resolve(repoOrName: String?): String? = repoOrName
        override fun projectNames(): List<String> = emptyList()
        override fun projectNameFor(repoOrName: String?): String? = null
    }

    private fun authorizedRequest(): MockHttpServletRequest =
        MockHttpServletRequest("GET", "/api/tracker/stories/SF-1").apply {
            addHeader("Authorization", "Bearer test-token")
        }

    private fun story(
        key: String,
        status: String,
        storyPhase: String? = "in-progress",
        type: String = "User Story",
    ): TrackerIssue =
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
                type = type,
                storyPhase = storyPhase,
            ),
            comments = emptyList(),
        )

    @Test
    fun `status reports done true and the raw status for a finished story`() {
        val issue = story(key = "SF-1", status = "Done", storyPhase = "in-progress")
        val trackerApi = FakeTrackerApi(issues = listOf(issue))
        val controller = TrackerStoryApiController(trackerApi, envProvider, noProjects)

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
        val controller = TrackerStoryApiController(trackerApi, envProvider, noProjects)

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
        val controller = TrackerStoryApiController(trackerApi, envProvider, noProjects)

        val response = controller.status(authorizedRequest(), "SF-3")

        val body = response.body as Map<*, *>
        assertEquals(true, body["done"])
    }

    // SF-1959 — de hotfix-as is een aanmaakkeuze op deze route (`sf-story create --hotfix`).
    @Test
    fun `create zonder hotfix-veld maakt geen hotfix-story`() {
        val trackerApi = FakeTrackerApi(issues = emptyList())
        val controller = TrackerStoryApiController(trackerApi, envProvider, noProjects)

        val response = controller.create(authorizedRequest(), CreateTrackerStoryRequest(title = "Nieuwe story"))

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(listOf("Nieuwe story" to false), trackerApi.createdStories)
    }

    @Test
    fun `create met hotfix true geeft de vlag door aan de tracker`() {
        val trackerApi = FakeTrackerApi(issues = emptyList())
        val controller = TrackerStoryApiController(trackerApi, envProvider, noProjects)

        val response = controller.create(
            authorizedRequest(),
            CreateTrackerStoryRequest(title = "Snelle fix", hotfix = true),
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(listOf("Snelle fix" to true), trackerApi.createdStories)
    }

    @Test
    fun `create normaliseert een repo-URL naar de geconfigureerde projectnaam`() {
        val trackerApi = FakeTrackerApi(issues = emptyList())
        val projects: ProjectRepositoryCatalog = object : ProjectRepositoryCatalog {
            override fun repoFor(projectName: String?): String? = null
            override fun resolve(repoOrName: String?): String? = repoOrName
            override fun projectNames(): List<String> = emptyList()
            override fun projectNameFor(repoOrName: String?): String? =
                "hkh-autopilot".takeIf { repoOrName == "https://github.com/robbertvdzon/hkh-autopilot.git" }
        }
        val controller = TrackerStoryApiController(trackerApi, envProvider, projects)

        val response = controller.create(
            authorizedRequest(),
            CreateTrackerStoryRequest(title = "Vanuit Product Factory", repo = "https://github.com/robbertvdzon/hkh-autopilot.git"),
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body as Map<*, *>
        assertEquals("hkh-autopilot", body["repo"])
    }

    @Test
    fun `create wijst een onbekend notification-event af zonder story aan te maken`() {
        val trackerApi = FakeTrackerApi(issues = emptyList())
        val controller = TrackerStoryApiController(trackerApi, envProvider, noProjects)

        val response = controller.create(
            authorizedRequest(),
            CreateTrackerStoryRequest(title = "Typfout", notificationEvents = setOf("EROR")),
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals(emptyList<Pair<String, Boolean>>(), trackerApi.createdStories)
    }

    @Test
    fun `update valideert notification-events voor enige write`() {
        val trackerApi = FakeTrackerApi(issues = listOf(story("SF-1", "In Progress")))
        val controller = TrackerStoryApiController(trackerApi, envProvider, noProjects)

        val response = controller.update(
            authorizedRequest(),
            "SF-1",
            UpdateTrackerStoryRequest(summary = "Mag niet worden geschreven", notificationEvents = setOf("EROR")),
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertTrue(trackerApi.updates.isEmpty())
    }

    @Test
    fun `update wijst notification-events op een subtaak af voor enige write`() {
        val subtask = story("SF-2", "In Progress", storyPhase = null, type = "Task")
        val trackerApi = FakeTrackerApi(issues = listOf(subtask))
        val controller = TrackerStoryApiController(trackerApi, envProvider, noProjects)

        val response = controller.update(
            authorizedRequest(),
            subtask.key,
            UpdateTrackerStoryRequest(summary = "Mag niet worden geschreven", notificationEvents = setOf("ERROR")),
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertTrue(trackerApi.updates.isEmpty())
    }

    @Test
    fun `status returns 401 when the bearer token does not match`() {
        val issue = story(key = "SF-1", status = "Done")
        val trackerApi = FakeTrackerApi(issues = listOf(issue))
        val controller = TrackerStoryApiController(trackerApi, envProvider, noProjects)

        val request = MockHttpServletRequest("GET", "/api/tracker/stories/SF-1").apply {
            addHeader("Authorization", "Bearer wrong-token")
        }

        val response = controller.status(request, "SF-1")

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }
}
