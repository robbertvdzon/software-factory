package nl.vdzon.softwarefactory.web

import nl.vdzon.softwarefactory.web.services.*
import nl.vdzon.softwarefactory.web.views.*
import nl.vdzon.softwarefactory.web.models.*
import nl.vdzon.softwarefactory.web.repositories.*
import nl.vdzon.softwarefactory.web.controllers.*

import nl.vdzon.softwarefactory.web.models.AgentsPageData
import nl.vdzon.softwarefactory.web.models.DashboardPageData
import nl.vdzon.softwarefactory.web.models.MergedPageData
import nl.vdzon.softwarefactory.web.models.StoriesPageData
import nl.vdzon.softwarefactory.web.models.StoryDetailPageData
import nl.vdzon.softwarefactory.web.models.UiAgentEvent
import nl.vdzon.softwarefactory.web.models.UiAgentRun
import nl.vdzon.softwarefactory.web.models.UiStoryRun
import nl.vdzon.softwarefactory.web.views.FactoryDashboardViews
import nl.vdzon.softwarefactory.youtrack.TrackerComment
import nl.vdzon.softwarefactory.youtrack.TrackerIssue
import nl.vdzon.softwarefactory.youtrack.TrackerIssueFields
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FactoryDashboardViewsTest {
    private val views = FactoryDashboardViews(
        Clock.fixed(Instant.parse("2026-05-24T12:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `renders stories with issue and run data`() {
        val html = views.stories(
            StoriesPageData(
                issues = listOf(issue()),
                runsByStory = mapOf("KAN-64" to run()),
                errors = emptyList(),
            ),
        )

        assertContains(html, "KAN-64")
        assertContains(html, "Events")
        assertContains(html, "/stories/KAN-64")
        assertContains(html, "${'$'}0.2040")
    }

    @Test
    fun `escapes tracker content before rendering`() {
        val html = views.stories(
            StoriesPageData(
                issues = listOf(issue(summary = """<script>alert("x")</script>""")),
                runsByStory = emptyMap(),
                errors = emptyList(),
            ),
        )

        assertContains(html, "&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;")
        assertFalse(html.contains("""<script>alert("x")</script>"""))
    }

    @Test
    fun `renders story detail command forms`() {
        val html = views.storyDetail(
            StoryDetailPageData(
                issue = issue(),
                storyKey = "KAN-64",
                run = run(),
                agentRuns = listOf(agentRun()),
                events = emptyList(),
                youTrackUrl = "https://youtrack.example/issue/KAN-64",
                previewUrl = "https://preview.example",
                errors = emptyList(),
            ),
        )

        assertContains(html, "/stories/KAN-64/commands/pause")
        assertContains(html, "/stories/KAN-64/commands/sync")
        assertContains(html, "Commit + push")
        assertContains(html, "/stories/KAN-64/open-workspace")
        assertContains(html, "Open in IntelliJ")
        assertContains(html, "/tmp/software-factory/KAN-64/repo")
        assertContains(html, "/stories/KAN-64/commands/merge")
        assertContains(html, "Test op preview")
        assertContains(html, "developer")
        assertContains(html, "Gestart 2026-05-24 11:55:00")
        assertContains(html, "Loopt")
    }

    @Test
    fun `briefing renders newest runs first with readable outcomes and role iterations`() {
        val html = views.briefing(
            StoryDetailPageData(
                issue = issue(
                    comments = listOf(
                        comment("dev-1", "[DEVELOPER] eerste poging", "2026-05-24T10:05:00Z"),
                        comment("review-1", "[REVIEWER] feedback", "2026-05-24T10:25:00Z"),
                        comment("dev-2", "[DEVELOPER] tweede poging", "2026-05-24T10:45:00Z"),
                    ),
                ),
                storyKey = "KAN-64",
                run = run(),
                agentRuns = listOf(
                    agentRun(
                        id = 1,
                        role = "developer",
                        startedAt = "2026-05-24T10:00:00Z",
                        endedAt = "2026-05-24T10:04:00Z",
                        outcome = "developed",
                        summaryText = "Eerste developer run",
                    ),
                    agentRun(
                        id = 2,
                        role = "reviewer",
                        startedAt = "2026-05-24T10:20:00Z",
                        endedAt = "2026-05-24T10:24:00Z",
                        outcome = "reviewed-with-feedback-for-developer",
                        summaryText = "Review feedback",
                    ),
                    agentRun(
                        id = 3,
                        role = "developer",
                        startedAt = "2026-05-24T10:40:00Z",
                        endedAt = "2026-05-24T10:42:00Z",
                        outcome = "developed",
                        summaryText = "Tweede developer run",
                    ),
                ),
                events = emptyList(),
                youTrackUrl = "https://youtrack.example/issue/KAN-64",
                previewUrl = null,
                errors = emptyList(),
            ),
        )

        assertContains(html, "developer (2/2)")
        assertContains(html, "reviewer (1/1)")
        assertContains(html, "Review met feedback")
        assertContains(html, "REVIEWED_WITH_FEEDBACK_FOR_DEVELOPER")
        assertContains(html, "Gestart 2026-05-24 10:40:00")
        assertContains(html, "klaar 2026-05-24 10:42:00")
        assertTrue(html.indexOf("Tweede developer run") < html.indexOf("Review feedback"))
        assertTrue(html.indexOf("tweede poging") < html.indexOf("feedback"))
    }

    private fun issue(
        summary: String = "Events",
        comments: List<TrackerComment> = listOf(comment("1", "[DEVELOPER] Done", "2026-05-24T11:58:00Z")),
    ): TrackerIssue =
        TrackerIssue(
            key = "KAN-64",
            summary = summary,
            description = "Story description",
            status = "Develop",
            projectKey = "KAN",
            fields = TrackerIssueFields(
                targetRepo = "https://github.com/robbertvdzon/sample-build-project",
                aiSupplier = "claude",
                aiPhase = "developing",
                aiLevel = 0,
                aiTokenBudget = 40_000,
                aiTokensUsed = 1_000,
                agentStartedAt = OffsetDateTime.parse("2026-05-24T11:55:00Z"),
                paused = false,
                error = null,
            ),
            comments = comments,
        )

    private fun comment(id: String, body: String, created: String): TrackerComment =
        TrackerComment(
            id = id,
            authorAccountId = "factory",
            authorDisplayName = "Factory",
            body = body,
            created = OffsetDateTime.parse(created),
        )

    private fun run(): UiStoryRun =
        UiStoryRun(
            id = 1,
            storyKey = "KAN-64",
            targetRepo = "https://github.com/robbertvdzon/sample-build-project",
            workspacePath = "/tmp/software-factory/KAN-64",
            startedAt = OffsetDateTime.parse("2026-05-24T11:50:00Z"),
            endedAt = null,
            finalStatus = null,
            branchName = "factory/KAN-64",
            prNumber = 12,
            prUrl = "https://github.com/robbertvdzon/sample-build-project/pull/12",
            baseBranch = "main",
            branchPrefix = "factory/",
            previewUrlTemplate = "https://preview-pr-{pr_num}.example",
            previewNamespaceTemplate = "preview-{pr_num}",
            totalInputTokens = 1_900,
            totalOutputTokens = 6_400,
            totalCacheReadTokens = 0,
            totalCacheCreationTokens = 0,
            totalCostUsdEst = 0.204,
        )

    private fun agentRun(
        id: Long = 1,
        role: String = "developer",
        startedAt: String = "2026-05-24T11:55:00Z",
        endedAt: String? = null,
        outcome: String? = null,
        summaryText: String = "Working",
    ): UiAgentRun =
        UiAgentRun(
            id = id,
            storyRunId = 1,
            storyKey = "KAN-64",
            role = role,
            containerName = "sf-KAN-64-$role",
            model = "mock",
            effort = "low",
            level = 0,
            startedAt = OffsetDateTime.parse(startedAt),
            endedAt = endedAt?.let { OffsetDateTime.parse(it) },
            outcome = outcome,
            inputTokens = 58,
            outputTokens = 2_500,
            cacheReadInputTokens = 0,
            cacheCreationInputTokens = 0,
            numTurns = 1,
            durationMs = 78_000,
            costUsdEst = 0.0494,
            summaryText = summaryText,
            workspacePath = null,
        )
}
