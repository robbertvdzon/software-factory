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
    // De deploy-token (en andere config) staat in secrets.env en wordt door de factory via
    // SecretsEnvLoader geladen — NIET in de OS-procesomgeving geëxporteerd. Resolve daarom via de
    // factory-config (de coordinator levert een resolver op basis van ConfigApi.resolvedValues());
    // de default valt terug op System.getenv voor losstaand/test-gebruik.
    private val secretResolver: (String) -> String? = { System.getenv(it) },
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun process(subtask: TrackerIssue, phase: SubtaskPhase?): IssueProcessResult {
        val parentKey = issueTrackerClient.parentStoryKey(subtask.key)
            ?: return IssueProcessResult.Skipped(subtask.key, "deploy-no-parent")
        val parent = runCatching { issueTrackerClient.getIssue(parentKey) }.getOrNull()
        val projectName = parent?.fields?.repo
        val deployConfig = projectRepoResolver.deployConfigFor(projectName)

        return when (phase) {
            // De Skip-afhandeling mag pas als de keten deze subtaak bereikt (fase `start`).
            // Eerder (fase leeg) markeren zou de deploy al "klaar" maken vóór development/merge,
            // en de terminale advance-loop zou dan elke poll siblings proberen te starten.
            null -> IssueProcessResult.Skipped(subtask.key, "not-started")
            SubtaskPhase.START ->
                if (deployConfig is DeployConfig.Skip) {
                    issueTrackerClient.updateIssueFields(
                        subtask.key,
                        TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.DEPLOY_APPROVED.trackerValue),
                    )
                    advanceChain(subtask)
                } else {
                    startDeploy(subtask, deployConfig)
                }
            SubtaskPhase.DEPLOYING -> pollDeploy(subtask, deployConfig)
            SubtaskPhase.DEPLOY_APPROVED -> advanceChain(subtask)
            SubtaskPhase.DEPLOY_FAILED -> IssueProcessResult.Skipped(subtask.key, "deploy-failed-terminal")
            else -> IssueProcessResult.Skipped(subtask.key, "deploy-unexpected:${phase.trackerValue}")
        }
    }

    private fun startDeploy(subtask: TrackerIssue, config: DeployConfig): IssueProcessResult {
        return when (config) {
            is DeployConfig.RestRestart -> {
                val token = secretResolver(config.tokenEnvVar)?.takeIf { it.isNotBlank() }
                    ?: run {
                        val errorMsg = "[ORCHESTRATOR] Token '${config.tokenEnvVar}' niet gevonden (secrets.env/properties.env/env-var) voor deploy van ${subtask.key}."
                        issueTrackerClient.updateIssueFields(subtask.key, TrackerFieldUpdate.of(TrackerField.ERROR to errorMsg))
                        return IssueProcessResult.Errored(subtask.key, errorMsg)
                    }
                // BELANGRIJK: persisteer DEPLOYING + het trigger-tijdstip (AGENT_STARTED_AT) VÓÓR we de
                // restart triggeren. Bij een self-deploy killt de restart dít JVM kort daarna; zou de fase
                // pas ná de POST geschreven worden, dan haalt de remote YouTrack-write het vaak niet vóór
                // de halt, blijft de subtaak op START steken en herstart 'ie zichzelf eindeloos. Door eerst
                // te persisteren pakt de orchestrator ná de herstart de subtaak in DEPLOYING op en pollt
                // 'ie /api/version tot de service ná dit trigger-tijdstip opnieuw is opgestart (pollRestRestart).
                issueTrackerClient.updateIssueFields(
                    subtask.key,
                    TrackerFieldUpdate.of(
                        TrackerField.SUBTASK_PHASE to SubtaskPhase.DEPLOYING.trackerValue,
                        TrackerField.AGENT_STARTED_AT to OffsetDateTime.now(clock),
                    ),
                )
                return try {
                    val request = HttpRequest.newBuilder(URI.create(config.restartUrl))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .header("Authorization", "Bearer $token")
                        .timeout(Duration.ofSeconds(30))
                        .build()
                    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                    if (response.statusCode() == 401) {
                        // Geen herstart uitgevoerd → niet in DEPLOYING blijven hangen: terug naar START + error.
                        val errorMsg = "[ORCHESTRATOR] Restart-API gaf 401 voor ${subtask.key}; controleer ${config.tokenEnvVar}."
                        issueTrackerClient.updateIssueFields(
                            subtask.key,
                            TrackerFieldUpdate.of(
                                TrackerField.SUBTASK_PHASE to SubtaskPhase.START.trackerValue,
                                TrackerField.ERROR to errorMsg,
                            ),
                        )
                        return IssueProcessResult.Errored(subtask.key, errorMsg)
                    }
                    logger.info("Restart-aanvraag verstuurd voor {}: HTTP {}.", subtask.key, response.statusCode())
                    IssueProcessResult.Recovered(subtask.key, SubtaskPhase.DEPLOYING.trackerValue)
                } catch (ex: Exception) {
                    // Restart niet verstuurd (bv. URL onbereikbaar) → JVM leeft nog → terug naar START + error,
                    // zodat 'ie niet vruchteloos in DEPLOYING blijft pollen naar een versie die nooit verandert.
                    val errorMsg = "[ORCHESTRATOR] Fout bij restart-aanvraag voor ${subtask.key}: ${ex.message}"
                    logger.error(errorMsg, ex)
                    issueTrackerClient.updateIssueFields(
                        subtask.key,
                        TrackerFieldUpdate.of(
                            TrackerField.SUBTASK_PHASE to SubtaskPhase.START.trackerValue,
                            TrackerField.ERROR to errorMsg,
                        ),
                    )
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
        // De deploy is geslaagd zodra de service ná ons restart-trigger-tijdstip ([startedAt],
        // = AGENT_STARTED_AT) opnieuw is opgestart. We lezen `startedAt` uit /api/version.
        // (NIET "commitDate > baseline": bij een her-deploy van dezelfde commit verandert de
        // commit-datum niet, waardoor die check nooit slaagt — zie SF-179.)
        return try {
            val request = HttpRequest.newBuilder(URI.create(config.versionUrl))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                return IssueProcessResult.Skipped(subtask.key, "version-api-not-ready:${response.statusCode()}")
            }
            val restartedAt = parseStartedAt(response.body())
            if (restartedAt != null && restartedAt.isAfter(startedAt)) {
                logger.info("Deploy geslaagd voor {}: service opnieuw opgestart op {} (na trigger {}).", subtask.key, restartedAt, startedAt)
                issueTrackerClient.updateIssueFields(
                    subtask.key,
                    TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.DEPLOY_APPROVED.trackerValue),
                )
                IssueProcessResult.Recovered(subtask.key, SubtaskPhase.DEPLOY_APPROVED.trackerValue)
            } else {
                IssueProcessResult.Skipped(subtask.key, "service-not-restarted-yet")
            }
        } catch (ex: Exception) {
            logger.warn("Poll-fout voor {}: {}.", subtask.key, ex.message)
            IssueProcessResult.Skipped(subtask.key, "poll-error")
        }
    }

    /** Parseert het `startedAt`-tijdstip uit de JSON-response van /api/version (best-effort). */
    internal fun parseStartedAt(json: String): OffsetDateTime? = runCatching {
        val match = Regex(""""startedAt"\s*:\s*"([^"]+)"""").find(json) ?: return null
        OffsetDateTime.parse(match.groupValues[1])
    }.getOrNull()

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
}
