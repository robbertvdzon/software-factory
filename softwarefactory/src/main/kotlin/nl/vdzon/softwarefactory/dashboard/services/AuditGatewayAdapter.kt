package nl.vdzon.softwarefactory.dashboard.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import nl.vdzon.softwarefactory.audit.AuditGateway
import nl.vdzon.softwarefactory.audit.models.AuditDispatchHandle
import nl.vdzon.softwarefactory.audit.models.AuditOutcome
import nl.vdzon.softwarefactory.audit.models.AuditReportResult
import nl.vdzon.softwarefactory.audit.repositories.AuditReportRepository
import nl.vdzon.softwarefactory.audit.services.AuditJob
import nl.vdzon.softwarefactory.audit.services.AuditJobsReader
import nl.vdzon.softwarefactory.audit.types.AuditOutcomeStatus
import nl.vdzon.softwarefactory.config.ProjectDashboardSettings
import nl.vdzon.softwarefactory.contract.AgentResultFile
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AgentDispatchRequest
import nl.vdzon.softwarefactory.core.contracts.AgentRunCompletionRecord
import nl.vdzon.softwarefactory.core.contracts.AgentRunRepository
import nl.vdzon.softwarefactory.core.contracts.AgentRunStart
import nl.vdzon.softwarefactory.core.contracts.AgentRuntime
import nl.vdzon.softwarefactory.core.contracts.AiRouting
import nl.vdzon.softwarefactory.core.contracts.StoryPhase
import nl.vdzon.softwarefactory.core.contracts.StoryRunRepository
import nl.vdzon.softwarefactory.core.contracts.StoryWorkspaceApi
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import nl.vdzon.softwarefactory.knowledge.models.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.tracker.TrackerCapabilities
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Adapter die de [AuditGateway]-poort van de audit-module invult: dispatcht de auditor-agent
 * rechtstreeks via [AgentRuntime] (zelfde Docker-uitvoeringspad als een gewone subtaak, maar
 * buiten `AgentDispatcher`/de Subtask-koppeling om — een audit heeft geen tracker-story) en
 * verwerkt de uitkomst (rapport, memory-tips, evt. 1 voorgestelde vervolg-story).
 */
