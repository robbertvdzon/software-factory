package nl.vdzon.softwarefactory.bridge

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.FactoryCommand
import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.core.OrchestratorPollResult
import nl.vdzon.softwarefactory.core.TrackerAttachment
import nl.vdzon.softwarefactory.core.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerIssueFields
import nl.vdzon.softwarefactory.nightly.NightlyGateway
import nl.vdzon.softwarefactory.nightly.NightlyJob
import nl.vdzon.softwarefactory.nightly.NightlyJobsReader
import nl.vdzon.softwarefactory.nightly.NightlyRunJobRepository
import nl.vdzon.softwarefactory.nightly.NightlyRunRepository
import nl.vdzon.softwarefactory.nightly.NightlyScheduler
import nl.vdzon.softwarefactory.nightly.NightlySettingsRepository
import nl.vdzon.softwarefactory.nightly.NightlyStoryOutcome
import nl.vdzon.softwarefactory.nightly.NightlyTime
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.web.repositories.FactoryDashboardRepository
import nl.vdzon.softwarefactory.web.services.FactoryDashboardService
import nl.vdzon.softwarefactory.web.services.FactoryOperationsService
import nl.vdzon.softwarefactory.web.services.FactoryProcessService
import nl.vdzon.softwarefactory.web.services.FactoryVersionService
import nl.vdzon.softwarefactory.web.services.GitHubReleaseClient
import nl.vdzon.softwarefactory.web.services.ProjectDeployClient
import nl.vdzon.softwarefactory.web.services.WorkspaceDesktopLauncher
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Gedeelde test-wiring voor een minimale (maar echte) [FactoryDashboardService]/
 * [FactoryOperationsService], zelfde recept als `DashboardAuthInterceptorTest`/
 * `FactoryDashboardServiceTest` — hergebruikt door [BridgeRequestHandlerTest] en
 * [BridgeClientTest].
 */
internal object BridgeTestFixtures {

    fun minimalRequestHandler(
        issues: List<TrackerIssue>? = emptyList(),
        attachments: List<TrackerAttachment> = emptyList(),
        attachmentBytes: Map<String, ByteArray> = emptyMap(),
    ): BridgeRequestHandler = buildHandlerFixture(issues, attachments, attachmentBytes).handler

    /** Zelfde als [minimalRequestHandler], maar geeft ook de fakes terug om side-effects te asserten. */
    fun minimalRequestHandlerWithFakes(
        issues: List<TrackerIssue>? = emptyList(),
        attachments: List<TrackerAttachment> = emptyList(),
        attachmentBytes: Map<String, ByteArray> = emptyMap(),
    ): HandlerFixture = buildHandlerFixture(issues, attachments, attachmentBytes)

    fun minimalDashboardService(issues: List<TrackerIssue>? = emptyList()): FactoryDashboardService =
        buildFixture(FakeYouTrackApi(issues)).service

    class HandlerFixture(val handler: BridgeRequestHandler, val tracker: FakeYouTrackApi, val orchestrator: FakeOrchestratorApi)

    private fun buildHandlerFixture(
        issues: List<TrackerIssue>?,
        attachments: List<TrackerAttachment>,
        attachmentBytes: Map<String, ByteArray>,
    ): HandlerFixture {
        val fixture = buildFixture(FakeYouTrackApi(issues, attachments, attachmentBytes))
        val nightlyScheduler = NightlyScheduler(
            fixture.nightlySettingsRepository,
            fixture.nightlyRunRepository,
            fixture.nightlyRunJobRepository,
            NightlyTime(),
            FakeNightlyGateway,
        )
        val handler = BridgeRequestHandler(fixture.service, fixture.operations, nightlyScheduler, FactoryProcessService(), fixture.tracker)
        return HandlerFixture(handler, fixture.tracker, fixture.orchestrator)
    }

    private class Fixture(
        val service: FactoryDashboardService,
        val operations: FactoryOperationsService,
        val tracker: FakeYouTrackApi,
        val orchestrator: FakeOrchestratorApi,
        val nightlySettingsRepository: NightlySettingsRepository,
        val nightlyRunRepository: NightlyRunRepository,
        val nightlyRunJobRepository: NightlyRunJobRepository,
    )

