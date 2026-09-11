package nl.vdzon.softwarefactory.dashboard.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.audit.models.AuditDispatchHandle
import nl.vdzon.softwarefactory.audit.services.AuditJob
import nl.vdzon.softwarefactory.audit.services.AuditJobDetail
import nl.vdzon.softwarefactory.audit.repositories.AuditQuestionRepository
import nl.vdzon.softwarefactory.audit.repositories.AuditReportRecord
import nl.vdzon.softwarefactory.audit.repositories.AuditReportRepository
import nl.vdzon.softwarefactory.audit.services.AuditJobsReader
import nl.vdzon.softwarefactory.audit.types.AuditOutcomeStatus
import nl.vdzon.softwarefactory.config.ProjectDashboardSettings
import nl.vdzon.softwarefactory.core.contracts.AgentRunRepository
import nl.vdzon.softwarefactory.core.contracts.AgentDispatchRequest
import nl.vdzon.softwarefactory.core.contracts.AgentDispatchResult
import nl.vdzon.softwarefactory.core.contracts.AgentRuntime
import nl.vdzon.softwarefactory.core.contracts.ApprovalMode
import nl.vdzon.softwarefactory.core.contracts.NotificationEvent
import nl.vdzon.softwarefactory.core.contracts.StoryPhase
import nl.vdzon.softwarefactory.core.contracts.StoryRunRepository
import nl.vdzon.softwarefactory.core.contracts.StoryRunRecord
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.contracts.TrackerIssueFields
import nl.vdzon.softwarefactory.core.contracts.TrackerProject
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import nl.vdzon.softwarefactory.telegram.AuditQuestionNotifier
import nl.vdzon.softwarefactory.tracker.TrackerCapabilities
import nl.vdzon.softwarefactory.runtime.v2.AgentRuntimeV2HttpClient
import nl.vdzon.softwarefactory.runtime.v2.RuntimeExecution
import nl.vdzon.softwarefactory.runtime.v2.RuntimeExecutionMode
import nl.vdzon.softwarefactory.runtime.v2.RuntimeJobKind
import nl.vdzon.softwarefactory.runtime.v2.RuntimeJobResultView
import nl.vdzon.softwarefactory.runtime.v2.RuntimeJobStatus
import nl.vdzon.softwarefactory.runtime.v2.RuntimeJobView
import nl.vdzon.softwarefactory.runtime.v2.RuntimePublicationStatus
import nl.vdzon.softwarefactory.runtime.v2.RuntimeRepositoryResult
import nl.vdzon.softwarefactory.runtime.v2.RuntimeTaskType
import nl.vdzon.softwarefactory.runtime.v2.RuntimeUsageSummary
import nl.vdzon.softwarefactory.github.GitHubApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.time.OffsetDateTime
import java.util.UUID

class AuditGatewayAdapterTest {

    @Test
    fun `audit dispatch gebruikt Runtime checkout op main en nooit een lokale workspace`() {
        val reader = mock(AuditJobsReader::class.java)
        doReturn(
            AuditJobDetail(
                AuditJob("softwarefactory", "architecture", "Architecture", true, null, null, null),
                "Controleer de architectuur.",
            ),
        ).`when`(reader).readJob("https://github.com/example/softwarefactory", "softwarefactory", "architecture")
        val projects = mock(ProjectDashboardSettings::class.java)
        doReturn("https://github.com/example/softwarefactory").`when`(projects).repoFor("softwarefactory")
        val storyRuns = mock(StoryRunRepository::class.java)
        doReturn(
            StoryRunRecord(42, "AUDIT:softwarefactory:architecture", "https://github.com/example/softwarefactory"),
        ).`when`(storyRuns).openOrCreate(
            "AUDIT:softwarefactory:architecture",
            "https://github.com/example/softwarefactory",
        )
        val runtime = CapturingRuntime()
        val adapter = AuditGatewayAdapter(
            auditJobsReader = reader,
            projects = projects,
            auditReportRepository = mock(AuditReportRepository::class.java),
            auditQuestionRepository = mock(AuditQuestionRepository::class.java),
            agentRuntime = runtime,
            runtimeClient = mock(AgentRuntimeV2HttpClient::class.java),
            github = mock(GitHubApi::class.java),
            agentRunRepository = mock(AgentRunRepository::class.java),
            storyRunRepository = storyRuns,
            tracker = mock(TrackerCapabilities::class.java),
            knowledgeApi = mock(KnowledgeApi::class.java),
            auditQuestionNotifier = mock(AuditQuestionNotifier::class.java),
            objectMapper = jacksonObjectMapper(),
        )

        val handle = adapter.startAudit("softwarefactory", "architecture")

        assertEquals(null, handle.workspacePath)
        assertEquals("main", runtime.request?.baseBranch)
        assertEquals("softwarefactory", runtime.request?.projectKey)
        assertEquals(null, runtime.request?.workspacePath)
    }

