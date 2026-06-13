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
import nl.vdzon.softwarefactory.core.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerIssueFields
import nl.vdzon.softwarefactory.core.TrackerProject
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
    fun `stories overview hides subtasks`() {
        val html = views.stories(
            StoriesPageData(
                issues = listOf(
                    issue(summary = "Real story", key = "KAN-64", type = "Story"),
                    issue(summary = "Just a subtask", key = "KAN-65", type = "Task"),
                ),
                runsByStory = emptyMap(),
                errors = emptyList(),
            ),
        )

        assertContains(html, "Real story")
        assertFalse(html.contains("Just a subtask"))
    }

    @Test
    fun `stories overview renders filter checkboxes and bucket attributes`() {
        val html = views.stories(
            StoriesPageData(
                issues = listOf(
                    issue(summary = "Done story", key = "KAN-70", type = "Story", status = "Done"),
                    issue(summary = "Busy story", key = "KAN-71", type = "Story", status = "In Progress"),
                    issue(summary = "Todo story", key = "KAN-72", type = "Story", status = "Open"),
                ),
                runsByStory = emptyMap(),
                errors = emptyList(),
            ),
        )

        // Drie checkboxes, standaard aangevinkt
        assertContains(html, "data-bucket-toggle=\"finished\" checked")
        assertContains(html, "data-bucket-toggle=\"in-progress\" checked")
        assertContains(html, "data-bucket-toggle=\"todo\" checked")
        // Rijen krijgen een bucket-attribuut op basis van de classificatie
        assertContains(html, "data-bucket=\"finished\"")
        assertContains(html, "data-bucket=\"in-progress\"")
        assertContains(html, "data-bucket=\"todo\"")
        // Inline toggle-script aanwezig
        assertContains(html, "data-story-filter")
    }

    @Test
    fun `classifyStatus buckets statuses case-insensitively`() {
        listOf("Done", "fixed", "VERIFIED", "Closed", "resolved").forEach {
            assertEquals(FactoryDashboardViews.StatusBucket.FINISHED, views.classifyStatus(it))
        }
        listOf("In Progress", "develop", "Developing").forEach {
            assertEquals(FactoryDashboardViews.StatusBucket.IN_PROGRESS, views.classifyStatus(it))
        }
        listOf("Open", "Submitted", "Backlog", "To Do").forEach {
            assertEquals(FactoryDashboardViews.StatusBucket.TODO, views.classifyStatus(it))
        }
        // Onbekend en leeg vallen onder TODO
        assertEquals(FactoryDashboardViews.StatusBucket.TODO, views.classifyStatus("Iets onbekends"))
        assertEquals(FactoryDashboardViews.StatusBucket.TODO, views.classifyStatus(null))
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
        assertContains(html, """data-refresh="5"""")
        assertContains(html, "/stories/KAN-64/commands/sync")
        assertContains(html, "Commit + push")
        assertContains(html, "/stories/KAN-64/open-workspace")
        assertContains(html, "Open in IntelliJ")
        assertContains(html, "/tmp/software-factory/KAN-64/repo")
        assertContains(html, "/stories/KAN-64/commands/merge")
        assertContains(html, "Test op preview")
    }

    @Test
    fun `story detail shows full purge button with confirmation for a story`() {
        val html = views.storyDetail(detailPage(issue()))

        assertContains(html, "/stories/KAN-64/purge")
        assertContains(html, "Verwijder story volledig")
        assertContains(html, "onsubmit=\"return confirm(")
    }

    @Test
    fun `story detail hides full purge button for a subtask`() {
        val html = views.storyDetail(detailPage(issue(type = "Task", subtaskType = "development")))

        assertFalse(html.contains("/purge"))
    }

    @Test
    fun `briefing renders newest runs first with readable outcomes and role iterations`() {
        val runs = listOf(
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
        )
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
                agentRuns = runs,
                allAgentRuns = runs,
                events = emptyList(),
                youTrackUrl = "https://youtrack.example/issue/KAN-64",
                previewUrl = null,
                errors = emptyList(),
            ),
        )

        assertContains(html, """data-refresh="5"""")
        assertContains(html, "developing")
        assertContains(html, "Huidige fase: developing.")
        assertFalse(html.contains("Agent-comments"))
        assertContains(html, "developer (2/2)")
        assertContains(html, "reviewer (1/1)")
        assertContains(html, "Review met feedback")
        assertContains(html, "REVIEWED_WITH_FEEDBACK_FOR_DEVELOPER")
        assertContains(html, "Gestart 2026-05-24 10:40:00")
        assertContains(html, "klaar 2026-05-24 10:42:00")
        // Looptijd tussen haakjes in mm:ss (10:40:00 → 10:42:00 = 2:00).
        assertContains(html, "(2:00)")
        assertTrue(html.indexOf("Tweede developer run") < html.indexOf("Review feedback"))
        assertFalse(html.contains("tweede poging"))
    }

    @Test
    fun `story detail shows approve and reject for refined phase`() {
        val html = views.storyDetail(detailPage(issue(aiPhase = null, storyPhase = "refined")))

        assertContains(html, "/stories/KAN-64/story-phase")
        assertContains(html, """name="phase" value="refined-approved"""")
        assertContains(html, """name="phase" value="refined-rejected"""")
        assertContains(html, "Approve")
        assertContains(html, "Reject")
        assertContains(html, "Refinement beoordelen")
    }

    @Test
    fun `story detail shows answer form for refined-with-questions`() {
        val html = views.storyDetail(detailPage(issue(aiPhase = null, storyPhase = "refined-with-questions")))

        assertContains(html, "/stories/KAN-64/story-phase")
        assertContains(html, """value="questions-answered"""")
        assertContains(html, "Antwoord versturen")
    }

    @Test
    fun `story detail shows the agent question text in the action card`() {
        val page = detailPage(issue(aiPhase = null, storyPhase = "planned-with-questions"))
            .copy(agentQuestions = mapOf("KAN-64" to "Welke aanpak heeft de voorkeur: soft-delete of hard-delete?"))

        val html = views.storyDetail(page)

        assertContains(html, "Vraag van de planner")
        assertContains(html, """<div class="q">""")
        assertContains(html, "Welke aanpak heeft de voorkeur: soft-delete of hard-delete?")
    }

    @Test
    fun `stories list shows type badge and story phase`() {
        val html = views.stories(
            StoriesPageData(
                issues = listOf(issue(aiPhase = null, storyPhase = "refining")),
                runsByStory = emptyMap(),
                errors = emptyList(),
            ),
        )

        assertContains(html, "Story")
        assertContains(html, "refining")
    }

    @Test
    fun `story detail lists subtasks with status and links`() {
        val page = detailPage(issue(aiPhase = null, storyPhase = "planning-approved")).copy(
            subtasks = listOf(
                issue(key = "KAN-70", summary = "Implementeer X", type = "Task", subtaskType = "development", subtaskPhase = "developing"),
            ),
        )

        val html = views.storyDetail(page)

        assertContains(html, "Subtaken")
        assertContains(html, "/stories/KAN-70")
        assertContains(html, "developing")
    }

    @Test
    fun `subtask detail shows parent link and review approve reject`() {
        val page = detailPage(
            issue(key = "KAN-70", aiPhase = null, type = "Task", subtaskType = "review", subtaskPhase = "reviewed"),
        ).copy(storyKey = "KAN-70", parentKey = "KAN-64")

        val html = views.storyDetail(page)

        assertContains(html, "/stories/KAN-64")
        assertContains(html, "/stories/KAN-70/subtask-phase")
        assertContains(html, """name="phase" value="review-approved"""")
        assertContains(html, """name="phase" value="review-rejected"""")
        // Op de eigen subtaak-pagina geen returnTo-override: na de actie blijf je op de subtaak.
        assertFalse(html.contains("""name="returnTo""""))
    }

    @Test
    fun `subtasks panel shows phase, done badge and inline human action (no label)`() {
        val page = detailPage(issue(aiPhase = null, storyPhase = "planning-approved")).copy(
            subtasks = listOf(
                issue(
                    key = "KAN-70", summary = "Story-brede review", type = "Task",
                    subtaskType = "review", subtaskPhase = "reviewed",
                ),
                issue(
                    key = "KAN-71", summary = "Implementatie", type = "Task",
                    subtaskType = "development", subtaskPhase = "review-approved",
                ),
            ),
        )

        val html = views.storyDetail(page)

        // Het label is weg uit de FE.
        assertFalse(html.contains("ai-development"))
        assertFalse(html.contains("ongetagd"))
        // Wel: de fase, een 'actie nodig' bij een mens-actie en een 'klaar' bij de eindfase.
        assertContains(html, "fase: reviewed")
        assertContains(html, "actie nodig")
        assertContains(html, "klaar")
        assertContains(html, "/stories/KAN-70/subtask-phase")
        assertContains(html, """name="phase" value="review-approved"""")
        // Gesurfacet op het story-scherm: na de actie terug naar de story (niet naar de subtaak).
        assertContains(html, """name="returnTo" value="/stories/KAN-64"""")
    }

    @Test
    fun `subtasks panel shows error badge when issue has error field set`() {
        val page = detailPage(issue(aiPhase = null, storyPhase = "planning-approved")).copy(
            subtasks = listOf(
                issue(
                    key = "KAN-70", summary = "Implementing with error", type = "Task",
                    subtaskType = "development", subtaskPhase = "developing", error = "Connection timeout",
                ),
            ),
        )

        val html = views.storyDetail(page)

        assertContains(html, "fout")
        assertContains(html, """<span class="badge bad">fout</span>""")
    }

    @Test
    fun `subtasks panel shows bezig badge when subtask is in active phase but not terminal`() {
        val page = detailPage(issue(aiPhase = null, storyPhase = "planning-approved")).copy(
            subtasks = listOf(
                issue(
                    key = "KAN-70", summary = "Developing", type = "Task",
                    subtaskType = "development", subtaskPhase = "developing",
                ),
                issue(
                    key = "KAN-71", summary = "Reviewing", type = "Task",
                    subtaskType = "review", subtaskPhase = "reviewing",
                ),
            ),
        )

        val html = views.storyDetail(page)

        // Both active subtasks should show bezig badge.
        assertContains(html, "bezig")
        // Count occurrences: should be 2 for the two active subtasks.
        val bezig = "bezig".let { word ->
            var count = 0
            var index = 0
            while (html.indexOf(word, index).also { index = it } != -1) {
                count++
                index += word.length
            }
            count
        }
        assertTrue(bezig >= 2, "Expected at least 2 'bezig' badges for active subtasks")
    }

    @Test
    fun `story shows start refining button when story phase is empty`() {
        val page = detailPage(issue(aiPhase = null, storyPhase = null))

        val html = views.storyDetail(page)

        assertContains(html, "/stories/KAN-64/start-refining")
        assertContains(html, "Start refining")
    }

    @Test
    fun `story shows start developing button when planning-approved with not-started subtasks`() {
        val page = detailPage(issue(aiPhase = null, storyPhase = "planning-approved")).copy(
            subtasks = listOf(
                issue(key = "KAN-70", type = "Task", subtaskType = "development", subtaskPhase = null),
            ),
        )

        val html = views.storyDetail(page)

        assertContains(html, "/stories/KAN-64/start-developing")
        assertContains(html, "Start developing")
    }

    @Test
    fun `start developing button hidden once a subtask is started`() {
        val page = detailPage(issue(aiPhase = null, storyPhase = "planning-approved")).copy(
            subtasks = listOf(
                issue(key = "KAN-70", type = "Task", subtaskType = "development", subtaskPhase = "developing"),
            ),
        )

        val html = views.storyDetail(page)

        assertFalse(html.contains("/stories/KAN-64/start-developing"))
    }

    @Test
    fun `story briefing combines story and subtask runs with a source label`() {
        val runs = listOf(
            agentRun(id = 1, role = "refiner", startedAt = "2026-05-24T10:00:00Z", summaryText = "Story refine"),
            agentRun(id = 2, role = "developer", startedAt = "2026-05-24T10:30:00Z", summaryText = "Subtaak dev", subtaskKey = "KAN-64.1"),
        )
        val page = StoryDetailPageData(
            issue = issue(storyPhase = "planning-approved"),
            storyKey = "KAN-64",
            run = run(),
            agentRuns = runs.filter { it.subtaskKey == null },
            allAgentRuns = runs,
            events = emptyList(),
            youTrackUrl = "https://youtrack.example/issue/KAN-64",
            previewUrl = null,
            errors = emptyList(),
            subtasks = listOf(issue(key = "KAN-64.1", summary = "Implementatie", type = "Task", subtaskType = "development")),
        )

        val html = views.briefing(page)

        // Beide bronnen tonen, met label.
        assertContains(html, "Story refine")
        assertContains(html, "Subtaak dev")
        assertContains(html, "Story &middot; KAN-64")
        assertContains(html, "Subtaak &middot; KAN-64.1")
        assertContains(html, "Implementatie")
    }

    @Test
    fun `stories page shows the new story form with project and repo options`() {
        val page = StoriesPageData(
            issues = emptyList(),
            runsByStory = emptyMap(),
            errors = emptyList(),
            projects = listOf(TrackerProject(id = "0-0", key = "PF", name = "Personal Feed")),
            repoNames = listOf("personal-feed", "softwarefactory"),
        )

        val html = views.stories(page)

        assertContains(html, "Nieuwe story")
        assertContains(html, "/stories/create")
        assertContains(html, """<option value="PF">""")
        assertContains(html, """<option value="personal-feed">""")
        assertContains(html, "Direct starten")
    }

    private fun detailPage(issue: TrackerIssue): StoryDetailPageData =
        StoryDetailPageData(
            issue = issue,
            storyKey = "KAN-64",
            run = run(),
            agentRuns = emptyList(),
            events = emptyList(),
            youTrackUrl = "https://youtrack.example/issue/KAN-64",
            previewUrl = null,
            errors = emptyList(),
        )

    private fun issue(
        summary: String = "Events",
        comments: List<TrackerComment> = listOf(comment("1", "[DEVELOPER] Done", "2026-05-24T11:58:00Z")),
        aiPhase: String? = "developing",
        storyPhase: String? = null,
        type: String? = null,
        subtaskType: String? = null,
        subtaskPhase: String? = null,
        key: String = "KAN-64",
        tags: List<String> = emptyList(),
        status: String = "Develop",
        error: String? = null,
    ): TrackerIssue =
        TrackerIssue(
            key = key,
            summary = summary,
            tags = tags,
            description = "Story description",
            status = status,
            projectKey = "KAN",
            fields = TrackerIssueFields(
                targetRepo = "https://github.com/robbertvdzon/sample-build-project",
                aiSupplier = "claude",
                aiPhase = aiPhase,
                aiLevel = 0,
                aiTokenBudget = 40_000,
                aiTokensUsed = 1_000,
                agentStartedAt = OffsetDateTime.parse("2026-05-24T11:55:00Z"),
                paused = false,
                error = error,
                type = type,
                storyPhase = storyPhase,
                subtaskType = subtaskType,
                subtaskPhase = subtaskPhase,
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
        subtaskKey: String? = null,
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
            subtaskKey = subtaskKey,
        )
}
