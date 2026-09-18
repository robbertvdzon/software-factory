package nl.vdzon.softwarefactory.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
import nl.vdzon.softwarefactory.dashboard.services.DashboardCommandService
import nl.vdzon.softwarefactory.dashboard.services.DashboardEventBus
import nl.vdzon.softwarefactory.dashboard.services.DashboardQueryService
import nl.vdzon.softwarefactory.dashboard.services.FactoryOperationsService
import nl.vdzon.softwarefactory.dashboard.services.FactoryVersionService
import nl.vdzon.softwarefactory.dashboard.services.GitHubActionsClient
import nl.vdzon.softwarefactory.dashboard.services.GitHubReleaseClient
import nl.vdzon.softwarefactory.dashboard.services.ProjectDeployClient
import nl.vdzon.softwarefactory.dashboard.services.RecentCommitsPoller
import nl.vdzon.softwarefactory.dashboard.repositories.FactoryDashboardRepository
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import nl.vdzon.softwarefactory.knowledge.models.AgentKnowledgeEntry
import nl.vdzon.softwarefactory.knowledge.models.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.maintenance.CleanupRunGuard
import nl.vdzon.softwarefactory.maintenance.types.CleanupRunStatus
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.pipeline.DeployTargetStatusApi
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.runtime.CleanupRunNowApi
import nl.vdzon.softwarefactory.runtime.models.CleanupRunNowOutcome
import nl.vdzon.softwarefactory.runtime.repositories.JdbcAgentEventRepository
import nl.vdzon.softwarefactory.runtime.services.AgentLogService
import nl.vdzon.softwarefactory.telegram.InteractiveAssistantClient
import nl.vdzon.softwarefactory.telegram.clients.TelegramClient
import nl.vdzon.softwarefactory.telegram.models.AssistantInputFile
import nl.vdzon.softwarefactory.telegram.models.AssistantReply
import nl.vdzon.softwarefactory.telegram.repositories.TelegramThreadStore
import nl.vdzon.softwarefactory.telegram.services.TelegramAssistantService
import nl.vdzon.softwarefactory.testsupport.InMemoryStoryRunRepository
import nl.vdzon.softwarefactory.tracker.TrackerApi
import nl.vdzon.softwarefactory.web.controllers.DashboardApiErrorHandler
import nl.vdzon.softwarefactory.web.controllers.DashboardCommandController
import nl.vdzon.softwarefactory.web.controllers.DashboardQueryController
import nl.vdzon.softwarefactory.web.controllers.DashboardStatusController
import nl.vdzon.softwarefactory.web.services.DashboardAuthInterceptor
import nl.vdzon.softwarefactory.web.services.DashboardAuthService
import nl.vdzon.softwarefactory.web.services.GoogleIdTokenVerifier
import nl.vdzon.softwarefactory.web.services.GoogleIdentity
import nl.vdzon.softwarefactory.web.services.StoryAttachmentStore
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Clock

/**
 * Gedeelde test-wiring voor de dashboard-API: een minimale maar echte [DashboardQueryService],
 * [DashboardCommandService] en [FactoryOperationsService] op handgeschreven fakes, achter de echte
 * controllers, de auth-interceptor en de foutvertaling. Tests praten via [Harness] over HTTP.
 */
internal object DashboardApiFixtures {
    const val USER = "robbert@vdzon.com"

    /** Gedeelde no-op fake — find leeg, upsert/delete niet ondersteund (deze tests raken knowledge niet). */
    object NoopKnowledgeApi : KnowledgeApi {
        override fun find(targetRepo: String, role: String) = emptyList<AgentKnowledgeEntry>()
        override fun upsert(request: AgentKnowledgeUpdateRequest) = throw UnsupportedOperationException()
        override fun delete(targetRepo: String, role: String, category: String, key: String) = false
    }

