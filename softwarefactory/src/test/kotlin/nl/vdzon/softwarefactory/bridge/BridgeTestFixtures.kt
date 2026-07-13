package nl.vdzon.softwarefactory.bridge

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.DeploymentStatusProbe
import nl.vdzon.softwarefactory.core.contracts.FactoryCommand
import nl.vdzon.softwarefactory.core.contracts.IssueProcessResult
import nl.vdzon.softwarefactory.core.contracts.OrchestratorPollResult
import nl.vdzon.softwarefactory.core.contracts.TrackerAttachment
import nl.vdzon.softwarefactory.core.contracts.TrackerComment
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.contracts.TrackerIssueFields
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.git.GitProcessResult
import nl.vdzon.softwarefactory.knowledge.models.AgentKnowledgeEntry
import nl.vdzon.softwarefactory.knowledge.models.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import nl.vdzon.softwarefactory.nightly.NightlyGateway
import nl.vdzon.softwarefactory.nightly.services.NightlyJob
import nl.vdzon.softwarefactory.nightly.services.NightlyJobsReader
import nl.vdzon.softwarefactory.nightly.repositories.NightlyRunJobRepository
import nl.vdzon.softwarefactory.nightly.repositories.NightlyRunRepository
import nl.vdzon.softwarefactory.nightly.services.NightlyScheduler
import nl.vdzon.softwarefactory.nightly.repositories.NightlySettingsRepository
import nl.vdzon.softwarefactory.nightly.models.NightlyStoryOutcome
import nl.vdzon.softwarefactory.nightly.services.NightlyTime
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.telegram.services.AssistantWorkspaceService
import nl.vdzon.softwarefactory.telegram.clients.ClaudeAssistantClient
import nl.vdzon.softwarefactory.telegram.services.TelegramAssistantService
import nl.vdzon.softwarefactory.telegram.clients.TelegramClient
import nl.vdzon.softwarefactory.telegram.repositories.TelegramThreadStore
import nl.vdzon.softwarefactory.dashboard.repositories.FactoryDashboardRepository
import nl.vdzon.softwarefactory.dashboard.services.DashboardQueryService
import nl.vdzon.softwarefactory.dashboard.services.DashboardCommandService
import nl.vdzon.softwarefactory.dashboard.services.FactoryOperationsService
import nl.vdzon.softwarefactory.dashboard.services.FactoryProcessService
import nl.vdzon.softwarefactory.dashboard.services.FactoryVersionService
import nl.vdzon.softwarefactory.dashboard.services.GitHubActionsClient
import nl.vdzon.softwarefactory.dashboard.services.GitHubReleaseClient
import nl.vdzon.softwarefactory.dashboard.services.ProjectDeployClient
import nl.vdzon.softwarefactory.dashboard.services.WorkspaceDesktopLauncher
import nl.vdzon.softwarefactory.tracker.TrackerApi
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Gedeelde test-wiring voor een minimale (maar echte) [DashboardQueryService]/
 * [FactoryOperationsService]; hergebruikt door [BridgeRequestHandlerTest] en [BridgeClientTest].
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

    fun minimalDashboardService(issues: List<TrackerIssue>? = emptyList()): DashboardQueryService =
        buildFixture(FakeTrackerApi(issues)).service

    class HandlerFixture(val handler: BridgeRequestHandler, val tracker: FakeTrackerApi, val orchestrator: FakeOrchestratorApi)

    private fun buildHandlerFixture(
        issues: List<TrackerIssue>?,
        attachments: List<TrackerAttachment>,
        attachmentBytes: Map<String, ByteArray>,
    ): HandlerFixture {
        val fixture = buildFixture(FakeTrackerApi(issues, attachments, attachmentBytes))
        val nightlyScheduler = NightlyScheduler(
            fixture.nightlySettingsRepository,
            fixture.nightlyRunRepository,
            fixture.nightlyRunJobRepository,
            NightlyTime(),
            FakeNightlyGateway,
        )
        val handler = BridgeRequestHandler(
            fixture.service,
            fixture.commands,
            fixture.operations,
            nightlyScheduler,
            FactoryProcessService(),
            fixture.tracker,
            minimalAssistantService(),
        )
        return HandlerFixture(handler, fixture.tracker, fixture.orchestrator)
    }

    private class Fixture(
        val service: DashboardQueryService,
        val commands: DashboardCommandService,
        val operations: FactoryOperationsService,
        val tracker: FakeTrackerApi,
        val orchestrator: FakeOrchestratorApi,
        val nightlySettingsRepository: NightlySettingsRepository,
        val nightlyRunRepository: NightlyRunRepository,
        val nightlyRunJobRepository: NightlyRunJobRepository,
    )

    private fun buildFixture(tracker: FakeTrackerApi): Fixture {
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
        val projectResolver = ProjectConfiguration(emptyMap())
        val materializer = nl.vdzon.softwarefactory.runtime.services.SubtaskPlanMaterializer(tracker, projectResolver)
        val deployClient = ProjectDeployClient()
        val workspaceLauncher = WorkspaceDesktopLauncher()
        val jobsReader = NightlyJobsReader()
        val service = DashboardQueryService(
            issueTrackerClient = tracker,
            orchestratorApi = orchestrator,
            repository = repository,
            factorySecrets = secrets,
            operations = operations,
            projectRepoResolver = projectResolver,
            versionService = FactoryVersionService(),
            nightlySettingsRepository = nightlySettingsRepository,
            nightlyRunRepository = nightlyRunRepository,
            nightlyRunJobRepository = nightlyRunJobRepository,
            nightlyJobsReader = jobsReader,
            deployClient = deployClient,
            workspaceLauncher = workspaceLauncher,
            gitHubReleaseClient = GitHubReleaseClient(secrets),
            gitHubActionsClient = GitHubActionsClient(secrets),
            deploymentStatusProbe = DeploymentStatusProbe { _, _ -> null },
            subtaskPlanMaterializer = materializer,
        )
        val commands = DashboardCommandService(
            tracker, secrets, projectResolver, jobsReader, materializer, nightlySettingsRepository,
            orchestrator, deployClient, repository, workspaceLauncher,
        )
        return Fixture(service, commands, operations, tracker, orchestrator, nightlySettingsRepository, nightlyRunRepository, nightlyRunJobRepository)
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
            trackerProjects = emptyList(),
            githubToken = "fake",
            factoryDatabaseUrl = "jdbc:fake",
            factoryDatabaseSchema = "fake",
            kubeconfig = "fake",
            aiCredentialsDir = "fake",
            aiOauthToken = null,
            loadedFrom = "fake",
        )

    /** Minimale, echte [TelegramAssistantService] (geen mocks) voor de `assistant.status`-operatie. */
    private fun minimalAssistantService(): TelegramAssistantService {
        val secrets = fakeSecrets()
        val resolver = ProjectConfiguration(emptyMap())
        val threadStore = object : TelegramThreadStore {
            override fun sessionFor(chatId: String, messageId: Long): String? = null
            override fun map(chatId: String, messageId: Long, sessionId: String) = Unit
            override fun activeRootSession(chatId: String): String? = null
            override fun setActiveRootSession(chatId: String, sessionId: String) = Unit
        }
        val gitApi = object : GitApi {
            override fun clone(repoUrl: String, targetDir: java.nio.file.Path, githubToken: String?) = Unit
            override fun checkoutBase(repoRoot: java.nio.file.Path, baseBranch: String, githubToken: String?) = Unit
            override fun checkoutStoryBranch(
                repoRoot: java.nio.file.Path,
                branchName: String,
                baseBranch: String,
                createIfMissing: Boolean,
                githubToken: String?,
            ) = Unit
            override fun commitAll(repoRoot: java.nio.file.Path, message: String, githubToken: String?): Boolean = false
            override fun push(repoRoot: java.nio.file.Path, branchName: String, githubToken: String?) = Unit
            override fun remoteBranchExists(repoRoot: java.nio.file.Path, branchName: String, githubToken: String?): Boolean = false
            override fun runCommand(
                command: List<String>,
                cwd: java.nio.file.Path?,
                env: Map<String, String>,
                timeoutSeconds: Long,
            ) = GitProcessResult(0, "", "")
            override fun repositorySlug(repoUrl: String): String? = null
        }
        val knowledgeApi = object : KnowledgeApi {
            override fun find(targetRepo: String, role: String) = emptyList<AgentKnowledgeEntry>()
            override fun upsert(request: AgentKnowledgeUpdateRequest) = throw UnsupportedOperationException()
        }
        return TelegramAssistantService(
            ClaudeAssistantClient(secrets),
            threadStore,
            TelegramClient(secrets),
            resolver,
            AssistantWorkspaceService(gitApi, secrets, resolver),
            knowledgeApi,
        )
    }

    private class StubJdbcTemplate : JdbcTemplate()

    /** Als [issues] null is, gooit findWorkIssues een fout — om het soft-fail-pad te testen. */
    internal class FakeTrackerApi(
        private val issues: List<TrackerIssue>?,
        private val attachments: List<TrackerAttachment> = emptyList(),
        private val attachmentBytes: Map<String, ByteArray> = emptyMap(),
    ) : TrackerApi {
        var lastFieldUpdate: Pair<String, TrackerFieldUpdate>? = null
        var lastComment: Pair<String, String>? = null
        var findWorkIssuesCalls: Int = 0
            private set

        override fun findWorkIssues(maxResults: Int, includeFinished: Boolean): List<TrackerIssue> {
            findWorkIssuesCalls++
            return issues ?: error("tracker niet bereikbaar (test)")
        }

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
