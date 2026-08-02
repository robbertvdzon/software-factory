package nl.vdzon.softwarefactory.bridge

import nl.vdzon.softwarefactory.bridge.services.BridgeRequestHandler
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.DeploymentStatusProbe
import nl.vdzon.softwarefactory.core.contracts.FactoryCommand
import nl.vdzon.softwarefactory.core.contracts.IssueProcessResult
import nl.vdzon.softwarefactory.core.contracts.OrchestratorPollResult
import nl.vdzon.softwarefactory.core.contracts.StoryPhase
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
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.pipeline.DeployTargetStatusApi
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.telegram.services.AssistantWorkspaceService
import nl.vdzon.softwarefactory.telegram.clients.ClaudeAssistantClient
import nl.vdzon.softwarefactory.telegram.services.TelegramAssistantService
import nl.vdzon.softwarefactory.telegram.clients.TelegramClient
import nl.vdzon.softwarefactory.telegram.repositories.TelegramThreadStore
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.dashboard.repositories.FactoryDashboardRepository
import nl.vdzon.softwarefactory.runtime.repositories.JdbcAgentEventRepository
import nl.vdzon.softwarefactory.runtime.services.AgentLogService
import nl.vdzon.softwarefactory.dashboard.services.DashboardQueryService
import nl.vdzon.softwarefactory.dashboard.services.DashboardCommandService
import nl.vdzon.softwarefactory.dashboard.services.FactoryOperationsService
import nl.vdzon.softwarefactory.dashboard.services.FactoryProcessService
import nl.vdzon.softwarefactory.dashboard.services.FactoryVersionService
import nl.vdzon.softwarefactory.dashboard.services.GitHubActionsClient
import nl.vdzon.softwarefactory.dashboard.services.GitHubReleaseClient
import nl.vdzon.softwarefactory.dashboard.services.RecentCommitsPoller
import nl.vdzon.softwarefactory.dashboard.services.ProjectDeployClient
import nl.vdzon.softwarefactory.dashboard.services.WorkspaceDesktopLauncher
import nl.vdzon.softwarefactory.testsupport.InMemoryStoryRunRepository
import nl.vdzon.softwarefactory.tracker.TrackerApi
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock

/**
 * Gedeelde test-wiring voor een minimale (maar echte) [DashboardQueryService]/
 * [FactoryOperationsService]; hergebruikt door [BridgeRequestHandlerTest] en [BridgeClientTest].
 */
internal object BridgeTestFixtures {

