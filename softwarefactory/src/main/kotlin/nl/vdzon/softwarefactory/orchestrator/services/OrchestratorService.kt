package nl.vdzon.softwarefactory.orchestrator.services

import nl.vdzon.softwarefactory.config.ProjectRepositoryCatalog
import nl.vdzon.softwarefactory.core.contracts.BoardState
import nl.vdzon.softwarefactory.core.contracts.StoryPhase
import nl.vdzon.softwarefactory.core.contracts.StoryPipeline
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.core.contracts.AgentRuntime
import nl.vdzon.softwarefactory.core.contracts.CreditsPauseCoordinator
import nl.vdzon.softwarefactory.core.contracts.IssueProcessResult
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.core.contracts.OrchestratorPollResult
import nl.vdzon.softwarefactory.core.contracts.OrchestratorSettings
import nl.vdzon.softwarefactory.core.contracts.StoryRunRecord
import nl.vdzon.softwarefactory.core.contracts.StoryRunRepository
import nl.vdzon.softwarefactory.core.contracts.FactoryCommand
import nl.vdzon.softwarefactory.core.contracts.SubtaskSpec
import nl.vdzon.softwarefactory.core.contracts.SubtaskType
import nl.vdzon.softwarefactory.core.contracts.SubtaskPhase
import nl.vdzon.softwarefactory.tracker.TrackerCapabilities
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.core.contracts.StoryWorkspaceApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
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
    private val issueTrackerClient: TrackerCapabilities,
    private val agentRuntime: AgentRuntime,
    private val storyRunRepository: StoryRunRepository,
    private val pullRequestClient: GitHubApi,
    private val previewApi: PreviewApi,
    private val storyWorkspaceService: StoryWorkspaceApi,
    private val creditsPauseCoordinator: CreditsPauseCoordinator,
    private val projectRepoResolver: ProjectRepositoryCatalog,
    private val clock: Clock,
    private val settings: OrchestratorSettings,
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

    override fun pollOnce(): OrchestratorPollResult {
        val t0 = System.nanoTime()
        val issues = issueTrackerClient.findWorkIssues()
        val t1 = System.nanoTime()
        val activeCreditsPause = creditsPauseCoordinator.activePause(OffsetDateTime.now(clock))
        if (activeCreditsPause != null) {
            return OrchestratorPollResult(issues.map { IssueProcessResult.Skipped(it.key, "credits-paused") })
        }
        promoteQueuedStories(issues)
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

    /**
     * Wachtrij (`start-next`): promoot per target-repo hoogstens één story naar `start`, en alleen
     * als er voor die repo geen enkele andere story nog een open run heeft (zie
     * [StoryRunRepository.activeRunForRepo] — refining/planning/in-progress tellen allemaal als
     * bezig; pas merged/closed/deleted maakt de repo weer vrij). Bij meerdere kandidaten wint het
     * laagste story-nummer. Werkt op de al opgehaalde `issues`-batch, dus de promotie zelf wordt
     * pas de volgende poll-cyclus daadwerkelijk gedispatcht (voorkomt dat twee kandidaten voor
     * dezelfde repo in één cyclus allebei "vrij" zien en beide promoveren).
     */
    private fun promoteQueuedStories(issues: List<TrackerIssue>) {
        val queued = issues.filter { StoryPhase.fromTracker(it.fields.storyPhase) == StoryPhase.START_NEXT }
        if (queued.isEmpty()) return
        queued.groupBy { projectRepoResolver.resolve(it.fields.repo) }
            .forEach { (targetRepo, candidates) ->
                if (targetRepo.isNullOrBlank()) return@forEach
                val blockingRun = storyRunRepository.activeRunForRepo(targetRepo)
                if (blockingRun != null) {
                    warnIfQueueBlockedTooLong(blockingRun, targetRepo)
                    return@forEach
                }
                val winner = candidates.minByOrNull { storyNumber(it.key) } ?: return@forEach
                issueTrackerClient.updateIssueFields(
                    winner.key,
                    TrackerFieldUpdate.of(TrackerField.STORY_PHASE to StoryPhase.START.trackerValue),
                )
                logger.info(
                    "Wachtrij: {} gepromoot naar `start` voor repo {} (laagste story-nummer, {} kandidaten).",
                    winner.key,
                    targetRepo,
                    candidates.size,
                )
            }
    }

    /**
     * SF-1460 — zichtbaarheid voor een start-next-promotie die al langer dan
     * [OrchestratorSettings.blockedQueueWarnThreshold] geblokkeerd wordt door dezelfde openstaande
     * run voor [targetRepo]. Alleen een WARN-logregel; geen automatische sluiting (kan werk weggooien).
     */
    private fun warnIfQueueBlockedTooLong(blockingRun: StoryRunRecord, targetRepo: String) {
        val startedAt = blockingRun.startedAt ?: return
        val openFor = Duration.between(startedAt, OffsetDateTime.now(clock))
        if (openFor < settings.blockedQueueWarnThreshold) return
        logger.warn(
            "Wachtrij geblokkeerd: story {} (run-id {}) houdt repo {} al {} minuten open; " +
                "start-next-promotie voor deze repo staat stil.",
            blockingRun.storyKey,
            blockingRun.id,
            targetRepo,
            openFor.toMinutes(),
        )
    }

    private fun storyNumber(storyKey: String): Int = storyKey.substringAfterLast('-').toIntOrNull() ?: Int.MAX_VALUE

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
            finishPullRequestRun(run, finalStatus = "merged", cleanupContext = "PR is al gemerged")
            return IssueProcessResult.Merged(run.storyKey, prNumber)
        }

        // Handmatig gesloten zonder te mergen (buiten de factory om, bv. rechtstreeks op GitHub):
        // zonder deze check bleef de run voor altijd "open" pollen en de preview-namespace verweesd
        // achter (zie docs/cluster-inventory.md — orphaned pnf-pr-*/robberts-assistent-pr-*-namespaces).
        if (pullRequestClient.isClosed(run.targetRepo, prNumber)) {
            finishPullRequestRun(run, finalStatus = "closed", cleanupContext = "PR is gesloten zonder te mergen")
            return IssueProcessResult.Closed(run.storyKey, prNumber)
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

    /**
     * Ruimt de preview-namespace op en sluit de story-run af, ongeacht of de cleanup lukt (best-effort:
     * anders blijft de run actief en probeert elke poll het opnieuw — herhaalde foutmelding in de log,
     * zonder ooit verder te komen). Faalt de cleanup toch, dan schrijven we dat als [TrackerField.ERROR]
     * naar de story i.p.v. 'm alleen te loggen — anders verdwijnt de verweesde namespace stilletjes
     * uit beeld zodra de run/story naar Done gaat.
     */
    private fun finishPullRequestRun(run: StoryRunRecord, finalStatus: String, cleanupContext: String) {
        runCatching { cleanupPreviewNamespace(run) }
            .onFailure { failure ->
                logger.error("PR-monitor: preview-cleanup faalde voor {} ({}, genegeerd): {}", run.storyKey, cleanupContext, failure.message)
                recordOrphanedPreviewNamespace(run, failure)
            }
        issueTrackerClient.transitionIssue(run.storyKey, BoardState.DONE.laneName)
        storyRunRepository.close(run.id, finalStatus, OffsetDateTime.now(clock))
    }

    private fun cleanupPreviewNamespace(run: StoryRunRecord): Boolean {
        val namespace = previewApi.render(run.previewNamespaceTemplate, run.prNumber) ?: return false
        return previewApi.cleanup(namespace)
    }

    private fun recordOrphanedPreviewNamespace(run: StoryRunRecord, failure: Throwable) {
        val namespace = runCatching { previewApi.render(run.previewNamespaceTemplate, run.prNumber) }.getOrNull()
        val note = "[ORCHESTRATOR] Preview-cleanup mislukt voor namespace '${namespace ?: "onbekend"}': " +
            "${failure.message}. Handmatig opruimen: oc delete project ${namespace ?: "<namespace>"}"
        runCatching {
            issueTrackerClient.updateIssueFields(run.storyKey, TrackerFieldUpdate.of(TrackerField.ERROR to note))
        }.onFailure { logger.warn("Kon cleanup-fout niet naar tracker schrijven voor {}: {}", run.storyKey, it.message) }
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
            is IssueProcessResult.Closed -> "Closed(#${result.prNumber})"
            else -> result::class.simpleName ?: "?"
        }
}
