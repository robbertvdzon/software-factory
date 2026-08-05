package nl.vdzon.softwarefactory.pipeline

import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.ApprovalMode
import nl.vdzon.softwarefactory.core.contracts.IssueProcessResult
import nl.vdzon.softwarefactory.core.contracts.OrchestratorSettings
import nl.vdzon.softwarefactory.core.contracts.StoryPhase
import nl.vdzon.softwarefactory.core.contracts.SubtaskPhase
import nl.vdzon.softwarefactory.core.contracts.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.contracts.TrackerIssueFields
import nl.vdzon.softwarefactory.pipeline.service.AgentDispatcher
import nl.vdzon.softwarefactory.core.contracts.SubtaskSpec
import nl.vdzon.softwarefactory.pipeline.service.StoryRefinementCoordinator
import nl.vdzon.softwarefactory.runtime.SubtaskMaterializationApi
import nl.vdzon.softwarefactory.testsupport.FakeAgentRuntime
import nl.vdzon.softwarefactory.testsupport.FakeCostMonitor
import nl.vdzon.softwarefactory.testsupport.FakeGitHubApi
import nl.vdzon.softwarefactory.testsupport.FakePreviewEnvironmentCleaner
import nl.vdzon.softwarefactory.testsupport.FakeStoryWorkspaceService
import nl.vdzon.softwarefactory.testsupport.InMemoryAgentRunRepository
import nl.vdzon.softwarefactory.testsupport.InMemoryProcessedCommentStore
import nl.vdzon.softwarefactory.testsupport.InMemoryStoryRunRepository
import nl.vdzon.softwarefactory.tracker.TrackerApi
import nl.vdzon.softwarefactory.tracker.services.ProcessedCommentService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class StoryRefinementCoordinatorAutoStartTest {

    private val now = OffsetDateTime.parse("2026-01-01T10:00:00Z")
    private val fixedClock = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"), ZoneOffset.UTC)
    private val settings = OrchestratorSettings(
        pollInterval = Duration.ofSeconds(1),
        maxParallelRefiner = 1,
        maxParallelDeveloper = 1,
        maxParallelReviewer = 1,
        maxParallelTester = 1,
        maxParallelTotal = 4,
        maxDeveloperLoopbacks = 3,
        maxTransientRetries = 2,
        hardTimeout = Duration.ofMinutes(60),
        costMonitorInterval = Duration.ofMinutes(5),
        creditsPauseDefault = Duration.ofMinutes(30),
    )

    @Test
    fun `processStoryRefinement auto-starts first subtask and sets story to in-progress when autoApprove is true`() {
        val subtask = subtaskIssue("SF-2", subtaskPhase = null)
        val tracker = FakeTracker(subtasks = listOf(subtask))
        val coordinator = createCoordinator(tracker)
        val story = storyIssue("SF-1", StoryPhase.PLANNING_APPROVED, autoApprove = true)

        val result = coordinator.processStoryRefinement(story)

        assertTrue(result is IssueProcessResult.Recovered, "Verwacht Recovered maar was $result")
        assertEquals(
            StoryPhase.IN_PROGRESS.trackerValue,
            tracker.lastFieldUpdateFor("SF-1", TrackerField.STORY_PHASE),
        )
        assertEquals(
            SubtaskPhase.START.trackerValue,
            tracker.lastFieldUpdateFor("SF-2", TrackerField.SUBTASK_PHASE),
        )
    }

    @Test
    fun `processStoryRefinement skips when development already started (idempotent)`() {
        val subtask = subtaskIssue("SF-2", subtaskPhase = SubtaskPhase.DEVELOPING.trackerValue)
        val tracker = FakeTracker(subtasks = listOf(subtask))
        val coordinator = createCoordinator(tracker)
        val story = storyIssue("SF-1", StoryPhase.PLANNING_APPROVED, autoApprove = true)

        val result = coordinator.processStoryRefinement(story)

        assertTrue(result is IssueProcessResult.Skipped, "Verwacht Skipped maar was $result")
        assertEquals("development-already-started", (result as IssueProcessResult.Skipped).reason)
        assertTrue(tracker.allUpdates.isEmpty(), "Geen updates verwacht bij idempotente skip")
    }

    @Test
    fun `processStoryRefinement skips without auto-starting when autoApprove is false`() {
        val subtask = subtaskIssue("SF-2", subtaskPhase = null)
        val tracker = FakeTracker(subtasks = listOf(subtask))
        val coordinator = createCoordinator(tracker)
        val story = storyIssue("SF-1", StoryPhase.PLANNING_APPROVED, autoApprove = false)

        val result = coordinator.processStoryRefinement(story)

        assertTrue(result is IssueProcessResult.Skipped, "Verwacht Skipped maar was $result")
        assertEquals("refinement-done", (result as IssueProcessResult.Skipped).reason)
        assertTrue(tracker.allUpdates.isEmpty(), "Geen updates verwacht wanneer autoApprove=false")
    }

    // ── SF-1959: hotfix-routing op `Story Phase = start` ──────────────────────────

    @Test
    fun `hotfix-story op start materialiseert de kale keten en start zonder refiner`() {
        val subtask = subtaskIssue("SF-2", subtaskPhase = null, subtaskType = "hotfix")
        val tracker = FakeTracker(subtasks = listOf(subtask))
        val materialization = RecordingMaterialization()
        val coordinator = createCoordinator(tracker, materialization)
        val story = storyIssue("SF-1", StoryPhase.START, autoApprove = true, hotfix = true)

        val result = coordinator.processStoryRefinement(story)

        assertTrue(result is IssueProcessResult.Recovered, "Verwacht Recovered maar was $result")
        assertEquals(
            listOf("hotfix", "merge", "deploy"),
            materialization.calls.single().second.map { it.type.trackerValue },
            "exact [hotfix, merge, deploy]: geen review/test/summary/documentation/manual-approve",
        )
        assertEquals("SF-1", materialization.calls.single().first)
        assertEquals(
            SubtaskPhase.START.trackerValue,
            tracker.lastFieldUpdateFor("SF-2", TrackerField.SUBTASK_PHASE),
        )
        assertEquals(
            StoryPhase.IN_PROGRESS.trackerValue,
            tracker.lastFieldUpdateFor("SF-1", TrackerField.STORY_PHASE),
        )
        // Nooit `refining`/`planning`: er is geen enkele fase-schrijving met een agent-fase.
        assertTrue(
            tracker.allUpdates.none { it.third == StoryPhase.REFINING.trackerValue || it.third == StoryPhase.PLANNING.trackerValue },
            "hotfix mag geen refiner/planner starten, kreeg: ${tracker.allUpdates}",
        )
    }

    @Test
    fun `hotfix-story negeert ApprovalMode elke-stap`() {
        val subtask = subtaskIssue("SF-2", subtaskPhase = null, subtaskType = "hotfix")
        val tracker = FakeTracker(subtasks = listOf(subtask))
        val materialization = RecordingMaterialization()
        val coordinator = createCoordinator(tracker, materialization)
        val story = storyIssue("SF-1", StoryPhase.START, autoApprove = false, hotfix = true)

        coordinator.processStoryRefinement(story)

        assertEquals(
            listOf("hotfix", "merge", "deploy"),
            materialization.calls.single().second.map { it.type.trackerValue },
        )
        assertEquals(
            StoryPhase.IN_PROGRESS.trackerValue,
            tracker.lastFieldUpdateFor("SF-1", TrackerField.STORY_PHASE),
        )
    }

    @Test
    fun `hotfix-start is idempotent op een al gestarte subtaak`() {
        val subtask = subtaskIssue("SF-2", subtaskPhase = SubtaskPhase.DEVELOPING.trackerValue, subtaskType = "hotfix")
        val tracker = FakeTracker(subtasks = listOf(subtask))
        val coordinator = createCoordinator(tracker, RecordingMaterialization())
        val story = storyIssue("SF-1", StoryPhase.START, autoApprove = true, hotfix = true)

        coordinator.processStoryRefinement(story)

        assertTrue(
            tracker.allUpdates.none { it.first == "SF-2" },
            "een lopende hotfix-subtaak mag niet terug op `start` gezet worden: ${tracker.allUpdates}",
        )
    }

    @Test
    fun `niet-hotfix-story op start gaat naar de refiner-tak, niet naar de hotfix-keten`() {
        val tracker = FakeTracker()
        val materialization = RecordingMaterialization()
        val coordinator = createCoordinator(tracker, materialization)
        val story = storyIssue("SF-1", StoryPhase.START, autoApprove = true, hotfix = false)

        coordinator.processStoryRefinement(story)

        // Regressie op AC 10: zonder hotfix gaat `start` naar de dispatcher (refiner-tak) en wordt
        // er nooit een subtaak-keten gematerialiseerd of een story op in-progress gezet.
        assertTrue(materialization.calls.isEmpty(), "geen subtaak-materialisatie zonder hotfix")
        assertTrue(
            tracker.allUpdates.none { it.third == StoryPhase.IN_PROGRESS.trackerValue },
            "zonder hotfix hoort de story eerst te refinen, kreeg: ${tracker.allUpdates}",
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private fun createCoordinator(
        tracker: TrackerApi,
        subtaskMaterialization: SubtaskMaterializationApi? = null,
    ): StoryRefinementCoordinator {
        // Gedeelde fakes uit nl.vdzon.softwarefactory.testsupport.
        val storyRunRepo = InMemoryStoryRunRepository()
        val agentRunRepo = InMemoryAgentRunRepository()
        val agentRuntime = FakeAgentRuntime(now)
        val processedCommentStore = InMemoryProcessedCommentStore()
        val dispatcher = AgentDispatcher(
            issueTrackerClient = tracker,
            agentRuntime = agentRuntime,
            storyRunRepository = storyRunRepo,
            agentRunRepository = agentRunRepo,
            pullRequestClient = FakeGitHubApi(),
            processedCommentService = ProcessedCommentService(tracker, processedCommentStore),
            previewApi = FakePreviewEnvironmentCleaner(),
            storyWorkspaceService = FakeStoryWorkspaceService(),
            costMonitor = FakeCostMonitor(),
            projectRepoResolver = ProjectConfiguration(emptyMap()),
            settings = settings,
            clock = fixedClock,
        )
        return StoryRefinementCoordinator(
            tracker, agentRuntime, storyRunRepo, agentRunRepo, settings, fixedClock, dispatcher, subtaskMaterialization,
        )
    }

    private fun storyIssue(key: String, phase: StoryPhase, autoApprove: Boolean, hotfix: Boolean = false): TrackerIssue =
        TrackerIssue(
            key = key,
            summary = "Story $key",
            description = null,
            status = "",
            comments = emptyList(),
            fields = TrackerIssueFields(
                targetRepo = null,
                aiPhase = null,
                aiLevel = null,
                aiTokenBudget = null,
                aiTokensUsed = null,
                agentStartedAt = null,
                paused = false,
                error = null,
                storyPhase = phase.trackerValue,
                hotfix = hotfix,
                approvalMode = if (autoApprove) ApprovalMode.AUTOMATIC.trackerValue else ApprovalMode.EVERY_STEP.trackerValue,
            ),
        )

    private fun subtaskIssue(key: String, subtaskPhase: String?, subtaskType: String = "development"): TrackerIssue =
        TrackerIssue(
            key = key,
            summary = "Subtask $key",
            description = null,
            status = "",
            comments = emptyList(),
            fields = TrackerIssueFields(
                targetRepo = null,
                aiPhase = null,
                aiLevel = null,
                aiTokenBudget = null,
                aiTokensUsed = null,
                agentStartedAt = null,
                paused = false,
                error = null,
                type = "Task",
                subtaskPhase = subtaskPhase,
                subtaskType = subtaskType,
            ),
        )

    // ── stubs ─────────────────────────────────────────────────────────────────────

    /** Registreert de exact-list-materialisatie van de hotfix-keten (SF-1959). */
    private class RecordingMaterialization : SubtaskMaterializationApi {
        val calls: MutableList<Pair<String, List<SubtaskSpec>>> = mutableListOf()

        override fun materializeFromSpecs(storyKey: String, specs: List<SubtaskSpec>) {
            calls += storyKey to specs
        }
    }

    private class FakeTracker(
        private val subtasks: List<TrackerIssue> = emptyList(),
    ) : TrackerApi {
        val allUpdates: MutableList<Triple<String, TrackerField, String?>> = mutableListOf()

        override fun subtasksOf(parentKey: String): List<TrackerIssue> = subtasks
        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
            update.values.forEach { (field, value) -> allUpdates += Triple(issueKey, field, value?.toString()) }
        }
        override fun getIssue(issueKey: String): TrackerIssue =
            subtasks.firstOrNull { it.key == issueKey } ?: throw NoSuchElementException(issueKey)
        override fun transitionIssue(issueKey: String, statusName: String) = Unit
        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment =
            throw UnsupportedOperationException()

        fun lastFieldUpdateFor(issueKey: String, field: TrackerField): String? =
            allUpdates.lastOrNull { it.first == issueKey && it.second == field }?.third
    }
}
