package nl.vdzon.softwarefactory.dashboard.services

import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.softwarefactory.audit.AuditGateway
import nl.vdzon.softwarefactory.audit.models.AuditDispatchHandle
import nl.vdzon.softwarefactory.audit.models.AuditOutcome
import nl.vdzon.softwarefactory.audit.models.AuditQuestionResult
import nl.vdzon.softwarefactory.audit.models.AuditReportResult
import nl.vdzon.softwarefactory.audit.repositories.AuditQuestionRecord
import nl.vdzon.softwarefactory.audit.repositories.AuditQuestionRepository
import nl.vdzon.softwarefactory.audit.repositories.AuditReportRepository
import nl.vdzon.softwarefactory.audit.services.AuditJob
import nl.vdzon.softwarefactory.audit.services.AuditJobsReader
import nl.vdzon.softwarefactory.audit.types.AuditOutcomeStatus
import nl.vdzon.softwarefactory.config.ProjectDashboardSettings
import nl.vdzon.softwarefactory.contract.AgentResultFile
import nl.vdzon.softwarefactory.contract.AgentResultKnowledgeUpdate
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AgentDispatchRequest
import nl.vdzon.softwarefactory.core.contracts.AgentRunCompletionRecord
import nl.vdzon.softwarefactory.core.contracts.AgentRunRepository
import nl.vdzon.softwarefactory.core.contracts.recordStarted
import nl.vdzon.softwarefactory.core.contracts.AgentRunRateLimit
import nl.vdzon.softwarefactory.core.contracts.AgentRunStart
import nl.vdzon.softwarefactory.core.contracts.AgentRuntime
import nl.vdzon.softwarefactory.core.contracts.AiRouting
import nl.vdzon.softwarefactory.core.contracts.StoryPhase
import nl.vdzon.softwarefactory.core.contracts.StoryRunRepository
import nl.vdzon.softwarefactory.core.contracts.StoryRunRecord
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import nl.vdzon.softwarefactory.knowledge.models.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.support.ControlJsonStripper
import nl.vdzon.softwarefactory.telegram.AuditQuestionNotifier
import nl.vdzon.softwarefactory.tracker.TrackerCapabilities
import nl.vdzon.softwarefactory.runtime.v2.AgentRuntimeV2HttpClient
import nl.vdzon.softwarefactory.runtime.v2.RuntimeJobResultView
import nl.vdzon.softwarefactory.runtime.v2.RuntimeJobStatus
import nl.vdzon.softwarefactory.runtime.v2.RuntimePublicationStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Adapter die de [AuditGateway]-poort van de audit-module invult: dispatcht de auditor-agent
 * rechtstreeks via [AgentRuntime] (dezelfde Runtime-v2-uitvoering als een gewone subtaak, maar
 * buiten `AgentDispatcher`/de Subtask-koppeling om — een audit heeft geen tracker-story) en
 * verwerkt de uitkomst (rapport, memory-tips, evt. 1 voorgestelde vervolg-story).
 */