    class Harness(
        val mvc: MockMvc,
        val token: String,
        val tracker: FakeTrackerApi,
        val orchestrator: FakeOrchestratorApi,
        val cleanupRunNow: FakeCleanupRunNowApi,
        val cleanupGuard: CleanupRunGuard,
        val attachmentStore: StoryAttachmentStore,
        val statusController: DashboardStatusController,
        val eventBus: DashboardEventBus,
    ) {
        private val mapper = jacksonObjectMapper()

        fun get(path: String, authenticated: Boolean = true): MvcResult =
            mvc.perform(MockMvcRequestBuilders.get(path).apply { if (authenticated) header("Authorization", "Bearer $token") }).andReturn()

        /** GET met sessietoken; asserteert 200 en geeft de JSON-body terug. */
        fun getJson(path: String): JsonNode {
            val result = mvc.perform(MockMvcRequestBuilders.get(path).header("Authorization", "Bearer $token"))
                .andExpect(status().isOk).andReturn()
            return mapper.readTree(result.response.contentAsString)
        }

        fun post(path: String, body: String? = null, authenticated: Boolean = true): MvcResult =
            mvc.perform(
                MockMvcRequestBuilders.post(path).apply {
                    if (authenticated) header("Authorization", "Bearer $token")
                    if (body != null) contentType(MediaType.APPLICATION_JSON).content(body)
                },
            ).andReturn()

        /** POST met sessietoken; asserteert 200 en geeft de JSON-body terug. */
        fun postJson(path: String, body: String? = null): JsonNode {
            val result = post(path, body)
            check(result.response.status == 200) { "Verwachtte 200 op $path, kreeg ${result.response.status}: ${result.response.contentAsString}" }
            return mapper.readTree(result.response.contentAsString)
        }

        fun json(result: MvcResult): JsonNode = mapper.readTree(result.response.contentAsString)
    }

    fun harness(
        issues: List<TrackerIssue>? = emptyList(),
        attachments: List<TrackerAttachment> = emptyList(),
        attachmentBytes: Map<String, ByteArray> = emptyMap(),
    ): Harness {
        val tracker = FakeTrackerApi(issues, attachments, attachmentBytes)
        val fixture = buildFixture(tracker)
        val secrets = fakeSecrets()
        val authService = DashboardAuthService(secrets, GoogleIdTokenVerifier { GoogleIdentity(USER, emailVerified = true) })
        val token = authService.loginWithGoogle("stub-id-token").token
        val eventBus = DashboardEventBus()
        val statusController = DashboardStatusController(FactoryVersionService(), eventBus)
        val mvc = MockMvcBuilders.standaloneSetup(
            DashboardQueryController(fixture.service, tracker, minimalAssistantService()),
            DashboardCommandController(fixture.commands, fixture.operations),
            statusController,
        )
            .addMappedInterceptors(arrayOf("/api/v1/**"), DashboardAuthInterceptor(authService))
            .setControllerAdvice(DashboardApiErrorHandler())
            .build()
        return Harness(
            mvc, token, tracker, fixture.orchestrator, fixture.cleanupRunNow, fixture.cleanupGuard,
            StoryAttachmentStore(fixture.service, tracker), statusController, eventBus,
        )
    }

    fun minimalDashboardService(issues: List<TrackerIssue>? = emptyList()): DashboardQueryService =
        buildFixture(FakeTrackerApi(issues)).service

    /** Vangt op welke opruimronde de API aanvraagt; de echte route is elders gedekt. */
    class FakeCleanupRunNowApi(private val status: CleanupRunStatus = CleanupRunStatus.STARTED) : CleanupRunNowApi {
        val requestedKinds = mutableListOf<String>()

        override fun runNow(kind: String): CleanupRunNowOutcome {
            requestedKinds += kind
            return CleanupRunNowOutcome(status, mapOf(kind to status))
        }
    }

    private class Fixture(
        val service: DashboardQueryService,
        val commands: DashboardCommandService,
        val operations: FactoryOperationsService,
        val orchestrator: FakeOrchestratorApi,
        val cleanupRunNow: FakeCleanupRunNowApi,
        val cleanupGuard: CleanupRunGuard,
    )

