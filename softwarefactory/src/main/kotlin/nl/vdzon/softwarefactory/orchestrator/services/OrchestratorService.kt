package nl.vdzon.softwarefactory.orchestrator.services

import nl.vdzon.softwarefactory.core.BoardState
import nl.vdzon.softwarefactory.core.StoryPipeline
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.core.AgentRuntime
import nl.vdzon.softwarefactory.core.CreditsPauseCoordinator
import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.core.OrchestratorPollResult
import nl.vdzon.softwarefactory.core.StoryRunRecord
import nl.vdzon.softwarefactory.core.StoryRunRepository
import nl.vdzon.softwarefactory.core.FactoryCommand
import nl.vdzon.softwarefactory.core.SubtaskSpec
import nl.vdzon.softwarefactory.core.SubtaskType
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.tracker.TrackerApi
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.core.StoryWorkspaceApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime

/**
 * Orchestrator-shell: één poll-cyclus over alle AI-issues + PR-monitoring.
 *
 * De daadwerkelijke story/subtask-verwerkingslogica zit in de [StoryPipeline]-engine;
 * deze klasse haalt de issues op, roept de pipeline per issue aan, monitort PR's en logt de uitkomst.
 * Zo blijft de pipeline-logica geïsoleerd en los te onderzoeken/herschrijven.
 */
