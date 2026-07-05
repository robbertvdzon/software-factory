package nl.vdzon.softwarefactory.web

import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.FactoryCommand
import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.core.OrchestratorPollResult
import nl.vdzon.softwarefactory.core.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.nightly.NightlyGateway
import nl.vdzon.softwarefactory.nightly.NightlyJob
import nl.vdzon.softwarefactory.nightly.NightlyRunJobRepository
import nl.vdzon.softwarefactory.nightly.NightlyRunRepository
import nl.vdzon.softwarefactory.nightly.NightlyScheduler
import nl.vdzon.softwarefactory.nightly.NightlySettingsRepository
import nl.vdzon.softwarefactory.nightly.NightlyStoryOutcome
import nl.vdzon.softwarefactory.nightly.NightlyTime
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.web.config.DashboardAuthConfig
import nl.vdzon.softwarefactory.web.config.DashboardAuthInterceptor
import nl.vdzon.softwarefactory.web.controllers.FactoryDashboardController
import nl.vdzon.softwarefactory.web.repositories.FactoryDashboardRepository
import nl.vdzon.softwarefactory.web.services.DashboardEventBus
import nl.vdzon.softwarefactory.web.services.FactoryDashboardAuth
import nl.vdzon.softwarefactory.web.services.FactoryDashboardService
import nl.vdzon.softwarefactory.web.services.FactoryOperationsService
import nl.vdzon.softwarefactory.web.services.FactoryProcessService
import nl.vdzon.softwarefactory.web.services.FactoryVersionService
import nl.vdzon.softwarefactory.web.views.FactoryDashboardViews
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertTrue

/**
 * Dekt het centrale interceptor-gedrag dat voorheen als gekopieerde auth-blokken in
 * FactoryDashboardController zat: beschermde routes redirecten zonder login naar
 * `/login?next=…`, en `/my-actions/count` blijft de expliciete uitzondering ("0", geen redirect).
 * Standalone MockMvc met de échte controller + de échte pattern-lijst uit [DashboardAuthConfig].
 */
class DashboardAuthInterceptorTest {

