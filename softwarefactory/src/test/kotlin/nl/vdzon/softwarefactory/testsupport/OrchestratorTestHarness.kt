package nl.vdzon.softwarefactory.testsupport

import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.core.contracts.ApkReleaseProbe
import nl.vdzon.softwarefactory.core.contracts.ManualCommandProcessor
import nl.vdzon.softwarefactory.core.contracts.OrchestratorSettings
import nl.vdzon.softwarefactory.core.contracts.StoryWorkspaceApi
import nl.vdzon.softwarefactory.core.contracts.TrackerComment
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.contracts.TrackerIssueFields
import nl.vdzon.softwarefactory.orchestrator.services.OrchestratorService
import nl.vdzon.softwarefactory.merge.internal.ProjectAwarePullRequestMergeService
import nl.vdzon.softwarefactory.pipeline.service.AgentDispatcher
import nl.vdzon.softwarefactory.pipeline.service.DeploySubtaskHandler
import nl.vdzon.softwarefactory.pipeline.service.MergeSubtaskHandler
import nl.vdzon.softwarefactory.pipeline.service.StoryPipelineService
import nl.vdzon.softwarefactory.pipeline.service.StoryRefinementCoordinator
import nl.vdzon.softwarefactory.pipeline.service.SubtaskExecutionCoordinator
import nl.vdzon.softwarefactory.tracker.services.ProcessedCommentService
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Gedeelde test-harness voor orchestrator-tests: een vaste klok plus de volledige
 * [OrchestratorService]-wiring op basis van de fakes in dit package. Testklassen erven
 * hiervan en bouwen een service via [service]; issues maken ze met de [issue]-factory.
 */