    /** Gedeelde no-op fake — find leeg, upsert/delete niet ondersteund (deze tests raken knowledge niet). */
    object NoopKnowledgeApi : KnowledgeApi {
        override fun find(targetRepo: String, role: String) = emptyList<AgentKnowledgeEntry>()
        override fun upsert(request: AgentKnowledgeUpdateRequest) = throw UnsupportedOperationException()
        override fun delete(targetRepo: String, role: String, category: String, key: String) = false
    }

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
        val handler = BridgeRequestHandler(
            fixture.service,
            fixture.commands,
            fixture.operations,
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
        val projectResolver = ProjectConfiguration(emptyMap())
        val deployClient = ProjectDeployClient()
        val workspaceLauncher = WorkspaceDesktopLauncher()
        val auditReportRepository = nl.vdzon.softwarefactory.audit.repositories.AuditReportRepository(stubJdbc, secrets)
        val auditGateway = nl.vdzon.softwarefactory.testsupport.FakeAuditGateway()
        val auditProjectSettingsRepository = nl.vdzon.softwarefactory.audit.repositories.AuditProjectSettingsRepository(stubJdbc, secrets)
        val auditSettingsRepository = nl.vdzon.softwarefactory.audit.repositories.AuditSettingsRepository(stubJdbc, secrets)
        val service = DashboardQueryService(
            issueTrackerClient = tracker,
            repository = repository,
            factorySecrets = secrets,
            operations = operations,
            projectRepoResolver = projectResolver,
            versionService = FactoryVersionService(),
            auditReportRepository = auditReportRepository,
            auditGateway = auditGateway,
            auditQuestionRepository = nl.vdzon.softwarefactory.audit.repositories.AuditQuestionRepository(stubJdbc, secrets),
            auditRunRepository = nl.vdzon.softwarefactory.audit.repositories.AuditRunRepository(stubJdbc, secrets),
            auditRunJobRepository = nl.vdzon.softwarefactory.audit.repositories.AuditRunJobRepository(stubJdbc, secrets),
            auditSettingsRepository = auditSettingsRepository,
            auditProjectSettingsRepository = auditProjectSettingsRepository,
            knowledgeApi = NoopKnowledgeApi,
            deployClient = deployClient,
            gitHubReleaseClient = GitHubReleaseClient(secrets),
            gitHubActionsClient = GitHubActionsClient(secrets),
            recentCommitsPoller = RecentCommitsPoller(projectResolver, GitHubActionsClient(secrets)),
            deploymentStatusProbe = DeploymentStatusProbe { _, _ -> null },
            agentLogApi = AgentLogService(JdbcAgentEventRepository(stubJdbc, secrets, jacksonObjectMapper()), jacksonObjectMapper()),
            deployTargetStatusApi = DeployTargetStatusApi { _, _ -> emptyList() },
        )
        val auditScheduler = nl.vdzon.softwarefactory.audit.services.AuditScheduler(
            auditSettingsRepository,
            auditProjectSettingsRepository,
            nl.vdzon.softwarefactory.audit.repositories.AuditRunRepository(stubJdbc, secrets),
            nl.vdzon.softwarefactory.audit.repositories.AuditRunJobRepository(stubJdbc, secrets),
            auditReportRepository,
            nl.vdzon.softwarefactory.audit.repositories.AuditQuestionRepository(stubJdbc, secrets),
            nl.vdzon.softwarefactory.config.time.FactoryTime(),
            auditGateway,
        )
        val commands = DashboardCommandService(
            tracker, secrets, projectResolver,
            orchestrator, deployClient, repository, workspaceLauncher,
            InMemoryStoryRunRepository(), NoopKnowledgeApi,
            Clock.fixed(java.time.Instant.parse("2026-01-01T10:00:00Z"), java.time.ZoneOffset.UTC),
            auditScheduler,
            auditProjectSettingsRepository,
            auditSettingsRepository,
        )
        return Fixture(service, commands, operations, tracker, orchestrator)
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
        val knowledgeApi = NoopKnowledgeApi
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
        val fieldUpdates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        var lastComment: Pair<String, String>? = null
        var lastDescription: Pair<String, String>? = null
        var findWorkIssuesCalls: Int = 0
            private set

        override fun findWorkIssues(maxResults: Int, includeFinished: Boolean): List<TrackerIssue> {
            findWorkIssuesCalls++
            return issues ?: error("tracker niet bereikbaar (test)")
        }

        // Zelfde bron als findWorkIssues; het Stories-overzicht gebruikt deze sinds de limiet eruit
        // ging. Subtaken eruit, zoals de echte query dat met `parent_key IS NULL` doet.
        override fun findAllStories(): List<TrackerIssue> =
            (issues ?: error("tracker niet bereikbaar (test)")).filter { it.parentKey == null }

        override fun findQuotaWaitingIssues(): List<TrackerIssue> =
            (issues ?: error("tracker niet bereikbaar (test)")).filter { it.fields.retryAfter != null }

        override fun listIssueAttachments(issueKey: String): List<TrackerAttachment> = attachments

        override fun downloadAttachmentBytes(attachment: TrackerAttachment): ByteArray? = attachmentBytes[attachment.id]

        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
            lastFieldUpdate = issueKey to update
            fieldUpdates += issueKey to update
        }

        fun writtenValues(field: nl.vdzon.softwarefactory.core.TrackerField): List<Any?> =
            fieldUpdates.filter { it.second.values.containsKey(field) }.map { it.second.values[field] }

        override fun createStory(
            projectKey: String,
            title: String,
            description: String?,
            repo: String?,
            aiSupplier: String?,
            aiModel: String?,
            startPhase: StoryPhase?,
            questionsAllowed: Boolean,
        ): TrackerIssue = issue("$projectKey-1").copy(
            summary = title,
            description = description,
            fields = issue("$projectKey-1").fields.copy(
                repo = repo,
                aiSupplier = aiSupplier,
                aiModel = aiModel,
                storyPhase = startPhase?.trackerValue,
                questionsAllowed = questionsAllowed,
            ),
        )

        override fun updateIssueDescription(issueKey: String, description: String) {
            lastDescription = issueKey to description
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

        override fun pollOnce() = OrchestratorPollResult(emptyList())
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

}