    private fun buildFixture(tracker: FakeYouTrackApi): Fixture {
        val secrets = fakeSecrets()
        val stubJdbc = StubJdbcTemplate()
        val repository = FactoryDashboardRepository(stubJdbc, secrets)
        val orchestrator = FakeOrchestratorApi()
        val operations = FactoryOperationsService(
            issueTrackerClient = tracker,
            orchestratorApi = orchestrator,
            repository = repository,
            previewApi = FakePreviewApi(),
        )
        val nightlySettingsRepository = NightlySettingsRepository(stubJdbc, secrets)
        val nightlyRunRepository = NightlyRunRepository(stubJdbc, secrets)
        val nightlyRunJobRepository = NightlyRunJobRepository(stubJdbc, secrets)
        val service = FactoryDashboardService(
            issueTrackerClient = tracker,
            orchestratorApi = orchestrator,
            repository = repository,
            factorySecrets = secrets,
            operations = operations,
            projectRepoResolver = ProjectRepoResolver(emptyMap()),
            versionService = FactoryVersionService(),
            nightlySettingsRepository = nightlySettingsRepository,
            nightlyRunRepository = nightlyRunRepository,
            nightlyRunJobRepository = nightlyRunJobRepository,
            nightlyJobsReader = NightlyJobsReader(),
            deployClient = ProjectDeployClient(),
            workspaceLauncher = WorkspaceDesktopLauncher(),
            gitHubReleaseClient = GitHubReleaseClient(secrets),
        )
        return Fixture(service, operations, tracker, orchestrator, nightlySettingsRepository, nightlyRunRepository, nightlyRunJobRepository)
    }

    fun issue(key: String) = TrackerIssue(
        key = key,
        summary = "Test-story $key",
        status = "open",
        fields = TrackerIssueFields(
            targetRepo = null,
            aiPhase = null,
            aiLevel = null,
            aiTokenBudget = null,
            aiTokensUsed = null,
            agentStartedAt = null,
            paused = false,
            error = null,
        ),
        comments = emptyList(),
    )

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

    private class StubJdbcTemplate : JdbcTemplate()

    /** Als [issues] null is, gooit findWorkIssues een fout — om het soft-fail-pad te testen. */
    internal class FakeYouTrackApi(
        private val issues: List<TrackerIssue>?,
        private val attachments: List<TrackerAttachment> = emptyList(),
        private val attachmentBytes: Map<String, ByteArray> = emptyMap(),
    ) : YouTrackApi {
        var lastFieldUpdate: Pair<String, TrackerFieldUpdate>? = null
        var lastComment: Pair<String, String>? = null

        override fun findWorkIssues(maxResults: Int): List<TrackerIssue> =
            issues ?: error("YouTrack niet bereikbaar (test)")

        override fun listIssueAttachments(issueKey: String): List<TrackerAttachment> = attachments

        override fun downloadAttachmentBytes(attachment: TrackerAttachment): ByteArray? = attachmentBytes[attachment.id]

        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
            lastFieldUpdate = issueKey to update
        }

        override fun postComment(issueKey: String, message: String): TrackerComment {
            lastComment = issueKey to message
            return TrackerComment(id = "c-1", authorAccountId = null, authorDisplayName = "test", body = message, created = null)
        }

        override fun getIssue(issueKey: String): TrackerIssue = error("ongebruikt: getIssue")
        override fun transitionIssue(issueKey: String, statusName: String) = error("ongebruikt: transitionIssue")
        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment =
            error("ongebruikt: postAgentComment")
    }

    internal class FakeOrchestratorApi : OrchestratorApi {
        var lastCommand: Triple<String, FactoryCommand, String?>? = null

        override fun pollOnce(projectKey: String) = OrchestratorPollResult(emptyList())
        override fun processIssue(issue: TrackerIssue) = IssueProcessResult.Skipped(issue.key, "test")
        override fun queueCommand(storyKey: String, command: FactoryCommand, reason: String?) {
            lastCommand = Triple(storyKey, command, reason)
        }
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
