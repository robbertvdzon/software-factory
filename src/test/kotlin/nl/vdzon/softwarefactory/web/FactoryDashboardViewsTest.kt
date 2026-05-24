package nl.vdzon.softwarefactory.web

import nl.vdzon.softwarefactory.tracker.TrackerComment
import nl.vdzon.softwarefactory.tracker.TrackerIssue
import nl.vdzon.softwarefactory.tracker.TrackerIssueFields
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertContains
import kotlin.test.assertFalse

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
        assertContains(html, "/stories/KAN-64/commands/merge")
        assertContains(html, "Test op preview")
        assertContains(html, "developer")
    }

    private fun issue(summary: String = "Events"): TrackerIssue =
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
            comments = listOf(
                TrackerComment(
                    id = "1",
                    authorAccountId = "factory",
                    authorDisplayName = "Factory",
                    body = "[DEVELOPER] Done",
                    created = OffsetDateTime.parse("2026-05-24T11:58:00Z"),
                ),
            ),
        )

    private fun run(): UiStoryRun =
        UiStoryRun(
            id = 1,
            storyKey = "KAN-64",
            targetRepo = "https://github.com/robbertvdzon/sample-build-project",
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

    private fun agentRun(): UiAgentRun =
        UiAgentRun(
            id = 1,
            storyRunId = 1,
            storyKey = "KAN-64",
            role = "developer",
            containerName = "sf-KAN-64-developer",
            model = "mock",
            effort = "low",
            level = 0,
            startedAt = OffsetDateTime.parse("2026-05-24T11:55:00Z"),
            endedAt = null,
            outcome = null,
            inputTokens = 58,
            outputTokens = 2_500,
            cacheReadInputTokens = 0,
            cacheCreationInputTokens = 0,
            numTurns = 1,
            durationMs = 78_000,
            costUsdEst = 0.0494,
            summaryText = "Working",
            workspacePath = null,
        )
}