@Service
class OrchestratorService(
    private val issueTrackerClient: TrackerApi,
    private val agentRuntime: AgentRuntime,
    private val storyRunRepository: StoryRunRepository,
    private val pullRequestClient: GitHubApi,
    private val previewApi: PreviewApi,
    private val storyWorkspaceService: StoryWorkspaceApi,
    private val creditsPauseCoordinator: CreditsPauseCoordinator,
    private val clock: Clock,
    // De story/subtask-verwerkingsengine (impl: StoryPipelineService).
    private val pipeline: StoryPipeline,
    // Hard, synchroon opruimen van een hele story (zie purgeStory). Default-construct uit de
    // eigen deps, zodat bestaande directe constructie (tests) blijft compileren; Spring injecteert
    // de @Service-bean.
    private val storyPurgeService: StoryPurgeService = StoryPurgeService(
        issueTrackerClient = issueTrackerClient,
        agentRuntime = agentRuntime,
        storyRunRepository = storyRunRepository,
        pullRequestClient = pullRequestClient,
        previewApi = previewApi,
        storyWorkspaceService = storyWorkspaceService,
    ),
) : OrchestratorApi {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun pollOnce(projectKey: String): OrchestratorPollResult {
        val t0 = System.nanoTime()
        val issues = issueTrackerClient.findWorkIssues()
        val t1 = System.nanoTime()
        val activeCreditsPause = creditsPauseCoordinator.activePause(OffsetDateTime.now(clock))
        if (activeCreditsPause != null) {
            return OrchestratorPollResult(issues.map { IssueProcessResult.Skipped(it.key, "credits-paused") })
        }
        val processed = issues.map { processIssue(it) }
        val t2 = System.nanoTime()
        val prResults = monitorPullRequests(issues.map { it.key }.toSet())
        val t3 = System.nanoTime()
        fun ms(from: Long, to: Long): Long = (to - from) / 1_000_000
        logger.info(
            "Poll-stappen: findWorkIssues={}ms, processIssues({})={}ms, prMonitor={}ms",
            ms(t0, t1),
            issues.size,
            ms(t1, t2),
            ms(t2, t3),
        )
        // DEBUG: compacte uitkomst per issue, zodat zichtbaar is waarom een (sub)taak blijft staan.
        logger.info("Poll-resultaten: {}", processed.joinToString(" | ") { "${it.storyKey}=${resultDetail(it)}" })
        return OrchestratorPollResult(processed + prResults)
    }

    override fun processIssue(issue: TrackerIssue): IssueProcessResult =
        pipeline.process(issue)

    override fun queueCommand(storyKey: String, command: FactoryCommand, reason: String?) {
        // De reden (bv. een afkeurreden bij reject) komt op een aparte regel ná het command-token mee,
        // zodat de command-parser het token herkent en de afhandeling de rest als reden kan lezen.
        val body = buildString {
            append("@factory:command:${command.token}")
            reason?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it.trim()) }
        }
        issueTrackerClient.postComment(storyKey, body)
    }

    override fun purgeStory(storyKey: String) {
        storyPurgeService.purgeStory(storyKey)
    }

    private fun monitorPullRequests(activeAiStoryKeys: Set<String>): List<IssueProcessResult> =
        storyRunRepository.activePullRequests()
            .filter { it.storyKey in activeAiStoryKeys }
            .mapNotNull { run ->
                runCatching {
                    monitorPullRequest(run)
                }.onFailure { exception ->
                    logger.warn("PR monitor failed for {}", run.storyKey, exception)
                }.getOrNull()
            }

    private fun monitorPullRequest(run: StoryRunRecord): IssueProcessResult? {
        val prNumber = run.prNumber ?: return null
        if (pullRequestClient.isMerged(run.targetRepo, prNumber)) {
            // Best-effort: faalt de preview-cleanup (bv. verlopen OpenShift-token), dan sluiten we de run
            // alsnog af i.p.v. te blokkeren — anders blijft de run actief en probeert elke poll het
            // opnieuw (herhaalde foutmelding in de log).
            runCatching { cleanupPreviewNamespace(run) }
                .onFailure { logger.warn("PR-monitor: preview-cleanup faalde voor {} (PR is al gemerged, genegeerd): {}", run.storyKey, it.message) }
            issueTrackerClient.transitionIssue(run.storyKey, BoardState.DONE.laneName)
            storyRunRepository.close(run.id, "merged", OffsetDateTime.now(clock))
            return IssueProcessResult.Merged(run.storyKey, prNumber)
        }

        if (agentRuntime.isAnyAgentRunningForStory(run.storyKey)) {
            return null
        }

        val comments = pullRequestClient.unprocessedFactoryComments(run.targetRepo, prNumber)
        if (comments.isEmpty()) {
            return null
        }
        comments.forEach { comment -> pullRequestClient.markCommentClaimed(run.targetRepo, comment.id) }
        // Fase 7 — v2: late PR-feedback wordt een nieuwe development-subtask op de story
        // (i.p.v. een story-phase-reset). De subtask krijgt meteen fase `start` zodat de keten 'm oppakt.
        val supplier = runCatching { issueTrackerClient.getIssue(run.storyKey).fields.aiSupplier }.getOrNull()
        val description = comments.joinToString("\n\n") { "- ${it.body}" }
        val subtask = issueTrackerClient.createSubtask(
            run.storyKey,
            SubtaskSpec(
                type = SubtaskType.DEVELOPMENT,
                title = "PR-feedback verwerken (PR #$prNumber)",
                description = "Verwerk de volgende PR-commentaren op de gedeelde branch:\n\n$description",
            ),
            supplier = supplier,
        )
        issueTrackerClient.updateIssueFields(
            subtask.key,
            TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.START.trackerValue),
        )
        return IssueProcessResult.PrCommentTriggered(run.storyKey, prNumber, comments.size)
    }

    private fun cleanupPreviewNamespace(run: StoryRunRecord): Boolean {
        val namespace = previewApi.render(run.previewNamespaceTemplate, run.prNumber) ?: return false
        return previewApi.cleanup(namespace)
    }

    /** DEBUG-detail per poll-uitkomst (zie pollOnce-logregel). */
    private fun resultDetail(result: IssueProcessResult): String =
        when (result) {
            is IssueProcessResult.Skipped -> "Skipped(${result.reason})"
            is IssueProcessResult.Dispatched -> "Dispatched(${result.role.markerKeyPart})"
            is IssueProcessResult.Recovered -> "Recovered(${result.phase})"
            is IssueProcessResult.Errored -> "Errored"
            is IssueProcessResult.Chained -> "Chained(${result.nextSubtaskKey ?: "-"})"
            is IssueProcessResult.Merged -> "Merged(#${result.prNumber})"
            else -> result::class.simpleName ?: "?"
        }
}