    @Test
    fun `audit proposal is created atomically with the exact audit notification events`() {
        val mapper = jacksonObjectMapper()
        val jobId = UUID.randomUUID()
        val completedAt = OffsetDateTime.parse("2026-08-05T12:00:00Z")
        val tracker = mock(TrackerCapabilities::class.java)
        val createdStory = trackerIssue("SF-99", "[Audit] Maak grenzen expliciet")
        doReturn(listOf(TrackerProject("SF", "SF", "Software Factory")))
            .`when`(tracker).ensureConfiguredProjects()
        doReturn(createdStory).`when`(tracker).createStory(
            matching("SF"),
            matching("[Audit] Maak grenzen expliciet"),
            matching("Voeg een architectuurtest toe."),
            matching("softwarefactory"),
            matching("claude"),
            isNull(),
            matching(StoryPhase.START_NEXT),
            matching(true),
            matching(ApprovalMode.AUTOMATIC.trackerValue),
            matching(NotificationEvent.AUDIT),
            matching(false),
        )
        val projects = mock(ProjectDashboardSettings::class.java)
        doReturn("https://github.com/example/softwarefactory").`when`(projects).repoFor("softwarefactory")
        val auditReports = mock(AuditReportRepository::class.java)
        val storedReport = AuditReportRecord(
            id = 7,
            project = "softwarefactory",
            auditType = "architecture",
            generatedAt = OffsetDateTime.parse("2026-08-05T12:00:00Z"),
            content = "# Rapport",
            score = null,
            scoreLabel = null,
            proposedStoryKey = "SF-99",
            status = "done",
            error = null,
            durationMs = 123,
        )
        doReturn(storedReport).`when`(auditReports).add(
            matching("softwarefactory"),
            matching("architecture"),
            matching("# Rapport"),
            isNull(),
            isNull(),
            matching("SF-99"),
            matching("done"),
            isNull(),
            matching(123L),
        )
        val runtime = mock(AgentRuntime::class.java)
        val runtimeClient = mock(AgentRuntimeV2HttpClient::class.java)
        val execution = RuntimeExecution("codex", "gpt-5.6-sol", RuntimeExecutionMode.SUBSCRIPTION)
        doReturn(
            RuntimeJobView(
                id = jobId,
                tenantId = "software-factory",
                idempotencyKey = "audit-1",
                jobKind = RuntimeJobKind.APPLICATION_WORK,
                taskType = RuntimeTaskType.REPOSITORY_AGENT,
                execution = execution,
                status = RuntimeJobStatus.SUCCEEDED,
                phase = "COMPLETED",
                attemptCount = 1,
                maxAttempts = 3,
                createdAt = completedAt.minusNanos(123_000_000),
                updatedAt = completedAt,
                completedAt = completedAt,
            ),
        ).`when`(runtimeClient).getJob(jobId)
        doReturn(
            RuntimeJobResultView(
                jobId = jobId,
                result = mapper.readTree(
                    """{"phase":"audited","outcome":"audited","summaryText":"klaar","auditReportMarkdown":"# Rapport","proposedStoryTitle":"Maak grenzen expliciet","proposedStoryDescription":"Voeg een architectuurtest toe."}""",
                ),
                repositoryResult = RuntimeRepositoryResult(
                    alias = "software-factory",
                    branch = "main",
                    checkoutCommitSha = "a".repeat(40),
                    publicationStatus = RuntimePublicationStatus.NONE,
                ),
                usageSummary = RuntimeUsageSummary(1, "EXACT"),
                completedAt = completedAt,
            ),
        ).`when`(runtimeClient).getResult(jobId)
        val storyRuns = mock(StoryRunRepository::class.java)
        doReturn(StoryRunRecord(42, "AUDIT:softwarefactory:architecture", "https://github.com/example/softwarefactory"))
            .`when`(storyRuns).get(42)
        doReturn("software-factory").`when`(projects)
            .runtimeAliasFor("https://github.com/example/softwarefactory")
        val github = mock(GitHubApi::class.java)
        doReturn("a".repeat(40)).`when`(github)
            .latestCommitSha("https://github.com/example/softwarefactory", "main")
        val adapter = AuditGatewayAdapter(
            auditJobsReader = mock(AuditJobsReader::class.java),
            projects = projects,
            auditReportRepository = auditReports,
            auditQuestionRepository = mock(AuditQuestionRepository::class.java),
            agentRuntime = runtime,
            runtimeClient = runtimeClient,
            github = github,
            agentRunRepository = mock(AgentRunRepository::class.java),
            storyRunRepository = storyRuns,
            tracker = tracker,
            knowledgeApi = mock(KnowledgeApi::class.java),
            auditQuestionNotifier = mock(AuditQuestionNotifier::class.java),
            objectMapper = mapper,
        )

        val outcome = adapter.auditOutcome(AuditDispatchHandle(jobId.toString(), null, 42))

        assertEquals(AuditOutcomeStatus.DONE, outcome.status)
        assertEquals("SF-99", outcome.report?.proposedStoryKey)
        verify(tracker).createStory(
            matching("SF"),
            matching("[Audit] Maak grenzen expliciet"),
            matching("Voeg een architectuurtest toe."),
            matching("softwarefactory"),
            matching("claude"),
            isNull(),
            matching(StoryPhase.START_NEXT),
            matching(true),
            matching(ApprovalMode.AUTOMATIC.trackerValue),
            matching(NotificationEvent.AUDIT),
            matching(false),
        )
    }