    private val clock = Clock.fixed(Instant.parse("2026-05-24T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `protected page without login redirects to login with next`() {
        mockMvc().perform(get("/dashboard"))
            .andExpect(status().isSeeOther)
            .andExpect(header().string(HttpHeaders.LOCATION, "/login?next=%2Fdashboard"))
    }

    @Test
    fun `protected post action without login redirects to the carrying story page`() {
        mockMvc().perform(post("/stories/SF-1/commands/approve"))
            .andExpect(status().isSeeOther)
            .andExpect(header().string(HttpHeaders.LOCATION, "/login?next=%2Fstories%2FSF-1"))
    }

    @Test
    fun `my-actions count without login returns zero instead of a redirect`() {
        mockMvc().perform(get("/my-actions/count"))
            .andExpect(status().isOk)
            .andExpect(content().string("0"))
    }

    @Test
    fun `protected page with a logged-in session renders normally`() {
        val fixture = fixture()
        val session = MockHttpSession()
        assertTrue(fixture.auth.login(session, "robbert", "secret"))

        fixture.mockMvc.perform(get("/dashboard").session(session))
            .andExpect(status().isOk)
    }

    // ── wiring ──────────────────────────────────────────────────────────────────

    private class Fixture(val mockMvc: MockMvc, val auth: FactoryDashboardAuth)

    private fun mockMvc(): MockMvc = fixture().mockMvc

    private fun fixture(): Fixture {
        val auth = FactoryDashboardAuth(
            object : ConfigApi {
                override fun resolvedValues(): Map<String, String> =
                    mapOf(
                        "SF_DASHBOARD_USERNAME" to "robbert",
                        "SF_DASHBOARD_PASSWORD" to "secret",
                    )
            },
            clock,
        )
        val secrets = fakeSecrets()
        val stubJdbc = StubJdbcTemplate()
        val repository = FactoryDashboardRepository(stubJdbc, secrets)
        val tracker = FakeYouTrackApi()
        val operations = FactoryOperationsService(
            issueTrackerClient = tracker,
            orchestratorApi = FakeOrchestratorApi(),
            repository = repository,
            previewApi = FakePreviewApi(),
        )
        val service = FactoryDashboardService(
            issueTrackerClient = tracker,
            orchestratorApi = FakeOrchestratorApi(),
            repository = repository,
            factorySecrets = secrets,
            operations = operations,
            projectRepoResolver = ProjectRepoResolver(emptyMap()),
            versionService = FactoryVersionService(),
            nightlySettingsRepository = NightlySettingsRepository(stubJdbc, secrets),
            nightlyRunRepository = NightlyRunRepository(stubJdbc, secrets),
            nightlyRunJobRepository = NightlyRunJobRepository(stubJdbc, secrets),
            // Geen defaults meer in productie-code: de echte beans expliciet meegeven.
            nightlyJobsReader = nl.vdzon.softwarefactory.nightly.NightlyJobsReader(),
            deployClient = nl.vdzon.softwarefactory.web.services.ProjectDeployClient(),
            workspaceLauncher = nl.vdzon.softwarefactory.web.services.WorkspaceDesktopLauncher(),
            gitHubReleaseClient = nl.vdzon.softwarefactory.web.services.GitHubReleaseClient(secrets),
            subtaskPlanMaterializer = nl.vdzon.softwarefactory.runtime.services.SubtaskPlanMaterializer(
                tracker,
                ProjectRepoResolver(emptyMap()),
            ),
        )
        val controller = FactoryDashboardController(
            auth = auth,
            service = service,
            operations = operations,
            views = FactoryDashboardViews(clock),
            eventBus = DashboardEventBus(),
            processService = FactoryProcessService(),
            nightlyScheduler = NightlyScheduler(
                NightlySettingsRepository(stubJdbc, secrets),
                NightlyRunRepository(stubJdbc, secrets),
                NightlyRunJobRepository(stubJdbc, secrets),
                NightlyTime(clock),
                FakeNightlyGateway,
            ),
        )
        // Zelfde pattern-lijst als de echte registratie, zodat de test de scoping mee-verifieert.
        val mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .addMappedInterceptors(DashboardAuthConfig.PROTECTED_PATHS.toTypedArray(), DashboardAuthInterceptor(auth))
            .build()
        return Fixture(mockMvc, auth)
    }

    private class StubJdbcTemplate : JdbcTemplate()

    private fun fakeSecrets(): FactorySecrets =
        FactorySecrets(
            youTrackBaseUrl = "http://fake",
            youTrackToken = "fake",
            youTrackProjects = emptyList(),
            githubToken = "fake",
            factoryDatabaseUrl = "jdbc:fake",
            factoryDatabaseSchema = "fake",
            kubeconfig = "fake",
            aiCredentialsDir = "fake",
            aiOauthToken = null,
            loadedFrom = "fake",
        )

    private class FakeYouTrackApi : YouTrackApi {
        override fun getIssue(issueKey: String): TrackerIssue = error("ongebruikt: getIssue")
        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) = error("ongebruikt: updateIssueFields")
        override fun transitionIssue(issueKey: String, statusName: String) = error("ongebruikt: transitionIssue")
        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment =
            error("ongebruikt: postAgentComment")
    }

    private class FakeOrchestratorApi : OrchestratorApi {
        override fun pollOnce(projectKey: String) = OrchestratorPollResult(emptyList())
        override fun processIssue(issue: TrackerIssue) = IssueProcessResult.Skipped(issue.key, "test")
        override fun queueCommand(storyKey: String, command: FactoryCommand, reason: String?) = Unit
        override fun purgeStory(storyKey: String) = Unit
    }

    private class FakePreviewApi : PreviewApi {
        override fun render(template: String?, prNumber: Int?): String? = null
        override fun cleanup(namespace: String): Boolean = false
    }

    private object FakeNightlyGateway : NightlyGateway {
        override fun allJobs(): List<NightlyJob> = emptyList()
        override fun startStory(project: String, jobName: String): String = error("ongebruikt: startStory")
        override fun storyOutcome(storyKey: String): NightlyStoryOutcome = error("ongebruikt: storyOutcome")
        override fun storyLink(storyKey: String): String = error("ongebruikt: storyLink")
        override fun sendDigest(project: String?, text: String): Boolean = false
    }
}