@Component
class AuditGatewayAdapter(
    private val auditJobsReader: AuditJobsReader,
    private val projects: ProjectDashboardSettings,
    private val auditReportRepository: AuditReportRepository,
    private val auditQuestionRepository: AuditQuestionRepository,
    private val agentRuntime: AgentRuntime,
    private val runtimeClient: AgentRuntimeV2HttpClient,
    private val github: GitHubApi,
    private val agentRunRepository: AgentRunRepository,
    private val storyRunRepository: StoryRunRepository,
    private val tracker: TrackerCapabilities,
    private val knowledgeApi: KnowledgeApi,
    private val auditQuestionNotifier: AuditQuestionNotifier,
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

        val request = AgentDispatchRequest(
            storyKey = syntheticKey,
            projectKey = project,
            targetRepo = repo,
            storyRunId = storyRun.id,
            role = AgentRole.AUDITOR,
            phase = "auditing",
            baseBranch = storyRun.baseBranch?.takeIf(String::isNotBlank) ?: DEFAULT_BASE_BRANCH,
            aiSupplier = detail.job.aiSupplier,
            aiModel = model,
            trackerContext = auditTaskContext(project, auditType, detail.prompt),
            workspacePath = null,
        )
        val result = agentRuntime.dispatch(request)
        logger.info("Audit {}/{} gestart als container {}.", project, auditType, result.containerName)

        // Een beantwoorde vraag is nu in de prompt van deze run beland; afvinken zodat 'ie niet in
        // élke volgende run blijft terugkomen. Een nog openstaande vraag blijft juist staan: het
        // antwoord kan nog komen, en tot die tijd hoort de auditor te weten dat hij het al vroeg.
        runCatching {
            auditQuestionRepository.pendingFor(project, auditType)
                ?.takeIf { !it.isOpen }
                ?.let { auditQuestionRepository.markConsumed(it.id, OffsetDateTime.now()) }
        }.onFailure { logger.warn("Kon auditvraag niet afvinken voor {}/{}.", project, auditType, it) }

        // Audits gaan buiten AgentDispatcher om (geen Subtask), maar horen wel als lopende agent-run
        // in het Agents-scherm te verschijnen.
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
        return AuditDispatchHandle(
            containerName = result.containerName,
            workspacePath = result.workspacePath,
            storyRunId = storyRun.id,
        )
    }

    /**
     * `.task.md`-body: de audit-prompt, een eventuele openstaande/beantwoorde vraag met de
     * bevindingen van de run die 'm stelde, en de laatste eerdere rapporten als historische context.
     */
    private fun auditTaskContext(project: String, auditType: String, prompt: String): String {
        val history = auditReportRepository.recentFor(project, auditType, limit = HISTORY_LIMIT)
        val pending = runCatching { auditQuestionRepository.pendingFor(project, auditType) }.getOrNull()
        return buildString {
            appendLine("### Audit-instructie")
            appendLine()
            appendLine(prompt.trim())
            pending?.let { appendLine(pendingQuestionSection(it)) }
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

    /**
     * Het blok dat een vervolgrun z'n eigen vraag teruggeeft. Twee situaties:
     * beantwoord (ga verder op basis van het antwoord) of nog open (de audit draait opnieuw voordat
     * er antwoord was — dan moet de auditor niet dezelfde vraag nóg eens stellen).
     * De bevindingen van de vorige run staan erbij zodat het onderzoek niet over hoeft.
     */
    private fun pendingQuestionSection(question: AuditQuestionRecord): String = buildString {
        appendLine()
        if (question.isOpen) {
            appendLine("### Je eerdere vraag (nog niet beantwoord)")
            appendLine()
            appendLine(question.question.trim())
            appendLine()
            appendLine(
                "Er is nog geen antwoord. Stel dezelfde vraag niet opnieuw: rond de audit af met de " +
                    "aanname die je het meest verdedigbaar vindt en benoem die expliciet in je rapport.",
            )
        } else {
            appendLine("### Je eerdere vraag, met het antwoord van de PO")
            appendLine()
            appendLine("**Vraag:**")
            appendLine(question.question.trim())
            appendLine()
            appendLine("**Antwoord:**")
            appendLine(question.answer.orEmpty().trim())
            appendLine()
            appendLine("Dit antwoord is leidend. Maak de audit nu af en schrijf het rapport.")
        }
        question.findings?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("### Wat je toen al had uitgezocht")
            appendLine()
            appendLine(it.trim())
            appendLine()
            appendLine("Doe dit onderzoek niet opnieuw; bouw erop voort.")
        }
    }

    override fun auditOutcome(handle: AuditDispatchHandle): AuditOutcome {
        val jobId = runCatching { UUID.fromString(handle.containerName) }.getOrNull()
            ?: return failedAudit(handle, "Agent Runtime job-id is ongeldig: ${handle.containerName}")
        val job = runCatching { runtimeClient.getJob(jobId) }.getOrElse {
            return AuditOutcome(
                status = AuditOutcomeStatus.RUNNING,
                startedAt = null,
                endedAt = null,
                costUsd = 0.0,
            )
        }
        if (!job.terminal) {
            return AuditOutcome(status = AuditOutcomeStatus.RUNNING, startedAt = null, endedAt = null, costUsd = 0.0)
        }
        if (job.status != RuntimeJobStatus.SUCCEEDED) {
            return failedAudit(
                handle,
                listOfNotNull(job.errorCode, job.errorMessage).joinToString(": ")
                    .ifBlank { "Agent Runtime-audit eindigde als ${job.status}." },
            )
        }
        val runtimeResult = runCatching { runtimeClient.getResult(jobId) }.getOrElse {
            logger.warn("Terminaal auditresultaat {} is tijdelijk niet leesbaar; volgende poll probeert opnieuw.", jobId, it)
            return AuditOutcome(
                status = AuditOutcomeStatus.RUNNING,
                startedAt = job.createdAt,
                endedAt = null,
                costUsd = 0.0,
            )
        }
        agentRunRepository.storeRuntimeJobResult(
            runtimeJobId = jobId.toString(),
            checkoutCommitSha = runtimeResult.repositoryResult?.checkoutCommitSha,
            publishedCommitSha = runtimeResult.repositoryResult?.commitSha,
            repositoryResultJson = runtimeResult.repositoryResult?.let(objectMapper::writeValueAsString),
            verificationResultJson = runtimeResult.verificationResult?.let(objectMapper::writeValueAsString),
        )
        val storyRun = storyRunRepository.get(handle.storyRunId)
            ?: return failedAudit(handle, "Audit story-run ${handle.storyRunId} ontbreekt.")
        validateAuditRepositoryResult(storyRun, runtimeResult)?.let { error ->
            return failedAudit(handle, error)
        }
        val result = runtimeAuditResult(storyRun.storyKey, handle, job.status, job.createdAt, runtimeResult)
        val now = OffsetDateTime.now()
        storyRunRepository.close(handle.storyRunId, result.outcome, now)

        val completionRecord = AgentRunCompletionRecord(
            outcome = result.outcome,
            inputTokens = result.inputTokens,
            outputTokens = result.outputTokens,
            cacheReadInputTokens = result.cacheReadInputTokens,
            cacheCreationInputTokens = result.cacheCreationInputTokens,
            numTurns = result.numTurns,
            durationMs = result.durationMs,
            costUsdEst = result.costUsdEst,
            summaryText = result.summaryText,
            rateLimit = result.rateLimit?.let {
                AgentRunRateLimit(it.status, it.resetsAt, it.overageResetsAt)
            },
        )
        agentRunRepository.complete(handle.containerName, completionRecord, now)
        agentRunRepository.addUsageToStoryRun(handle.storyRunId, completionRecord)

        if (result.exitCode != 0) {
            val error = result.summaryText?.take(2000)
                ?: "Agent Runtime-audit is zonder bruikbaar resultaat gestopt."
            logger.warn("Audit-run {} mislukt: {}", handle.containerName, error)
            return AuditOutcome(status = AuditOutcomeStatus.FAILED, startedAt = null, endedAt = now, costUsd = 0.0, error = error)
        }

        val (project, auditType) = projectAndTypeFrom(result.storyKey)

        // Vraag-run: geen rapport, wel een openstaande vraag. De job wordt hierna terminaal gezet
        // (AuditJobStatus.ASKED) zodat de run kan sluiten; het antwoord plant een vervolgrun in.
        val questions = result.auditQuestions.filter { it.isNotBlank() }
        if (result.phase == AUDIT_QUESTIONS_PHASE && questions.isNotEmpty()) {
            val question = auditQuestionRepository.add(
                project = project,
                auditType = auditType,
                question = questions.joinToString("\n") { "- ${it.trim()}" },
                findings = result.auditFindingsMarkdown?.trim()?.ifBlank { null },
            )
            logger.info("Audit {}/{} stelde {} vraag/vragen (audit_question {}).", project, auditType, questions.size, question.id)
            runCatching { auditQuestionNotifier.notifyAuditQuestion(project, auditType, question.id, question.question) }
                .onFailure { logger.warn("Kon auditvraag {} niet melden in Telegram.", question.id, it) }
            return AuditOutcome(
                status = AuditOutcomeStatus.ASKED,
                startedAt = null,
                endedAt = now,
                costUsd = result.costUsdEst,
                question = AuditQuestionResult(questionId = question.id, question = question.question),
            )
        }

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
            content = reportContent(result),
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

    private fun failedAudit(handle: AuditDispatchHandle, error: String): AuditOutcome {
        val now = OffsetDateTime.now()
        storyRunRepository.close(handle.storyRunId, "error", now)
        agentRunRepository.complete(
            handle.containerName,
            AgentRunCompletionRecord(
                outcome = "error",
                inputTokens = 0,
                outputTokens = 0,
                cacheReadInputTokens = 0,
                cacheCreationInputTokens = 0,
                numTurns = 0,
                durationMs = 0,
                costUsdEst = 0.0,
                summaryText = error,
            ),
            now,
        )
        logger.warn("Audit-run {} mislukt: {}", handle.containerName, error)
        return AuditOutcome(
            status = AuditOutcomeStatus.FAILED,
            startedAt = null,
            endedAt = now,
            costUsd = 0.0,
            error = error,
        )
    }

    private fun runtimeAuditResult(
        storyKey: String,
        handle: AuditDispatchHandle,
        status: RuntimeJobStatus,
        startedAt: OffsetDateTime,
        runtimeResult: RuntimeJobResultView,
    ): AgentResultFile {
        val payload = runtimeResult.result
        return AgentResultFile(
            storyKey = storyKey,
            role = AgentRole.AUDITOR.markerKeyPart,
            containerName = handle.containerName,
            phase = payload.text("phase"),
            outcome = payload.text("outcome") ?: status.name.lowercase(),
            summaryText = payload.text("summaryText"),
            exitCode = if (status == RuntimeJobStatus.SUCCEEDED) 0 else 1,
            inputTokens = runtimeResult.usageSummary.metric("INPUT_TOKENS"),
            outputTokens = runtimeResult.usageSummary.metric("OUTPUT_TOKENS"),
            cacheReadInputTokens = runtimeResult.usageSummary.metric("CACHE_READ_INPUT_TOKENS"),
            cacheCreationInputTokens = runtimeResult.usageSummary.metric("CACHE_CREATION_INPUT_TOKENS"),
            numTurns = runtimeResult.usageSummary.attemptCount,
            durationMs = Duration.between(startedAt, runtimeResult.completedAt).toMillis()
                .coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            costUsdEst = runtimeResult.usageSummary.costs
                .filter { it.currency == "USD" }
                .fold(BigDecimal.ZERO) { total, cost -> total + cost.amount }
                .toDouble(),
            knowledgeUpdates = payload.path("knowledgeUpdates").mapNotNull { update ->
                val category = update.text("category") ?: return@mapNotNull null
                val key = update.text("key") ?: return@mapNotNull null
                val content = update.text("content") ?: return@mapNotNull null
                AgentResultKnowledgeUpdate(category, key, content)
            },
            auditScore = payload.path("auditScore").takeUnless { it.isMissingNode || it.isNull }?.asDouble(),
            auditScoreLabel = payload.text("auditScoreLabel"),
            auditReportMarkdown = payload.text("auditReportMarkdown"),
            auditQuestions = payload.path("questions").mapNotNull { it.asText().takeIf(String::isNotBlank) },
            auditFindingsMarkdown = payload.text("auditFindingsMarkdown"),
            proposedStoryTitle = payload.text("proposedStoryTitle"),
            proposedStoryDescription = payload.text("proposedStoryDescription"),
        )
    }

    private fun validateAuditRepositoryResult(storyRun: StoryRunRecord, runtimeResult: RuntimeJobResultView): String? {
        val repository = runtimeResult.repositoryResult
            ?: return "Agent Runtime leverde geen repositoryResult voor de audit."
        val expectedAlias = projects.runtimeAliasFor(storyRun.targetRepo)
            ?: return "Geen Runtime-repositoryalias geconfigureerd voor ${storyRun.targetRepo}."
        val expectedBranch = storyRun.baseBranch?.takeIf(String::isNotBlank) ?: DEFAULT_BASE_BRANCH
        if (repository.alias != expectedAlias || repository.branch != expectedBranch) {
            return "Auditbewijs hoort bij ${repository.alias}/${repository.branch}, verwacht $expectedAlias/$expectedBranch."
        }
        if (repository.publicationStatus != RuntimePublicationStatus.NONE || repository.commitSha != null) {
            return "Read-only audit rapporteerde onverwachte Gitpublicatie."
        }
        val currentHead = github.latestCommitSha(storyRun.targetRepo, expectedBranch)
            ?: return "Actuele branch-head voor auditbewijs kon niet worden bepaald."
        if (!currentHead.equals(repository.checkoutCommitSha, ignoreCase = true)) {
            return "Auditbewijs is verouderd: checkout ${repository.checkoutCommitSha}, actuele branch $currentHead."
        }
        return null
    }

    private fun nl.vdzon.softwarefactory.runtime.v2.RuntimeUsageSummary.metric(name: String): Int =
        metrics.firstOrNull { it.metric == name }?.quantity?.toInt() ?: 0

    private fun com.fasterxml.jackson.databind.JsonNode.text(name: String): String? =
        path(name).takeUnless { it.isMissingNode || it.isNull }?.asText()?.takeIf(String::isNotBlank)

    /** Het getypeerde Runtime-rapport is leidend; `summaryText` is alleen de begrensde fallback. */
    private fun reportContent(result: AgentResultFile): String =
        result.auditReportMarkdown?.trim()?.ifBlank { null }
            ?: result.summaryText?.let { ControlJsonStripper.stripTrailingControlJson(it) }?.ifBlank { null }
            ?: "(geen rapporttekst)"

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
                notificationEvents = nl.vdzon.softwarefactory.core.contracts.NotificationEvent.AUDIT,
                // SF-1959 — een auditvoorstel is nooit een hotfix: dat pad slaat review/test over.
                hotfix = false,
            ).key
        }.onFailure { logger.warn("Kon voorgestelde audit-story niet aanmaken voor project {}.", project, it) }
            .getOrNull()
    }

    /** Zelfde val-terug als de bestaande "Nieuwe story"-flow (SF-818): het enige geconfigureerde project. */
    private fun defaultProjectKey(): String =
        tracker.ensureConfiguredProjects().singleOrNull()?.key ?: DEFAULT_PROJECT_KEY

    private companion object {
        const val HISTORY_LIMIT = 5
        /** Fase waarmee de auditor aangeeft dat hij een blokkerende vraag heeft i.p.v. een rapport. */
        const val AUDIT_QUESTIONS_PHASE = "audit-questions"
        const val DEFAULT_PROJECT_KEY = "SF"
        const val DEFAULT_BASE_BRANCH = "main"
        const val AUDIT_TITLE_PREFIX = "[Audit] "
    }
}