    private fun buildFixture(tracker: FakeTrackerApi): Fixture {
        val secrets = fakeSecrets()
        val stubJdbc = StubJdbcTemplate()
        val cleanupGuard = CleanupRunGuard.inMemory()
        val repository = FactoryDashboardRepository(stubJdbc, secrets)
        val orchestrator = FakeOrchestratorApi()
        val operations = FactoryOperationsService(
            issueTrackerClient = tracker,
            orchestratorApi = orchestrator,
            repository = repository,
            previewApi = FakePreviewApi(),
            testDecisions = org.mockito.Mockito.mock(nl.vdzon.softwarefactory.dashboard.services.TestDecisionService::class.java),
        )
        val projectResolver = ProjectConfiguration(emptyMap())
        val deployClient = ProjectDeployClient()
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
            maintenanceCleanupRunRepository = nl.vdzon.softwarefactory.maintenance.repositories.MaintenanceCleanupRunRepository(stubJdbc, secrets),
            cleanupRunGuard = cleanupGuard,
            issueAttachments = tracker,
        )
        val cleanupRunNow = FakeCleanupRunNowApi()
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
            orchestrator, deployClient, repository,
            InMemoryStoryRunRepository(), NoopKnowledgeApi,
            Clock.fixed(java.time.Instant.parse("2026-01-01T10:00:00Z"), java.time.ZoneOffset.UTC),
            auditScheduler,
            auditProjectSettingsRepository,
            auditSettingsRepository,
            cleanupRunNow,
        )
        return Fixture(service, commands, operations, orchestrator, cleanupRunNow, cleanupGuard)
    }

    fun issue(key: String) = TrackerIssue(
        key = key,
        summary = "Test-story $key",
        status = "open",
        fields = TrackerIssueFields(
            targetRepo = null,
            aiPhase = null,
            aiTokenBudget = null,
            aiTokensUsed = null,
            agentStartedAt = null,
            paused = false,
            error = null,
        ),
        comments = emptyList(),
    )

    fun fakeSecrets(
        allowedEmails: Set<String> = setOf(USER),
        dashboardRememberSecret: String? = "test-remember-secret",
    ): FactorySecrets =
        FactorySecrets(
            trackerProjects = emptyList(),
            githubToken = "fake",
            factoryDatabaseUrl = "jdbc:fake",
            factoryDatabaseSchema = "fake",
            kubeconfig = "fake",
            loadedFrom = "fake",
            allowedEmails = allowedEmails,
            dashboardRememberSecret = dashboardRememberSecret,
            productFactoryToken = "integration-secret",
        )

    /** Minimale, echte [TelegramAssistantService] (geen mocks) voor `/api/v1/assistant/status`. */
    private fun minimalAssistantService(): TelegramAssistantService {
        val secrets = fakeSecrets()
        val resolver = ProjectConfiguration(emptyMap())
        val threadStore = object : TelegramThreadStore {
            override fun sessionFor(chatId: String, messageId: Long): String? = null
            override fun map(chatId: String, messageId: Long, sessionId: String) = Unit
            override fun activeRootSession(chatId: String): String? = null
            override fun setActiveRootSession(chatId: String, sessionId: String) = Unit
        }
        val assistant = object : InteractiveAssistantClient {
            override val enabled = false
            override fun ask(
                chatId: String,
                projectKey: String?,
                sessionId: String,
                isResume: Boolean,
                systemPrompt: String,
                userMessage: String,
                inputFile: AssistantInputFile?,
                timeoutSecondsOverride: Long?,
            ) = AssistantReply("uit", true, sessionId, 0.0)
            override fun stop(sessionId: String) = false
        }
        return TelegramAssistantService(assistant, threadStore, TelegramClient(secrets), resolver, NoopKnowledgeApi)
    }

    private class StubJdbcTemplate : JdbcTemplate()

    /** Als [issues] null is, gooit findWorkIssues een fout — om het soft-fail-pad te testen. */
    internal class FakeTrackerApi(
        private val issues: List<TrackerIssue>?,
        attachments: List<TrackerAttachment> = emptyList(),
        attachmentBytes: Map<String, ByteArray> = emptyMap(),
    ) : TrackerApi {
        private val attachments = attachments.toMutableList()
        private val attachmentBytes = attachmentBytes.toMutableMap()
        var lastFieldUpdate: Pair<String, TrackerFieldUpdate>? = null
        val fieldUpdates = mutableListOf<Pair<String, TrackerFieldUpdate>>()
        var lastComment: Pair<String, String>? = null
        var lastDescription: Pair<String, String>? = null
        var lastDescriptionSummary: Pair<String, String>? = null
        var lastShortDescriptionSummary: Pair<String, String>? = null
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

        override fun findStoryKeysWithErroredSubtasks(): Set<String> =
            (issues ?: error("tracker niet bereikbaar (test)"))
                .filter { it.parentKey != null && !it.fields.error.isNullOrBlank() }
                .mapNotNull { it.parentKey }
                .toSet()

        override fun listIssueAttachments(issueKey: String): List<TrackerAttachment> = attachments

        override fun downloadAttachmentBytes(attachment: TrackerAttachment): ByteArray? = attachmentBytes[attachment.id]

        override fun uploadIssueAttachment(issueKey: String, name: String, mimeType: String, bytes: ByteArray): TrackerAttachment {
            val attachment = TrackerAttachment(
                id = "uploaded-${attachments.size + 1}",
                name = name,
                url = null,
                mimeType = mimeType,
                size = bytes.size.toLong(),
                created = 1L,
            )
            attachments += attachment
            attachmentBytes[attachment.id] = bytes
            return attachment
        }

        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
            lastFieldUpdate = issueKey to update
            fieldUpdates += issueKey to update
        }

        fun writtenValues(field: nl.vdzon.softwarefactory.core.TrackerField): List<Any?> =
            fieldUpdates.filter { it.second.values.containsKey(field) }.map { it.second.values[field] }

        /** SF-1959 — laatst meegegeven hotfix-vlag, zodat story-create erop kan asserteren. */
        var lastCreateStoryHotfix: Boolean? = null
        var lastCreateStoryNotificationEvents: Set<nl.vdzon.softwarefactory.core.contracts.NotificationEvent>? = null

        override fun createStory(
            projectKey: String,
            title: String,
            description: String?,
            repo: String?,
            aiSupplier: String?,
            aiModel: String?,
            startPhase: StoryPhase?,
            questionsAllowed: Boolean,
            approvalMode: String,
            notificationEvents: Set<nl.vdzon.softwarefactory.core.contracts.NotificationEvent>,
            hotfix: Boolean,
        ): TrackerIssue {
            lastCreateStoryHotfix = hotfix
            lastCreateStoryNotificationEvents = notificationEvents
            return issue("$projectKey-1").copy(
                summary = title,
                description = description,
                fields = issue("$projectKey-1").fields.copy(
                    repo = repo,
                    aiSupplier = aiSupplier,
                    aiModel = aiModel,
                    storyPhase = startPhase?.trackerValue,
                    questionsAllowed = questionsAllowed,
                    approvalMode = approvalMode,
                    notificationEvents = notificationEvents,
                    hotfix = hotfix,
                ),
            )
        }

        override fun updateIssueDescription(issueKey: String, description: String) {
            lastDescription = issueKey to description
        }

        override fun updateIssueDescriptionSummary(issueKey: String, descriptionSummary: String) {
            lastDescriptionSummary = issueKey to descriptionSummary
        }

        override fun updateIssueShortDescriptionSummary(issueKey: String, shortDescriptionSummary: String) {
            lastShortDescriptionSummary = issueKey to shortDescriptionSummary
        }

        override fun postComment(issueKey: String, message: String): TrackerComment {
            lastComment = issueKey to message
            return TrackerComment(id = "c-1", authorAccountId = null, authorDisplayName = "test", body = message, created = null)
        }

        override fun getIssue(issueKey: String): TrackerIssue =
            (issues ?: error("tracker niet bereikbaar (test)")).first { it.key == issueKey }
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
