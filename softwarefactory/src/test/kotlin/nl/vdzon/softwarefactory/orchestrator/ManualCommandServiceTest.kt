package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.orchestrator.*
import nl.vdzon.softwarefactory.core.contracts.*
import nl.vdzon.softwarefactory.orchestrator.services.*

import nl.vdzon.softwarefactory.orchestrator.*
import nl.vdzon.softwarefactory.orchestrator.*
import nl.vdzon.softwarefactory.orchestrator.*

import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.github.GitHubClientException
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.github.PullRequestInfo
import nl.vdzon.softwarefactory.github.PullRequestChecksResult
import nl.vdzon.softwarefactory.github.PullRequestHeadChangedException
import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.merge.internal.ProjectAwarePullRequestMergeService
import nl.vdzon.softwarefactory.core.DeploymentConfig
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.testsupport.InMemoryProcessedCommentStore
import nl.vdzon.softwarefactory.tracker.TrackerApi
import nl.vdzon.softwarefactory.core.contracts.TrackerComment
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.contracts.TrackerIssueFields
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.tracker.services.ProcessedCommentService
import nl.vdzon.softwarefactory.tracker.repositories.ProcessedCommentStore
import nl.vdzon.softwarefactory.preview.PreviewApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ManualCommandServiceTest {
    private val now = OffsetDateTime.parse("2026-05-24T12:00:00Z")
    private val clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC)

    @Test
    fun `auto-approve trigger updates field idempotently`() {
        val issueTracker = FakeTrackerApi()
        val store = InMemoryProcessedCommentStore()
        val service = service(issueTracker, store = store)
        val issue = issue(comments = listOf(comment("11", "AUTO-APPROVE=on")))

        val applied = service.apply(issue)
        val again = service.apply(issue)

        assertTrue(applied.issue.fields.autoApprove)
        assertEquals(issue, again.issue)
        assertEquals(
            listOf(mapOf(TrackerField.AUTO_APPROVE to "on")),
            issueTracker.updates.getValue("KAN-1").map { it.values },
        )
    }

    @Test
    fun `resume and level commands update fields once`() {
        val issueTracker = FakeTrackerApi()
        val store = InMemoryProcessedCommentStore()
        val service = service(issueTracker, store = store)
        val issue = issue(
            paused = true,
            error = "budget exceeded",
            comments = listOf(comment("10", "@factory:command:resume\nLEVEL=7\nSUPPLIER=copilot")),
        )

        val applied = service.apply(issue)
        val again = service.apply(issue)

        assertNull(applied.stopResult)
        assertFalse(applied.issue.fields.paused)
        assertNull(applied.issue.fields.error)
        assertEquals(7, applied.issue.fields.aiLevel)
        assertEquals("copilot", applied.issue.fields.aiSupplier)
        assertEquals(issue, again.issue)
        assertEquals(
            listOf(
                mapOf(TrackerField.PAUSED to false, TrackerField.ERROR to null),
                mapOf(TrackerField.AI_LEVEL to 7),
                mapOf(TrackerField.AI_SUPPLIER to "copilot"),
            ),
            issueTracker.updates.getValue("KAN-1").map { it.values },
        )
        assertTrue(store.isProcessed("KAN-1", "10", AgentRole.ORCHESTRATOR))
    }

    @Test
    fun `comments without manual commands do not trigger processed marker lookups`() {
        val issueTracker = FakeTrackerApi()
        val service = service(issueTracker)
        val issue = issue(
            comments = listOf(
                comment("20", "Gewone PO-opmerking zonder factory command."),
                comment("21", "[DEVELOPER] Samenvatting van een agent-run."),
            ),
        )

        val applied = service.apply(issue)

        assertEquals(issue, applied.issue)
        assertTrue(issueTracker.processedMarkerChecks.isEmpty())
    }

    @Test
    fun `resume on developer loopback cap clears error and increases story limit by five`() {
        val issueTracker = FakeTrackerApi()
        val service = service(issueTracker)
        val issue = issue(
            error = "[ORCHESTRATOR] Developer-loopback cap bereikt (5x). Handmatige triage nodig.",
            comments = listOf(comment("17", "@factory:command:resume")),
        )

        val applied = service.apply(issue)

        assertNull(applied.stopResult)
        assertFalse(applied.issue.fields.paused)
        assertNull(applied.issue.fields.error)
        assertEquals(10, applied.issue.fields.aiMaxDeveloperLoopbacks)
        assertEquals(
            mapOf(
                TrackerField.PAUSED to false,
                TrackerField.ERROR to null,
                TrackerField.AI_MAX_DEVELOPER_LOOPBACKS to 10,
            ),
            issueTracker.lastUpdate("KAN-1").values,
        )
    }

    @Test
    fun `resume on developer loopback cap increments existing story limit`() {
        val issueTracker = FakeTrackerApi()
        val service = service(issueTracker)
        val issue = issue(
            maxDeveloperLoopbacks = 12,
            error = "[ORCHESTRATOR] Developer-loopback cap bereikt (12x). Handmatige triage nodig.",
            comments = listOf(comment("18", "@factory:command:resume")),
        )

        val applied = service.apply(issue)

        assertEquals(17, applied.issue.fields.aiMaxDeveloperLoopbacks)
        assertEquals(17, issueTracker.lastUpdate("KAN-1").values[TrackerField.AI_MAX_DEVELOPER_LOOPBACKS])
    }

    @Test
    fun `resume on test chain cap clears error and raises the per-issue reset limit`() {
        // Spiegel van de developer-loopback-escape: default-cap 3 + increment 2 = 5.
        val issueTracker = FakeTrackerApi()
        val service = service(issueTracker)
        val issue = issue(
            error = "[ORCHESTRATOR] Test-chain reset cap bereikt (3x). Zet `resume` op deze subtaak.",
            comments = listOf(comment("19", "@factory:command:resume")),
        )

        val applied = service.apply(issue)

        assertNull(applied.issue.fields.error)
        assertEquals(5, applied.issue.fields.aiMaxTestChainResets)
        assertEquals(5, issueTracker.lastUpdate("KAN-1").values[TrackerField.AI_MAX_TEST_CHAIN_RESETS])
    }

    @Test
    fun `resume on test chain cap increments an already raised limit`() {
        val issueTracker = FakeTrackerApi()
        val service = service(issueTracker)
        val issue = issue(
            maxTestChainResets = 5,
            error = "[ORCHESTRATOR] Test-chain reset cap bereikt (5x). Zet `resume` op deze subtaak.",
            comments = listOf(comment("20", "@factory:command:resume")),
        )

        val applied = service.apply(issue)

        assertEquals(7, applied.issue.fields.aiMaxTestChainResets)
        assertEquals(7, issueTracker.lastUpdate("KAN-1").values[TrackerField.AI_MAX_TEST_CHAIN_RESETS])
    }

    @Test
    fun `pause and kill stop further orchestration`() {
        val issueTracker = FakeTrackerApi()
        val runtime = FakeAgentRuntime()
        val service = service(issueTracker, runtime = runtime)
        val issue = issue(comments = listOf(comment("11", "@factory:command:kill")))

        val applied = service.apply(issue)

        assertEquals(IssueProcessResult.Skipped("KAN-1", "killed"), applied.stopResult)
        assertEquals(listOf("KAN-1"), runtime.killedStories)
        assertEquals(true, issueTracker.lastUpdate("KAN-1").values[TrackerField.PAUSED])
    }

    @Test
    fun `delete closes PR branch preview run and transitions to Done`() {
        val issueTracker = FakeTrackerApi()
        val runtime = FakeAgentRuntime()
        val storyRuns = InMemoryStoryRunRepository().withPullRequest()
        val pullRequests = FakeGitHubApi()
        val previewCleaner = FakePreviewEnvironmentCleaner()
        val service = service(
            issueTracker = issueTracker,
            runtime = runtime,
            storyRuns = storyRuns,
            pullRequests = pullRequests,
            previewCleaner = previewCleaner,
        )
        val issue = issue(comments = listOf(comment("12", "@factory:command:delete")))

        val applied = service.apply(issue)

        assertEquals(IssueProcessResult.Skipped("KAN-1", "deleted"), applied.stopResult)
        assertEquals(listOf("KAN-1"), runtime.killedStories)
        assertEquals(listOf(42), pullRequests.closedPrs)
        assertEquals(listOf("ai/KAN-1"), pullRequests.deletedBranches)
        assertEquals(listOf("app-pr-42"), previewCleaner.cleanedNamespaces)
        assertEquals(listOf(1L to "deleted"), storyRuns.closed)
        assertEquals("(CANCELLED) Story KAN-1", issueTracker.summaryUpdates.single().second)
        assertEquals("Done", issueTracker.transitions.single().second)
    }

    @Test
    fun `merge merges the PR on the remote closes the run and transitions to Done`() {
        val issueTracker = FakeTrackerApi()
        val storyRuns = InMemoryStoryRunRepository().withPullRequest()
        val pullRequests = FakeGitHubApi()
        val previewCleaner = FakePreviewEnvironmentCleaner()
        val service = service(
            issueTracker = issueTracker,
            storyRuns = storyRuns,
            pullRequests = pullRequests,
            previewCleaner = previewCleaner,
        )
        val issue = issue(comments = listOf(comment("13", "@factory:command:merge")))

        val applied = service.apply(issue)

        // `gh pr merge` werkt main op de remote bij; er is bewust GEEN lokale `git push origin main`.
        assertEquals(IssueProcessResult.Merged("KAN-1", 42), applied.stopResult)
        assertEquals(listOf(42), pullRequests.mergedPrs)
        assertEquals(listOf("app-pr-42"), previewCleaner.cleanedNamespaces)
        assertEquals(listOf(1L to "merged"), storyRuns.closed)
        assertEquals("Done", issueTracker.transitions.single().second)
    }

    @Test
    fun `merge with GitHub conflict sets error and does not transition to Done`() {
        val issueTracker = FakeTrackerApi()
        val storyRuns = InMemoryStoryRunRepository().withPullRequest()
        val pullRequests = FakeGitHubApi().apply { shouldThrowOnMerge = true }
        val previewCleaner = FakePreviewEnvironmentCleaner()
        val service = service(
            issueTracker = issueTracker,
            storyRuns = storyRuns,
            pullRequests = pullRequests,
            previewCleaner = previewCleaner,
        )
        val issue = issue(comments = listOf(comment("15", "@factory:command:merge")))

        val applied = service.apply(issue)

        assertTrue(applied.issue.fields.error?.contains("Merge geblokkeerd") == true)
        assertTrue(applied.stopResult is IssueProcessResult.Errored)
        assertTrue(issueTracker.transitions.isEmpty())
        assertEquals(emptyList<Pair<Long, String>>(), storyRuns.closed)
        assertEquals(emptyList<Int>(), pullRequests.mergedPrs)
    }

    @Test
    fun `manual merge pending stays retryable and later merges when checks turn green`() {
        val issueTracker = FakeTrackerApi()
        val storyRuns = InMemoryStoryRunRepository().withPullRequest()
        val pullRequests = FakeGitHubApi().apply {
            checksResult = PullRequestChecksResult.Pending("Repository verification queued")
        }
        val store = InMemoryProcessedCommentStore()
        val service = service(
            issueTracker = issueTracker,
            store = store,
            storyRuns = storyRuns,
            pullRequests = pullRequests,
        )
        val issue = issue(comments = listOf(comment("16", "@factory:command:merge")))

        val pending = service.apply(issue)

        assertTrue(pending.retryLater)
        assertTrue(pending.stopResult is IssueProcessResult.Skipped)
        assertTrue(issueTracker.transitions.isEmpty())
        assertTrue(pullRequests.mergedPrs.isEmpty())

        pullRequests.checksResult = PullRequestChecksResult.Ready("new-head", emptyList())
        val merged = service.apply(issue)

        assertEquals(IssueProcessResult.Merged("KAN-1", 42), merged.stopResult)
        assertEquals(listOf(42), pullRequests.mergedPrs)
    }

    @Test
    fun `manual merge head race waits without Error or merge`() {
        val issueTracker = FakeTrackerApi()
        val pullRequests = FakeGitHubApi().apply { headRace = true }
        val service = service(
            issueTracker = issueTracker,
            storyRuns = InMemoryStoryRunRepository().withPullRequest(),
            pullRequests = pullRequests,
        )
        val issue = issue(comments = listOf(comment("17", "@factory:command:merge")))

        val applied = service.apply(issue)

        assertTrue(applied.retryLater)
        assertTrue(applied.issue.fields.error == null)
        assertTrue(issueTracker.transitions.isEmpty())
        assertTrue(pullRequests.mergedPrs.isEmpty())
    }

    @Test
    fun `re implement resets resources clears fields deletes agent comments and database run`() {
        val issueTracker = FakeTrackerApi()
        val storyRuns = InMemoryStoryRunRepository().withPullRequest()
        val pullRequests = FakeGitHubApi()
        val previewCleaner = FakePreviewEnvironmentCleaner()
        val service = service(
            issueTracker = issueTracker,
            storyRuns = storyRuns,
            pullRequests = pullRequests,
            previewCleaner = previewCleaner,
        )
        val issue = issue(
            phase = "tested-with-feedback-for-developer",
            error = "bad run",
            maxDeveloperLoopbacks = 12,
            agentStartedAt = OffsetDateTime.parse("2026-05-24T10:00:00Z"),
            comments = listOf(comment("14", "@factory:command:re-implement")),
        )

        val applied = service.apply(issue)

        assertEquals(IssueProcessResult.Skipped("KAN-1", "re-implement"), applied.stopResult)
        assertEquals(listOf(42), pullRequests.closedPrs)
        assertEquals(listOf("ai/KAN-1"), pullRequests.deletedBranches)
        assertEquals(listOf("app-pr-42"), previewCleaner.cleanedNamespaces)
        assertEquals(listOf("KAN-1"), issueTracker.deletedAgentComments)
        assertEquals(emptyList<Pair<Long, String>>(), storyRuns.closed)
        assertEquals(listOf(1L), storyRuns.deleted)
        assertTrue(storyRuns.activeRuns().isEmpty())
        val lastUpdate = issueTracker.lastUpdate("KAN-1").values
        assertFalse(lastUpdate.containsKey(TrackerField.AI_SUPPLIER))
        // v2: een story re-implement reset het Story Phase-veld (niet AI Phase).
        assertTrue(lastUpdate.containsKey(TrackerField.STORY_PHASE))
        assertNull(lastUpdate[TrackerField.STORY_PHASE])
        assertFalse(lastUpdate.containsKey(TrackerField.AI_LEVEL))
        assertTrue(lastUpdate.containsKey(TrackerField.AI_MAX_DEVELOPER_LOOPBACKS))
        assertNull(lastUpdate[TrackerField.AI_MAX_DEVELOPER_LOOPBACKS])
        assertFalse(lastUpdate.containsKey(TrackerField.AI_TOKEN_BUDGET))
        assertTrue(lastUpdate.containsKey(TrackerField.AI_TOKENS_USED))
        assertNull(lastUpdate[TrackerField.AI_TOKENS_USED])
        assertTrue(lastUpdate.containsKey(TrackerField.AGENT_STARTED_AT))
        assertNull(lastUpdate[TrackerField.AGENT_STARTED_AT])
        assertEquals(false, lastUpdate[TrackerField.PAUSED])
        assertNull(lastUpdate[TrackerField.ERROR])
    }

    @Test
    fun `re implement of a story deletes its subtasks`() {
        val issueTracker = FakeTrackerApi().apply {
            subtasks = listOf(
                issue(key = "KAN-2", type = "Task", subtaskType = "development"),
                issue(key = "KAN-3", type = "Task", subtaskType = "review"),
            )
        }
        val storyRuns = InMemoryStoryRunRepository().withPullRequest()
        val service = service(issueTracker = issueTracker, storyRuns = storyRuns)
        val issue = issue(
            storyPhase = "planning-approved",
            comments = listOf(comment("30", "@factory:command:re-implement")),
        )

        val applied = service.apply(issue)

        assertEquals(IssueProcessResult.Skipped("KAN-1", "re-implement"), applied.stopResult)
        assertEquals(listOf("KAN-2", "KAN-3"), issueTracker.deletedIssues)
    }

    @Test
    fun `re implement of a subtask does not delete sibling subtasks`() {
        val issueTracker = FakeTrackerApi().apply {
            subtasks = listOf(issue(key = "KAN-2", type = "Task", subtaskType = "development"))
        }
        val service = service(issueTracker = issueTracker)
        val subtask = issue(
            key = "KAN-5",
            type = "Task",
            subtaskType = "development",
            subtaskPhase = "developed",
            comments = listOf(comment("31", "@factory:command:re-implement")),
        )

        val applied = service.apply(subtask)

        assertEquals(IssueProcessResult.Skipped("KAN-5", "re-implement"), applied.stopResult)
        assertEquals(emptyList<String>(), issueTracker.deletedIssues)
    }

    @Test
    fun `re implement resets local workspace and skips github cleanup for non github repositories`() {
        val issueTracker = FakeTrackerApi()
        val targetRepo = "ssh://git.example.internal/team/project.git"
        val storyRuns = InMemoryStoryRunRepository().withRun(
            targetRepo = targetRepo,
            branchName = "ai/KAN-1",
            prNumber = null,
        )
        val pullRequests = FakeGitHubApi()
        val workspaceService = FakeStoryWorkspaceService()
        val service = service(
            issueTracker = issueTracker,
            storyRuns = storyRuns,
            pullRequests = pullRequests,
            storyWorkspaceService = workspaceService,
        )
        val issue = issue(
            targetRepo = targetRepo,
            phase = "developed",
            comments = listOf(comment("20", "@factory:command:re-implement")),
        )

        val applied = service.apply(issue)

        assertEquals(IssueProcessResult.Skipped("KAN-1", "re-implement"), applied.stopResult)
        assertEquals(emptyList<Int>(), pullRequests.closedPrs)
        assertEquals(emptyList<String>(), pullRequests.deletedBranches)
        assertEquals(listOf("KAN-1"), workspaceService.resetStoryKeys)
        assertEquals(emptyList<String>(), workspaceService.cleanedStoryKeys)
        assertEquals(emptyList<Pair<Long, String>>(), storyRuns.closed)
        assertEquals(listOf(1L), storyRuns.deleted)
        assertTrue(storyRuns.activeRuns().isEmpty())
        assertNull(issueTracker.lastUpdate("KAN-1").values[TrackerField.AI_PHASE])
    }

    @Test
    fun `clear error only clears the error field`() {
        val issueTracker = FakeTrackerApi()
        val service = service(issueTracker)
        val issue = issue(
            phase = "reviewing",
            error = "manual check needed",
            comments = listOf(comment("15", "@factory:command:clear-error")),
        )

        val applied = service.apply(issue)

        assertNull(applied.stopResult)
        assertNull(applied.issue.fields.error)
        assertEquals("reviewing", applied.issue.fields.aiPhase)
        assertEquals(mapOf(TrackerField.ERROR to null), issueTracker.lastUpdate("KAN-1").values)
    }

    @Test
    fun `clear error on a story also clears the errors of its subtasks`() {
        val issueTracker = FakeTrackerApi()
        issueTracker.subtasks = listOf(
            issue(key = "KAN-2", type = "Task", error = "agent dispatch failed"),
            issue(key = "KAN-3", type = "Task", error = null), // geen error → niet aanraken
        )
        val service = service(issueTracker)
        val story = issue(
            error = "story-level error",
            comments = listOf(comment("16", "@factory:command:clear-error")),
        )

        service.apply(story)

        // Story zelf én de subtaak-met-error worden geleegd; de foutloze subtaak blijft ongemoeid.
        assertEquals(mapOf(TrackerField.ERROR to null), issueTracker.lastUpdate("KAN-1").values)
        assertEquals(mapOf(TrackerField.ERROR to null), issueTracker.lastUpdate("KAN-2").values)
        assertNull(issueTracker.updates["KAN-3"])
    }

    @Test
    fun `re implement of a subtask resets its phase without deleting the shared run`() {
        val issueTracker = FakeTrackerApi()
        val storyRuns = InMemoryStoryRunRepository().withPullRequest()
        val service = service(issueTracker = issueTracker, storyRuns = storyRuns)
        val issue = issue(
            type = "Task",
            subtaskType = "development",
            subtaskPhase = "developing",
            error = "bad",
            comments = listOf(comment("20", "@factory:command:re-implement")),
        )

        val applied = service.apply(issue)

        assertEquals(IssueProcessResult.Skipped("KAN-1", "re-implement"), applied.stopResult)
        val lastUpdate = issueTracker.lastUpdate("KAN-1").values
        // Subtask re-implement reset alleen de Subtask Phase; de gedeelde story-run blijft.
        assertTrue(lastUpdate.containsKey(TrackerField.SUBTASK_PHASE))
        assertNull(lastUpdate[TrackerField.SUBTASK_PHASE])
        assertFalse(lastUpdate.containsKey(TrackerField.STORY_PHASE))
        assertNull(lastUpdate[TrackerField.ERROR])
        assertEquals(emptyList<Long>(), storyRuns.deleted)
        assertEquals(listOf("KAN-1"), issueTracker.deletedAgentComments)
    }

    @Test
    fun `retry current step kills active agent and clears error leaving the phase for recovery`() {
        val issueTracker = FakeTrackerApi()
        val runtime = FakeAgentRuntime()
        val service = service(issueTracker, runtime = runtime)
        val startedAt = OffsetDateTime.parse("2026-05-24T10:00:00Z")
        val issue = issue(
            storyPhase = "refining",
            paused = true,
            error = "[ORCHESTRATOR] Hard timeout",
            agentStartedAt = startedAt,
            comments = listOf(comment("16", "@factory:command:retry-current-step")),
        )

        val applied = service.apply(issue)

        assertEquals(IssueProcessResult.Skipped("KAN-1", "retry-current-step"), applied.stopResult)
        assertEquals(listOf("KAN-1"), runtime.killedStories)
        val lastUpdate = issueTracker.lastUpdate("KAN-1").values
        // v2: laat de actieve fase staan; alleen Error/Started/Paused legen → recovery herstart.
        assertFalse(lastUpdate.containsKey(TrackerField.STORY_PHASE))
        assertFalse(lastUpdate.containsKey(TrackerField.AI_PHASE))
        assertNull(lastUpdate[TrackerField.AGENT_STARTED_AT])
        assertEquals(false, lastUpdate[TrackerField.PAUSED])
        assertNull(lastUpdate[TrackerField.ERROR])
        assertNull(applied.issue.fields.agentStartedAt)
        assertFalse(applied.issue.fields.paused)
        assertNull(applied.issue.fields.error)
    }

    @Test
    fun `retry current step on a story also resets stuck subtasks with an error`() {
        val issueTracker = FakeTrackerApi()
        val runtime = FakeAgentRuntime()
        val startedAt = OffsetDateTime.parse("2026-05-24T10:00:00Z")
        issueTracker.subtasks = listOf(
            issue(
                key = "KAN-2",
                type = "Task",
                error = "[ORCHESTRATOR] Hard timeout: subtask hangt langer dan 60 minuten in reviewing.",
                agentStartedAt = startedAt,
                paused = true,
            ),
            issue(key = "KAN-3", type = "Task", error = null), // geen error → niet aanraken
        )
        val service = service(issueTracker, runtime = runtime)
        val story = issue(
            error = "[ORCHESTRATOR] Hard timeout",
            comments = listOf(comment("16", "@factory:command:retry-current-step")),
        )

        service.apply(story)

        // Zelfde reset als op de story zelf, ook toegepast op de vastgelopen subtaak — anders zet de
        // eerstvolgende poll exact dezelfde hard-timeout meteen terug (de subtaak z'n eigen
        // agentStartedAt was nooit gereset). De foutloze subtaak blijft ongemoeid.
        val subUpdate = issueTracker.lastUpdate("KAN-2").values
        assertNull(subUpdate[TrackerField.AGENT_STARTED_AT])
        assertEquals(false, subUpdate[TrackerField.PAUSED])
        assertNull(subUpdate[TrackerField.ERROR])
        assertNull(issueTracker.updates["KAN-3"])
    }

    @Test
    fun `approve command on the manual-approve gate sets manually-approved`() {
        val issueTracker = FakeTrackerApi()
        val service = service(issueTracker)
        val gate = issue(
            key = "KAN-9",
            type = "Task",
            subtaskType = "manual-approve",
            subtaskPhase = "manual-approve-needed",
            comments = listOf(comment("40", "@factory:command:approve")),
        )

        val applied = service.apply(gate)

        assertEquals("manually-approved", applied.issue.fields.subtaskPhase)
        assertEquals(
            mapOf(TrackerField.SUBTASK_PHASE to "manually-approved"),
            issueTracker.lastUpdate("KAN-9").values,
        )
    }

    @Test
    fun `reject command sets manually-not-approved and writes the reason to the story description`() {
        val issueTracker = FakeTrackerApi()
        issueTracker.parentKey = "KAN-1"
        issueTracker.stories["KAN-1"] = issue(key = "KAN-1")
        val service = service(issueTracker)
        val gate = issue(
            key = "KAN-9",
            type = "Task",
            subtaskType = "manual-approve",
            subtaskPhase = "manual-approve-needed",
            comments = listOf(comment("41", "@factory:command:reject\n\nKnoppen kloppen niet")),
        )

        val applied = service.apply(gate)

        assertEquals("manually-not-approved", applied.issue.fields.subtaskPhase)
        val (key, description) = issueTracker.descriptionUpdates.single()
        assertEquals("KAN-1", key)
        assertTrue(description.contains("manual-approve-feedback:start"))
        assertTrue(description.contains("Knoppen kloppen niet"), "reden moet in de description: $description")
    }

    @Test
    fun `approve command is a no-op when the subtask is not waiting on the gate`() {
        val issueTracker = FakeTrackerApi()
        val service = service(issueTracker)
        val subtask = issue(
            key = "KAN-9",
            type = "Task",
            subtaskType = "development",
            subtaskPhase = "developed",
            comments = listOf(comment("42", "@factory:command:approve")),
        )

        val applied = service.apply(subtask)

        // Geen fase-update: het commando raakt alleen de manual-approve-poort.
        assertNull(issueTracker.updates["KAN-9"])
        assertEquals("developed", applied.issue.fields.subtaskPhase)
    }

    private fun service(
        issueTracker: FakeTrackerApi,
        store: InMemoryProcessedCommentStore = InMemoryProcessedCommentStore(),
        runtime: FakeAgentRuntime = FakeAgentRuntime(),
        storyRuns: InMemoryStoryRunRepository = InMemoryStoryRunRepository(),
        pullRequests: FakeGitHubApi = FakeGitHubApi(),
        previewCleaner: FakePreviewEnvironmentCleaner = FakePreviewEnvironmentCleaner(),
        storyWorkspaceService: StoryWorkspaceApi? = null,
    ): ManualCommandService =
        ManualCommandService(
            issueTrackerClient = issueTracker,
            processedCommentService = ProcessedCommentService(issueTracker, store),
            agentRuntime = runtime,
            storyRunRepository = storyRuns,
            pullRequestClient = pullRequests,
            pullRequestMergeService = ProjectAwarePullRequestMergeService(
                pullRequests,
                ProjectConfiguration(
                    repos = mapOf("demo" to "git@github.com:robbertvdzon/sample-build-project.git"),
                    requiredChecks = mapOf("demo" to setOf("Repository verification")),
                ),
            ),
            previewApi = previewCleaner,
            storyWorkspaceService = storyWorkspaceService,
            settings = OrchestratorSettings(
                pollInterval = java.time.Duration.ofSeconds(15),
                maxParallelRefiner = 1,
                maxParallelDeveloper = 2,
                maxParallelReviewer = 2,
                maxParallelTester = 1,
                maxParallelTotal = 4,
                maxDeveloperLoopbacks = 5,
                maxTransientRetries = 2,
                hardTimeout = java.time.Duration.ofMinutes(60),
                costMonitorInterval = java.time.Duration.ofMinutes(5),
                creditsPauseDefault = java.time.Duration.ofMinutes(30),
            ),
            clock = clock,
        )

    private fun issue(
        phase: String? = null,
        paused: Boolean = false,
        error: String? = null,
        maxDeveloperLoopbacks: Int? = null,
        maxTestChainResets: Int? = null,
        agentStartedAt: OffsetDateTime? = null,
        targetRepo: String = "git@github.com:robbertvdzon/sample-build-project.git",
        comments: List<TrackerComment> = emptyList(),
        storyPhase: String? = null,
        type: String? = null,
        subtaskType: String? = null,
        subtaskPhase: String? = null,
        key: String = "KAN-1",
    ): TrackerIssue =
        TrackerIssue(
            key = key,
            summary = "Story KAN-1",
            status = "Develop",
            fields = TrackerIssueFields(
                targetRepo = targetRepo,
                repo = "demo",
                aiSupplier = "claude",
                aiPhase = phase,
                aiLevel = 5,
                aiMaxDeveloperLoopbacks = maxDeveloperLoopbacks,
                aiMaxTestChainResets = maxTestChainResets,
                aiTokenBudget = 40000,
                aiTokensUsed = 0,
                agentStartedAt = agentStartedAt,
                paused = paused,
                error = error,
                type = type,
                storyPhase = storyPhase,
                subtaskType = subtaskType,
                subtaskPhase = subtaskPhase,
            ),
            comments = comments,
        )

    private fun comment(id: String, body: String): TrackerComment =
        TrackerComment(id, "user", "User", body, null)

    private class FakeTrackerApi : TrackerApi {
        val updates = mutableMapOf<String, MutableList<TrackerFieldUpdate>>()
        val transitions = mutableListOf<Pair<String, String>>()
        val summaryUpdates = mutableListOf<Pair<String, String>>()
        val deletedAgentComments = mutableListOf<String>()
        val processedMarkerChecks = mutableListOf<Pair<String, AgentRole>>()
        var subtasks: List<TrackerIssue> = emptyList()
        val deletedIssues = mutableListOf<String>()
        var parentKey: String? = null
        val stories = mutableMapOf<String, TrackerIssue>()
        val descriptionUpdates = mutableListOf<Pair<String, String>>()

        override fun findAiIssues(maxResults: Int, includeFinished: Boolean): List<TrackerIssue> = emptyList()

        override fun subtasksOf(parentKey: String): List<TrackerIssue> = subtasks

        override fun parentStoryKey(subtaskKey: String): String? = parentKey

        override fun deleteIssue(issueKey: String) {
            deletedIssues += issueKey
        }

        override fun getIssue(issueKey: String): TrackerIssue =
            stories[issueKey] ?: throw UnsupportedOperationException()

        override fun updateIssueDescription(issueKey: String, description: String) {
            descriptionUpdates += issueKey to description
        }

        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
            updates.getOrPut(issueKey) { mutableListOf() } += update
        }

        override fun updateIssueSummary(issueKey: String, summary: String) {
            summaryUpdates += issueKey to summary
        }

        override fun transitionIssue(issueKey: String, statusName: String) {
            transitions += issueKey to statusName
        }

        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment =
            throw UnsupportedOperationException()

        override fun hasProcessedCommentMarker(commentId: String, role: AgentRole): Boolean {
            processedMarkerChecks += commentId to role
            return false
        }

        override fun markCommentProcessed(commentId: String, role: AgentRole): Boolean =
            false

        override fun deleteAgentComments(issueKey: String): Int {
            deletedAgentComments += issueKey
            return 1
        }

        fun lastUpdate(issueKey: String): TrackerFieldUpdate =
            updates.getValue(issueKey).last()
    }

    private class FakeAgentRuntime : AgentRuntime {
        val killedStories = mutableListOf<String>()

        override fun dispatch(request: AgentDispatchRequest): AgentDispatchResult =
            throw UnsupportedOperationException()

        override fun isAgentRunning(storyKey: String, role: AgentRole): Boolean = false

        override fun isContainerRunning(containerName: String): Boolean = false

        override fun isAnyAgentRunningForStory(storyKey: String): Boolean = false

        override fun runningCount(role: AgentRole?): Int = 0

        override fun killForStory(storyKey: String): Int {
            killedStories += storyKey
            return 1
        }
    }

    private class InMemoryStoryRunRepository : StoryRunRepository {
        private val runs = mutableMapOf<String, StoryRunRecord>()
        val closed = mutableListOf<Pair<Long, String>>()
        val deleted = mutableListOf<Long>()
        val pullRequestUpdates = mutableListOf<PullRequestUpdate>()

        fun withPullRequest(): InMemoryStoryRunRepository {
            withRun(
                targetRepo = "git@github.com:robbertvdzon/sample-build-project.git",
                branchName = "ai/KAN-1",
                prNumber = 42,
                prUrl = "https://github.com/robbertvdzon/sample-build-project/pull/42",
                previewNamespaceTemplate = "app-pr-{pr_num}",
            )
            return this
        }

        fun withRun(
            targetRepo: String,
            branchName: String,
            prNumber: Int?,
            prUrl: String? = null,
            previewNamespaceTemplate: String? = null,
            workspacePath: String? = "/tmp/workspace-test",
        ): InMemoryStoryRunRepository {
            runs["KAN-1"] = StoryRunRecord(
                id = 1,
                storyKey = "KAN-1",
                targetRepo = targetRepo,
                branchName = branchName,
                prNumber = prNumber,
                prUrl = prUrl,
                previewNamespaceTemplate = previewNamespaceTemplate,
                workspacePath = workspacePath,
            )
            return this
        }

        override fun openOrCreate(storyKey: String, targetRepo: String): StoryRunRecord =
            runs.getOrPut(storyKey) { StoryRunRecord(1, storyKey, targetRepo) }

        override fun get(storyRunId: Long): StoryRunRecord? =
            runs.values.firstOrNull { it.id == storyRunId }

        override fun updatePullRequest(
            storyRunId: Long,
            branchName: String,
            prNumber: Int?,
            prUrl: String?,
            baseBranch: String?,
            branchPrefix: String?,
            previewUrlTemplate: String?,
            previewNamespaceTemplate: String?,
            previewDbSecretRecipe: String?,
        ) {
            pullRequestUpdates += PullRequestUpdate(storyRunId, branchName, prNumber, prUrl)
        }

        override fun activePullRequests(): List<StoryRunRecord> =
            runs.values.filter { it.prNumber != null }

        override fun activeRuns(): List<StoryRunRecord> =
            runs.values.toList()

        override fun close(storyRunId: Long, finalStatus: String, endedAt: OffsetDateTime) {
            closed += storyRunId to finalStatus
            val entry = runs.entries.first { it.value.id == storyRunId }
            runs.remove(entry.key)
        }

        override fun delete(storyRunId: Long) {
            deleted += storyRunId
            val entry = runs.entries.first { it.value.id == storyRunId }
            runs.remove(entry.key)
        }
    }

    private class FakeGitHubApi : GitHubApi {
        val closedPrs = mutableListOf<Int>()
        val deletedBranches = mutableListOf<String>()
        val mergedPrs = mutableListOf<Int>()
        var shouldThrowOnMerge = false
        var headRace = false
        var checksResult: PullRequestChecksResult = PullRequestChecksResult.Ready("manual-test-head", emptyList())

        override fun ensurePullRequest(repoRoot: Path, branchName: String, baseBranch: String, title: String, body: String): PullRequestInfo =
            PullRequestInfo(number = 1, url = "https://github.example/pr/1")

        override fun isMerged(targetRepo: String, prNumber: Int): Boolean = false

        override fun unprocessedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> = emptyList()

        override fun claimedFactoryComments(targetRepo: String, prNumber: Int): List<PullRequestComment> = emptyList()

        override fun markCommentClaimed(targetRepo: String, commentId: Long) = Unit

        override fun markCommentDone(targetRepo: String, commentId: Long) = Unit

        override fun markCommentFailed(targetRepo: String, commentId: Long) = Unit

        override fun closePullRequest(targetRepo: String, prNumber: Int) {
            closedPrs += prNumber
        }

        override fun deleteBranch(targetRepo: String, branchName: String) {
            deletedBranches += branchName
        }

        override fun mergePullRequest(targetRepo: String, prNumber: Int, expectedHeadSha: String) {
            if (headRace) {
                throw PullRequestHeadChangedException(expectedHeadSha, "new-head")
            }
            if (shouldThrowOnMerge) {
                throw GitHubClientException("Pull request has merge conflicts")
            }
            mergedPrs += prNumber
        }

        override fun requiredChecks(targetRepo: String, prNumber: Int, requiredNames: Set<String>) =
            checksResult
    }

    private data class PullRequestUpdate(
        val storyRunId: Long,
        val branchName: String,
        val prNumber: Int?,
        val prUrl: String?,
    )

    private class FakeStoryWorkspaceService : StoryWorkspaceApi {
        val syncedRoles = mutableListOf<AgentRole>()
        val resetStoryKeys = mutableListOf<String>()
        val cleanedStoryKeys = mutableListOf<String>()

        override fun prepare(storyRun: StoryRunRecord, role: AgentRole): PreparedStoryWorkspace =
            throw UnsupportedOperationException()

        override fun resetForReImplementation(storyRun: StoryRunRecord): Boolean {
            resetStoryKeys += storyRun.storyKey
            return true
        }

        override fun syncAfterAgent(storyRun: StoryRunRecord, role: AgentRole): RepositorySyncResult {
            syncedRoles += role
            return RepositorySyncResult(
                workspacePath = Path.of("/tmp/story-workspace"),
                repoRoot = Path.of("/tmp/story-workspace/repo"),
                branchName = "ai/${storyRun.storyKey}",
                baseBranch = "main",
                branchPrefix = "ai/",
                deploymentConfig = DeploymentConfig(previewNamespaceTemplate = "app-pr-{pr_num}"),
                committed = true,
                pushed = true,
                prNumber = 43,
                prUrl = "https://github.example/pr/43",
            )
        }

        override fun cleanup(storyKey: String): Boolean {
            cleanedStoryKeys += storyKey
            return true
        }
    }

    private class FakePreviewEnvironmentCleaner : PreviewApi {
        override fun render(template: String?, prNumber: Int?): String? = PreviewApi.renderTemplate(template, prNumber)

        val cleanedNamespaces = mutableListOf<String>()

        override fun cleanup(namespace: String): Boolean {
            cleanedNamespaces += namespace
            return true
        }
    }

}