@Component
class AuditGatewayAdapter(
    private val auditJobsReader: AuditJobsReader,
    private val projects: ProjectDashboardSettings,
    private val auditReportRepository: AuditReportRepository,
    private val agentRuntime: AgentRuntime,
    private val agentRunRepository: AgentRunRepository,
    private val storyRunRepository: StoryRunRepository,
    private val storyWorkspaceService: StoryWorkspaceApi,
    private val tracker: TrackerCapabilities,
    private val knowledgeApi: KnowledgeApi,
    private val objectMapper: ObjectMapper,
) : AuditGateway {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun allJobs(): List<AuditJob> {
        val repos = projects.projectNames().mapNotNull { name -> projects.repoFor(name)?.let { name to it } }
        return auditJobsReader.readAll(repos).jobs
    }

    override fun startAudit(project: String, auditType: String): AuditDispatchHandle {
        val repo = projects.repoFor(project) ?: error("Onbekend project: $project")
        val detail = auditJobsReader.readJob(repo, project, auditType)
            ?: error("Audit niet gevonden: $project/$auditType")
        val route = AiRouting.resolve(null, detail.job.aiSupplier, AgentRole.AUDITOR)
        val model = detail.job.aiModel?.takeIf { it.isNotBlank() } ?: route.model

        // Synthetische, stabiele sleutel i.p.v. een tracker-storyKey: een audit heeft geen story.
        // Stabiel (geen timestamp) zodat `openOrCreate` een eventueel nog-open run van een vorige,
        // niet-afgeronde poging hergebruikt i.p.v. verweesde story_runs-rijen te laten opstapelen.
        val syntheticKey = "AUDIT:$project:$auditType"
        val storyRun = storyRunRepository.openOrCreate(syntheticKey, repo)

        // Kloont/hergebruikt de repo-checkout op de base-branch (AUDITOR: read-only, geen eigen
        // branch — zie StoryWorkspaceService.prepare) zodat de agentworker /work/repo gemount vindt;
        // zonder deze stap dispatcht agentRuntime.dispatch() een lege workspace en faalt de auditor
        // meteen op "Target repository is not mounted".
        val workspace = storyWorkspaceService.prepare(storyRun, AgentRole.AUDITOR)

        val request = AgentDispatchRequest(
            storyKey = syntheticKey,
            targetRepo = repo,
            storyRunId = storyRun.id,
            role = AgentRole.AUDITOR,
            phase = "auditing",
            aiSupplier = detail.job.aiSupplier,
            aiModel = model,
            trackerContext = auditTaskContext(project, auditType, detail.prompt),
            workspacePath = workspace.workspacePath.toString(),
        )
        val result = agentRuntime.dispatch(request)
        logger.info("Audit {}/{} gestart als container {}.", project, auditType, result.containerName)

        // Audits gaan buiten AgentDispatcher om (geen Subtask), maar horen wel als lopende agent-run
        // in het Agents-scherm te verschijnen — zelfde recordStarted+captureLogs-paar als daar.
        val agentRunId = agentRunRepository.recordStarted(
            AgentRunStart(
                storyRunId = storyRun.id,
                role = AgentRole.AUDITOR,
                containerName = result.containerName,
                model = model,
                effort = route.effort,
                level = route.level,
                workspacePath = result.workspacePath,
            ),
        )
        runCatching { agentRuntime.captureLogs(result.containerName, agentRunId) }
            .onFailure { logger.warn("Audit-logcapture kon niet starten voor {}.", result.containerName, it) }

        return AuditDispatchHandle(
            containerName = result.containerName,
            workspacePath = result.workspacePath,
            storyRunId = storyRun.id,
        )
    }

    /** `.task.md`-body: de audit-prompt plus de laatste eerdere rapporten als historische context. */
    private fun auditTaskContext(project: String, auditType: String, prompt: String): String {
        val history = auditReportRepository.recentFor(project, auditType, limit = HISTORY_LIMIT)
        return buildString {
            appendLine("### Audit-instructie")
            appendLine()
            appendLine(prompt.trim())
            if (history.isNotEmpty()) {
                appendLine()
                appendLine("### Eerdere rapporten (nieuwste eerst)")
                history.forEach { report ->
                    appendLine()
                    val scoreSuffix = report.score?.let { " — score: ${report.scoreLabel ?: it}" }.orEmpty()
                    appendLine("#### ${report.generatedAt}$scoreSuffix")
                    appendLine(report.content.trim())
                }
            }
        }
    }

    override fun auditOutcome(handle: AuditDispatchHandle): AuditOutcome {
        if (agentRuntime.isContainerRunning(handle.containerName)) {
            return AuditOutcome(status = AuditOutcomeStatus.RUNNING, startedAt = null, endedAt = null, costUsd = 0.0)
        }

        val resultFile = handle.workspacePath?.let { Path.of(it).resolve("agent-result.json") }
        val result = resultFile?.takeIf { it.exists() }?.let {
            runCatching { objectMapper.readValue<AgentResultFile>(it.readText()) }.getOrNull()
        }
        val now = OffsetDateTime.now()
        storyRunRepository.close(handle.storyRunId, result?.outcome ?: "error", now)

        val completionRecord = AgentRunCompletionRecord(
            outcome = result?.outcome ?: "error",
            inputTokens = result?.inputTokens ?: 0,
            outputTokens = result?.outputTokens ?: 0,
            cacheReadInputTokens = result?.cacheReadInputTokens ?: 0,
            cacheCreationInputTokens = result?.cacheCreationInputTokens ?: 0,
            numTurns = result?.numTurns ?: 0,
            durationMs = result?.durationMs ?: 0,
            costUsdEst = result?.costUsdEst ?: 0.0,
            summaryText = result?.summaryText,
        )
        agentRunRepository.complete(handle.containerName, completionRecord, now)
        agentRunRepository.addUsageToStoryRun(handle.storyRunId, completionRecord)

        if (result == null || result.exitCode != 0) {
            val error = result?.summaryText?.take(2000)
                ?: "Audit-container gestopt zonder agent-result.json te schrijven."
            logger.warn("Audit-run {} mislukt: {}", handle.containerName, error)
            return AuditOutcome(status = AuditOutcomeStatus.FAILED, startedAt = null, endedAt = now, costUsd = 0.0, error = error)
        }

        val (project, auditType) = projectAndTypeFrom(result.storyKey)
        // Zelfde targetRepo-vorm als bij dispatch (AgentWorkspaceFactory.tipsPayload leest memory op
        // exact `AgentDispatchRequest.targetRepo`, d.w.z. de repo-URL, niet de projectnaam) — anders
        // zou een opgeslagen tip nooit meer terug in agent-tips.md verschijnen.
        val repo = projects.repoFor(project)
        result.knowledgeUpdates.forEach { update ->
            if (repo == null) return@forEach
            runCatching {
                knowledgeApi.upsert(
                    AgentKnowledgeUpdateRequest(
                        targetRepo = repo,
                        role = AgentRole.AUDITOR.markerKeyPart,
                        category = update.category,
                        key = update.key,
                        content = update.content,
                        updatedByStory = result.storyKey,
                    ),
                )
            }.onFailure { logger.warn("Kon audit-memory-tip niet opslaan ({}/{}).", update.category, update.key, it) }
        }

        val proposedStoryKey = proposeStoryIfAny(project, result)

        val report = auditReportRepository.add(
            project = project,
            auditType = auditType,
            content = result.summaryText?.let { stripTrailingControlJson(it) }?.ifBlank { null } ?: "(geen rapporttekst)",
            score = result.auditScore,
            scoreLabel = result.auditScoreLabel,
            proposedStoryKey = proposedStoryKey,
            durationMs = result.durationMs.toLong(),
        )

        return AuditOutcome(
            status = AuditOutcomeStatus.DONE,
            startedAt = null,
            endedAt = now,
            costUsd = result.costUsdEst,
            report = AuditReportResult(
                reportId = report.id,
                content = report.content,
                score = report.score,
                scoreLabel = report.scoreLabel,
                proposedStoryKey = proposedStoryKey,
            ),
        )
    }

    /**
     * De auditor eindigt zijn output met 1-2 losse JSON-controleblokken (`agent_tips_update` en/of
     * `phase`/`score`/`proposedStory`, zie `AgentOutcomeParser`/`tipsPrompt` in de agentworker) — die
     * horen niet in het rapport dat een mens te lezen krijgt. Knipt herhaald het laatste top-level
     * `{...}`-blok van de tekst af zolang het geldige JSON is met een van die herkenbare sleutels.
     */
    private fun stripTrailingControlJson(text: String): String {
        val spans = topLevelJsonObjectSpans(text)
        var cut = text.length
        for ((start, end) in spans.asReversed()) {
            if (text.substring(end, cut).isNotBlank()) break // er staat nog iets anders ná dit blok
            val node = runCatching { objectMapper.readTree(text.substring(start, end)) }.getOrNull() ?: break
            if (!node.has("phase") && !node.has("agent_tips_update")) break
            cut = start
        }
        return text.substring(0, cut).trimEnd()
    }

    /**
     * Alle top-level `{...}`-blokken in [text], quote-bewust (rapporten citeren vaak brokjes code met
     * eigen `{`/`}`, die mogen de blok-herkenning niet verstoren) — als (startindex, eindindex-exclusief).
     */
    private fun topLevelJsonObjectSpans(text: String): List<Pair<Int, Int>> {
        val spans = mutableListOf<Pair<Int, Int>>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        text.forEachIndexed { index, char ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                return@forEachIndexed
            }
            when (char) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            spans += start to (index + 1)
                            start = -1
                        }
                    }
                }
            }
        }
        return spans
    }

    /** `"AUDIT:<project>:<auditType>"` → (`project`, `auditType`); zie [startAudit]. */
    private fun projectAndTypeFrom(syntheticKey: String): Pair<String, String> {
        val withoutPrefix = syntheticKey.removePrefix("AUDIT:")
        val project = withoutPrefix.substringBeforeLast(":")
        val auditType = withoutPrefix.substringAfterLast(":")
        return project to auditType
    }

    /** Maakt de door de auditor voorgestelde vervolg-story aan (fase `start-next`), of null als er geen was. */
    private fun proposeStoryIfAny(project: String, result: AgentResultFile): String? {
        val proposedTitle = result.proposedStoryTitle?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            tracker.createStory(
                projectKey = defaultProjectKey(),
                title = "$AUDIT_TITLE_PREFIX$proposedTitle",
                description = result.proposedStoryDescription,
                repo = project,
                aiSupplier = "claude",
                startPhase = StoryPhase.START_NEXT,
                questionsAllowed = true,
            ).key
        }.onFailure { logger.warn("Kon voorgestelde audit-story niet aanmaken voor project {}.", project, it) }
            .getOrNull()
    }

    /** Zelfde val-terug als de bestaande "Nieuwe story"-flow (SF-818): het enige geconfigureerde project. */
    private fun defaultProjectKey(): String =
        tracker.ensureConfiguredProjects().singleOrNull()?.key ?: DEFAULT_PROJECT_KEY

    private companion object {
        const val HISTORY_LIMIT = 5
        const val DEFAULT_PROJECT_KEY = "SF"
        const val AUDIT_TITLE_PREFIX = "[Audit] "
    }
}
