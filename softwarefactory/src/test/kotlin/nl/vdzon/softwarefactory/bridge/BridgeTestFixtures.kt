package nl.vdzon.softwarefactory.bridge

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.FactoryCommand
import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.core.OrchestratorPollResult
import nl.vdzon.softwarefactory.core.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerAttachment
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerIssueFields
import nl.vdzon.softwarefactory.nightly.NightlyJobsReader
import nl.vdzon.softwarefactory.nightly.NightlyRunJobRepository
import nl.vdzon.softwarefactory.nightly.NightlyRunRepository
import nl.vdzon.softwarefactory.nightly.NightlySettingsRepository
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.web.repositories.FactoryDashboardRepository
import nl.vdzon.softwarefactory.web.services.FactoryDashboardService
import nl.vdzon.softwarefactory.web.services.FactoryOperationsService
import nl.vdzon.softwarefactory.web.services.FactoryVersionService
import nl.vdzon.softwarefactory.web.services.ProjectDeployClient
import nl.vdzon.softwarefactory.web.services.WorkspaceDesktopLauncher
import nl.vdzon.softwarefactory.web.services.GitHubReleaseClient
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Gedeelde test-wiring voor een minimale (maar echte) [FactoryDashboardService], zelfde recept
 * als `DashboardAuthInterceptorTest`/`FactoryDashboardServiceTest` — hergebruikt door
 * [BridgeRequestHandlerTest] en [BridgeClientTest].
 */
internal object BridgeTestFixtures {

    fun minimalRequestHandler(
        issues: List<TrackerIssue>? = emptyList(),
        attachments: List<TrackerAttachment> = emptyList(),
        attachmentBytes: Map<String, ByteArray> = emptyMap(),
    ): BridgeRequestHandler {
        val tracker = FakeYouTrackApi(issues, attachments, attachmentBytes)
        val service = minimalDashboardService(tracker)
        return BridgeRequestHandler(service, tracker)
    }

    fun minimalDashboardService(issues: List<TrackerIssue>? = emptyList()): FactoryDashboardService =
        minimalDashboardService(FakeYouTrackApi(issues))

    private fun minimalDashboardService(tracker: FakeYouTrackApi): FactoryDashboardService {
        val secrets = fakeSecrets()
        val stubJdbc = StubJdbcTemplate()
        val repository = FactoryDashboardRepository(stubJdbc, secrets)
        val operations = FactoryOperationsService(
            issueTrackerClient = tracker,
            orchestratorApi = FakeOrchestratorApi(),
            repository = repository,
            previewApi = FakePreviewApi(),
        )
        return FactoryDashboardService(
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
            nightlyJobsReader = NightlyJobsReader(),
            deployClient = ProjectDeployClient(),
            workspaceLauncher = WorkspaceDesktopLauncher(),
            gitHubReleaseClient = GitHubReleaseClient(secrets),
        )
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
    private class FakeYouTrackApi(
        private val issues: List<TrackerIssue>?,
        private val attachments: List<TrackerAttachment> = emptyList(),
        private val attachmentBytes: Map<String, ByteArray> = emptyMap(),
    ) : YouTrackApi {
        override fun findWorkIssues(maxResults: Int): List<TrackerIssue> =
            issues ?: error("YouTrack niet bereikbaar (test)")

        override fun listIssueAttachments(issueKey: String): List<TrackerAttachment> = attachments

        override fun downloadAttachmentBytes(attachment: TrackerAttachment): ByteArray? = attachmentBytes[attachment.id]

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
}