abstract class OrchestratorTestHarness {
    protected val now: OffsetDateTime = OffsetDateTime.parse("2026-05-23T20:00:00Z")
    protected val clock: Clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC)

    /** Wired een [OrchestratorService] met fakes; elke collaborator is per test te overriden. */
    protected fun service(
        issueTracker: FakeTrackerApi,
        runtime: FakeAgentRuntime = FakeAgentRuntime(now),
        storyRuns: InMemoryStoryRunRepository = InMemoryStoryRunRepository(),
        agentRuns: InMemoryAgentRunRepository = InMemoryAgentRunRepository(),
        pullRequests: FakeGitHubApi = FakeGitHubApi(),
        processedCommentStore: InMemoryProcessedCommentStore = InMemoryProcessedCommentStore(),
        previewCleaner: FakePreviewEnvironmentCleaner = FakePreviewEnvironmentCleaner(),
        storyWorkspaceService: StoryWorkspaceApi = FakeStoryWorkspaceService(),
        costMonitor: FakeCostMonitor = FakeCostMonitor(),
        creditsPauseCoordinator: FakeCreditsPauseCoordinator = FakeCreditsPauseCoordinator(),
        manualCommandProcessor: ManualCommandProcessor = NoopManualCommandProcessor(),
        projectRepoResolver: ProjectConfiguration = ProjectConfiguration(
            repos = mapOf("demo" to "git@example/repo.git"),
            requiredChecks = mapOf("demo" to setOf("Repository verification")),
        ),
    ): OrchestratorService {
        val settings = OrchestratorSettings(
            pollInterval = Duration.ofSeconds(15),
            maxParallelRefiner = 1,
            maxParallelDeveloper = 2,
            maxParallelReviewer = 2,
            maxParallelTester = 1,
            maxParallelTotal = 4,
            maxDeveloperLoopbacks = 5,
            maxTransientRetries = 2,
            hardTimeout = Duration.ofMinutes(60),
            costMonitorInterval = Duration.ofMinutes(5),
            creditsPauseDefault = Duration.ofMinutes(30),
        )
        val dispatcher = AgentDispatcher(
            issueTrackerClient = issueTracker,
            agentRuntime = runtime,
            storyRunRepository = storyRuns,
            agentRunRepository = agentRuns,
            pullRequestClient = pullRequests,
            processedCommentService = ProcessedCommentService(issueTracker, processedCommentStore),
            previewApi = previewCleaner,
            storyWorkspaceService = storyWorkspaceService,
            costMonitor = costMonitor,
            projectRepoResolver = projectRepoResolver,
            settings = settings,
            clock = clock,
        )
        val pipeline = StoryPipelineService(
            issueTrackerClient = issueTracker,
            costMonitor = costMonitor,
            manualCommandProcessor = manualCommandProcessor,
            storyRefinementCoordinator = StoryRefinementCoordinator(
                issueTrackerClient = issueTracker,
                agentRuntime = runtime,
                storyRunRepository = storyRuns,
                agentRunRepository = agentRuns,
                settings = settings,
                clock = clock,
                dispatcher = dispatcher,
            ),
            subtaskExecutionCoordinator = SubtaskExecutionCoordinator(
                issueTrackerClient = issueTracker,
                agentRuntime = runtime,
                storyRunRepository = storyRuns,
                agentRunRepository = agentRuns,
                projectRepoResolver = projectRepoResolver,
                settings = settings,
                clock = clock,
                dispatcher = dispatcher,
                // De handlers zijn gewone beans; advanceChain gaat per process-aanroep mee.
                mergeHandler = MergeSubtaskHandler(
                    issueTrackerClient = issueTracker,
                    storyRunRepository = storyRuns,
                    pullRequestMergeService = ProjectAwarePullRequestMergeService(pullRequests, projectRepoResolver),
                ),
                deployHandler = DeploySubtaskHandler(
                    issueTrackerClient = issueTracker,
                    projectRepoResolver = projectRepoResolver,
                    clock = clock,
                    factoryEnvironmentProvider = object : ConfigApi {
                        override fun resolvedValues(): Map<String, String> = emptyMap()
                    },
                    deploymentStatusProbe = { _, _ -> null },
                    storyRunRepository = storyRuns,
                    gitHubApi = pullRequests,
                    apkReleaseProbe = ApkReleaseProbe { _, _, _ -> null },
                ),
            ),
        )
        return OrchestratorService(
            issueTrackerClient = issueTracker,
            agentRuntime = runtime,
            storyRunRepository = storyRuns,
            pullRequestClient = pullRequests,
            previewApi = previewCleaner,
            storyWorkspaceService = storyWorkspaceService,
            creditsPauseCoordinator = creditsPauseCoordinator,
            clock = clock,
            pipeline = pipeline,
        )
    }

    /** Bouwt een [TrackerIssue] met dezelfde defaults als de oorspronkelijke god-test. */
    protected fun issue(
        key: String,
        phase: String? = null,
        // Default `start`: de meeste tests verwachten dat de orchestrator de issue oppakt.
        // Tests voor de 'niet gestart'-gate geven expliciet storyPhase/subtaskPhase = null mee.
        storyPhase: String? = "start",
        paused: Boolean = false,
        error: String? = null,
        targetRepo: String? = "git@example/repo.git",
        repo: String? = "demo",
        agentStartedAt: OffsetDateTime? = null,
        description: String? = "Beschrijving voor $key",
        comments: List<TrackerComment> = emptyList(),
        aiSupplier: String = "claude",
        maxDeveloperLoopbacks: Int? = null,
        maxTestChainResets: Int? = null,
        type: String? = null,
        subtaskPhase: String? = "start",
        subtaskType: String? = null,
        autoApprove: Boolean = false,
        silent: Boolean = false,
    ): TrackerIssue =
        TrackerIssue(
            key = key,
            summary = "Story $key",
            description = description,
            status = "Develop",
            fields = TrackerIssueFields(
                targetRepo = targetRepo,
                repo = repo,
                aiSupplier = aiSupplier,
                aiPhase = phase,
                aiLevel = 5,
                aiMaxDeveloperLoopbacks = maxDeveloperLoopbacks,
                aiMaxTestChainResets = maxTestChainResets,
                aiTokenBudget = 100000,
                aiTokensUsed = 0,
                agentStartedAt = agentStartedAt,
                paused = paused,
                silent = silent,
                error = error,
                storyPhase = storyPhase,
                type = type,
                subtaskPhase = subtaskPhase,
                subtaskType = subtaskType,
                autoApprove = autoApprove,
            ),
            comments = comments,
        )
}
