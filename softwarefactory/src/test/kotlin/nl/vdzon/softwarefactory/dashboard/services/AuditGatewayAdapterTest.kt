package nl.vdzon.softwarefactory.dashboard.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.audit.models.AuditDispatchHandle
import nl.vdzon.softwarefactory.audit.repositories.AuditQuestionRepository
import nl.vdzon.softwarefactory.audit.repositories.AuditReportRecord
import nl.vdzon.softwarefactory.audit.repositories.AuditReportRepository
import nl.vdzon.softwarefactory.audit.services.AuditJobsReader
import nl.vdzon.softwarefactory.audit.types.AuditOutcomeStatus
import nl.vdzon.softwarefactory.config.ProjectDashboardSettings
import nl.vdzon.softwarefactory.contract.AgentResultFile
import nl.vdzon.softwarefactory.core.contracts.AgentRunRepository
import nl.vdzon.softwarefactory.core.contracts.AgentRuntime
import nl.vdzon.softwarefactory.core.contracts.ApprovalMode
import nl.vdzon.softwarefactory.core.contracts.NotificationEvent
import nl.vdzon.softwarefactory.core.contracts.StoryPhase
import nl.vdzon.softwarefactory.core.contracts.StoryRunRepository
import nl.vdzon.softwarefactory.core.contracts.StoryWorkspaceApi
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.contracts.TrackerIssueFields
import nl.vdzon.softwarefactory.core.contracts.TrackerProject
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import nl.vdzon.softwarefactory.telegram.AuditQuestionNotifier
import nl.vdzon.softwarefactory.tracker.TrackerCapabilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.io.path.writeText

class AuditGatewayAdapterTest {

    @TempDir
    lateinit var workspace: Path

    @Test
    fun `audit proposal is created atomically with the exact audit notification events`() {
        val mapper = jacksonObjectMapper()
        workspace.resolve("agent-result.json").writeText(
            mapper.writeValueAsString(
                AgentResultFile(
                    storyKey = "AUDIT:softwarefactory:architecture",
                    role = "auditor",
                    containerName = "audit-1",
                    phase = "audited",
                    outcome = "audited",
                    auditReportMarkdown = "# Rapport",
                    proposedStoryTitle = "Maak grenzen expliciet",
                    proposedStoryDescription = "Voeg een architectuurtest toe.",
                    durationMs = 123,
                ),
            ),
        )
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
        doReturn(false).`when`(runtime).isContainerRunning("audit-1")
        val adapter = AuditGatewayAdapter(
            auditJobsReader = mock(AuditJobsReader::class.java),
            projects = projects,
            auditReportRepository = auditReports,
            auditQuestionRepository = mock(AuditQuestionRepository::class.java),
            agentRuntime = runtime,
            agentRunRepository = mock(AgentRunRepository::class.java),
            storyRunRepository = mock(StoryRunRepository::class.java),
            storyWorkspaceService = mock(StoryWorkspaceApi::class.java),
            tracker = tracker,
            knowledgeApi = mock(KnowledgeApi::class.java),
            auditQuestionNotifier = mock(AuditQuestionNotifier::class.java),
            objectMapper = mapper,
        )

        val outcome = adapter.auditOutcome(AuditDispatchHandle("audit-1", workspace.toString(), 42))

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
}
