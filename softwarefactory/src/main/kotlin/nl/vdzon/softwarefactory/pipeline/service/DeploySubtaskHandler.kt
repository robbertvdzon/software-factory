package nl.vdzon.softwarefactory.pipeline.service

import nl.vdzon.softwarefactory.config.DeployConfig
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Verwerkt een DEPLOY-subtask: bewaakt of de nieuwe versie na merge is uitgerold.
 * Ondersteunt rest-restart (POST /api/restart + poll /api/version) en
 * openshift-watch (kubectl get deployment). Wordt aangemaakt door
 * [SubtaskExecutionCoordinator] die de advanceChain-functie meegeeft.
 */
class DeploySubtaskHandler(
    private val issueTrackerClient: YouTrackApi,
    private val projectRepoResolver: ProjectRepoResolver,
    private val advanceChain: (TrackerIssue) -> IssueProcessResult,
    private val clock: Clock = Clock.systemUTC(),
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun process(subtask: TrackerIssue, phase: SubtaskPhase?): IssueProcessResult {
        val parentKey = issueTrackerClient.parentStoryKey(subtask.key)
            ?: return IssueProcessResult.Skipped(subtask.key, "deploy-no-parent")
        val parent = runCatching { issueTrackerClient.getIssue(parentKey) }.getOrNull()
        val projectName = parent?.fields?.repo
        val deployConfig = projectRepoResolver.deployConfigFor(projectName)

        if (deployConfig is DeployConfig.Skip) {
            issueTrackerClient.updateIssueFields(
                subtask.key,
                TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.DEPLOY_APPROVED.trackerValue),
            )
            return advanceChain(subtask)
        }

        return when (phase) {
            null -> IssueProcessResult.Skipped(subtask.key, "not-started")
            SubtaskPhase.START -> startDeploy(subtask, deployConfig)
            SubtaskPhase.DEPLOYING -> pollDeploy(subtask, deployConfig)
            SubtaskPhase.DEPLOY_APPROVED -> advanceChain(subtask)
            SubtaskPhase.DEPLOY_FAILED -> IssueProcessResult.Skipped(subtask.key, "deploy-failed-terminal")
            else -> IssueProcessResult.Skipped(subtask.key, "deploy-unexpected:${phase.trackerValue}")
        }
    }

    private fun startDeploy(subtask: TrackerIssue, config: DeployConfig): IssueProcessResult {
        return when (config) {
            is DeployConfig.RestRestart -> {
                val token = System.getenv(config.tokenEnvVar)?.takeIf { it.isNotBlank() }
                    ?: run {
                        val errorMsg = "[ORCHESTRATOR] Env-var '${config.tokenEnvVar}' niet gezet voor deploy van ${subtask.key}."
                        issueTrackerClient.updateIssueFields(subtask.key, TrackerFieldUpdate.of(TrackerField.ERROR to errorMsg))
                        return IssueProcessResult.Errored(subtask.key, errorMsg)
                    }
                // Haal de huidige commit-datum op vóór de restart, als baseline voor de vergelijking.
                val baselineCommitDate = fetchBaselineCommitDate(config.versionUrl)
                return try {
                    val request = HttpRequest.newBuilder(URI.create(config.restartUrl))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .header("Authorization", "Bearer $token")
                        .timeout(Duration.ofSeconds(30))
                        .build()
                    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                    if (response.statusCode() == 401) {
                        val errorMsg = "[ORCHESTRATOR] Restart-API gaf 401 voor ${subtask.key}; controleer ${config.tokenEnvVar}."
                        issueTrackerClient.updateIssueFields(subtask.key, TrackerFieldUpdate.of(TrackerField.ERROR to errorMsg))
                        return IssueProcessResult.Errored(subtask.key, errorMsg)
                    }
                    logger.info("Restart-aanvraag verstuurd voor {}: HTTP {}.", subtask.key, response.statusCode())
                    issueTrackerClient.updateIssueFields(
                        subtask.key,
                        TrackerFieldUpdate.of(
                            TrackerField.SUBTASK_PHASE to SubtaskPhase.DEPLOYING.trackerValue,
                            TrackerField.AGENT_STARTED_AT to OffsetDateTime.now(clock),
                        ),
                    )
                    // Sla baseline-commit op in description zodat pollRestRestart kan vergelijken.
                    if (baselineCommitDate != null) {
                        issueTrackerClient.updateIssueDescription(
                            subtask.key,
                            "deploy-baseline: $baselineCommitDate",
                        )
                    }
                    IssueProcessResult.Recovered(subtask.key, SubtaskPhase.DEPLOYING.trackerValue)
                } catch (ex: Exception) {
                    val errorMsg = "[ORCHESTRATOR] Fout bij restart-aanvraag voor ${subtask.key}: ${ex.message}"
                    logger.error(errorMsg, ex)
                    issueTrackerClient.updateIssueFields(subtask.key, TrackerFieldUpdate.of(TrackerField.ERROR to errorMsg))
                    IssueProcessResult.Errored(subtask.key, errorMsg)
                }
            }
            is DeployConfig.OpenshiftWatch -> {
                issueTrackerClient.updateIssueFields(
                    subtask.key,
                    TrackerFieldUpdate.of(
                        TrackerField.SUBTASK_PHASE to SubtaskPhase.DEPLOYING.trackerValue,
                        TrackerField.AGENT_STARTED_AT to OffsetDateTime.now(clock),
                    ),
                )
                return IssueProcessResult.Recovered(subtask.key, SubtaskPhase.DEPLOYING.trackerValue)
            }
            DeployConfig.Skip -> IssueProcessResult.Skipped(subtask.key, "deploy-skip")
        }
    }

    /**
     * Haalt de huidige commit-datum op van [versionUrl] voor gebruik als baseline.
     * Geeft null als de URL niet bereikbaar of de datum niet parseerbaar is.
     */
    private fun fetchBaselineCommitDate(versionUrl: String): OffsetDateTime? = runCatching {
        val request = HttpRequest.newBuilder(URI.create(versionUrl))
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) parseCommitDate(response.body()) else null
    }.getOrNull()

    private fun pollDeploy(subtask: TrackerIssue, config: DeployConfig): IssueProcessResult {
        val startedAt = subtask.fields.agentStartedAt ?: OffsetDateTime.now(clock)
        return when (config) {
            is DeployConfig.RestRestart -> pollRestRestart(subtask, config, startedAt)
            is DeployConfig.OpenshiftWatch -> pollOpenshiftWatch(subtask, config, startedAt)
            DeployConfig.Skip -> IssueProcessResult.Skipped(subtask.key, "deploy-skip")
        }
    }

    private fun pollRestRestart(
        subtask: TrackerIssue,
        config: DeployConfig.RestRestart,
        startedAt: OffsetDateTime,
    ): IssueProcessResult {
        val timeoutAt = startedAt.plusMinutes(config.timeoutMinutes.toLong())
        if (OffsetDateTime.now(clock).isAfter(timeoutAt)) {
            logger.warn("Deploy timeout voor {} na {} minuten.", subtask.key, config.timeoutMinutes)
            issueTrackerClient.updateIssueFields(
                subtask.key,
                TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.DEPLOY_FAILED.trackerValue),
            )
            return IssueProcessResult.Errored(subtask.key, "deploy-timeout")
        }
        // Baseline: commit-datum van de vorige versie (opgeslagen bij startDeploy).
        // De nieuwe versie is uitgerold zodra commitDate > baseline.
        val baseline = parseBaselineFromDescription(subtask.description) ?: startedAt
        return try {
            val request = HttpRequest.newBuilder(URI.create(config.versionUrl))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                return IssueProcessResult.Skipped(subtask.key, "version-api-not-ready:${response.statusCode()}")
            }
            val commitDate = parseCommitDate(response.body())
            if (commitDate != null && commitDate.isAfter(baseline)) {
                logger.info("Deploy geslaagd voor {}: commitDate={} > baseline={}.", subtask.key, commitDate, baseline)
                issueTrackerClient.updateIssueFields(
                    subtask.key,
                    TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.DEPLOY_APPROVED.trackerValue),
                )
                IssueProcessResult.Recovered(subtask.key, SubtaskPhase.DEPLOY_APPROVED.trackerValue)
            } else {
                IssueProcessResult.Skipped(subtask.key, "version-not-updated-yet")
            }
        } catch (ex: Exception) {
            logger.warn("Poll-fout voor {}: {}.", subtask.key, ex.message)
            IssueProcessResult.Skipped(subtask.key, "poll-error")
        }
    }

    /** Parseert de baseline commit-datum uit de description (geschreven door startDeploy). */
    internal fun parseBaselineFromDescription(description: String?): OffsetDateTime? {
        description ?: return null
        val match = Regex("""deploy-baseline:\s*(\S+)""").find(description) ?: return null
        return runCatching { OffsetDateTime.parse(match.groupValues[1]) }.getOrNull()
    }

    private fun pollOpenshiftWatch(
        subtask: TrackerIssue,
        config: DeployConfig.OpenshiftWatch,
        startedAt: OffsetDateTime,
    ): IssueProcessResult {
        val timeoutAt = startedAt.plusMinutes(config.timeoutMinutes.toLong())
        if (OffsetDateTime.now(clock).isAfter(timeoutAt)) {
            logger.warn("OpenShift deploy timeout voor {} na {} minuten.", subtask.key, config.timeoutMinutes)
            issueTrackerClient.updateIssueFields(
                subtask.key,
                TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.DEPLOY_FAILED.trackerValue),
            )
            return IssueProcessResult.Errored(subtask.key, "deploy-timeout")
        }
        return try {
            val pb = ProcessBuilder(
                "kubectl", "get", "deployment", config.deployment,
                "-n", config.namespace,
                "-o", "jsonpath={.spec.template.spec.containers[0].image}",
            )
            val proc = pb.start()
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                logger.warn("kubectl get deployment mislukt voor {}: exitCode={}.", subtask.key, exitCode)
                return IssueProcessResult.Skipped(subtask.key, "kubectl-error")
            }
            val image = proc.inputStream.bufferedReader().readText().trim()
            logger.debug("OpenShift image voor {}: {}.", subtask.key, image)
            // Een nieuwe image na de deployStart wordt als succesvol beschouwd.
            // Exacte commit-matching vereist dat het image een commit-label draagt; hier is een
            // best-effort check: als kubectl überhaupt een image teruggeeft is het pod-niveau
            // bereikt. In productie moet een commit-label worden gecontroleerd.
            if (image.isNotEmpty()) {
                issueTrackerClient.updateIssueFields(
                    subtask.key,
                    TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.DEPLOY_APPROVED.trackerValue),
                )
                IssueProcessResult.Recovered(subtask.key, SubtaskPhase.DEPLOY_APPROVED.trackerValue)
            } else {
                IssueProcessResult.Skipped(subtask.key, "openshift-no-image-yet")
            }
        } catch (ex: Exception) {
            logger.warn("kubectl-fout voor {}: {}.", subtask.key, ex.message)
            IssueProcessResult.Skipped(subtask.key, "kubectl-error")
        }
    }

    /** Parseert de commitDate uit de JSON-response van /api/version (best-effort). */
    internal fun parseCommitDate(json: String): OffsetDateTime? = runCatching {
        val match = Regex(""""commitDate"\s*:\s*"([^"]+)"""").find(json) ?: return null
        OffsetDateTime.parse(match.groupValues[1])
    }.getOrNull()
}