    /** Mockito's [eq] returns null while registering a matcher; Kotlin non-null parameters reject that. */
    private fun <T> matching(value: T): T {
        eq(value)
        return value
    }

    private fun trackerIssue(key: String, summary: String) = TrackerIssue(
        key = key,
        summary = summary,
        status = "Open",
        fields = TrackerIssueFields(
            targetRepo = null,
            aiPhase = null,
            aiLevel = null,
            aiTokenBudget = null,
            aiTokensUsed = null,
            agentStartedAt = null,
            paused = false,
            error = null,
            storyPhase = StoryPhase.START_NEXT.trackerValue,
            notificationEvents = NotificationEvent.AUDIT,
        ),
        comments = emptyList(),
    )

    private class CapturingRuntime : AgentRuntime {
        var request: AgentDispatchRequest? = null

        override fun dispatch(request: AgentDispatchRequest): AgentDispatchResult {
            this.request = request
            return AgentDispatchResult(UUID.randomUUID().toString(), OffsetDateTime.now())
        }

        override fun isContainerRunning(containerName: String) = false
        override fun isAgentRunning(storyKey: String, role: nl.vdzon.softwarefactory.core.AgentRole) = false
        override fun isAnyAgentRunningForStory(storyKey: String) = false
        override fun runningCount(role: nl.vdzon.softwarefactory.core.AgentRole?) = 0
        override fun killForStory(storyKey: String) = 0
    }
}
