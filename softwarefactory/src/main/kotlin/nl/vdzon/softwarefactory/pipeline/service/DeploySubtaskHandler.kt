package nl.vdzon.softwarefactory.pipeline.service

import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.config.DeployConfig
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.core.ArgoApplicationStatus
import nl.vdzon.softwarefactory.core.DeploymentStatusProbe
import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.core.StoryRunRepository
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
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
 * openshift-watch (via de [DeploymentStatusProbe]-poort; de kubectl-implementatie leeft
 * als adapter in het runtime-package). Gewone Spring-bean: de advanceChain-functie zit
 * niet meer in de constructor, maar wordt per [process]-aanroep door
 * [SubtaskExecutionCoordinator] meegegeven.
 */
@Component
class DeploySubtaskHandler(
    private val issueTrackerClient: YouTrackApi,
    private val projectRepoResolver: ProjectRepoResolver,
    private val clock: Clock,
    // De deploy-token (en andere config) staat in secrets.env en wordt door de factory via
    // SecretsEnvLoader geladen — NIET in de OS-procesomgeving geëxporteerd. Resolve daarom via
    // ConfigApi.resolvedValues() (secrets.env + properties.env + System.getenv in één map);
    // een losse System.getenv-fallback zou tokens uit secrets.env missen.
    private val factoryEnvironmentProvider: ConfigApi,
    private val deploymentStatusProbe: DeploymentStatusProbe,
    // Voor de SHA-gebaseerde verificatie: de verwachte live-SHA is de HEAD van de base-branch ná merge.
    private val storyRunRepository: StoryRunRepository,
    private val gitHubApi: GitHubApi,
    // Geen bean beschikbaar voor HttpClient; de default is er puur zodat tests 'm kunnen vervangen.
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private fun resolveSecret(key: String): String? =
        factoryEnvironmentProvider.resolvedValues()[key]?.takeIf { it.isNotBlank() }

    /**
     * De verwachte live-SHA voor deze deploy: de HEAD van de base-branch van de story ná merge
     * (die commit is precies wat de merge zojuist op main heeft gezet). Best-effort: bij ontbrekende
     * targetRepo/branch of een gh-fout → `null`, waarna de verificatie terugvalt op het oude gedrag.
     */
    private fun expectedSha(parentKey: String): String? {
        val run = runCatching { storyRunRepository.openOrCreate(parentKey, "") }.getOrNull() ?: return null
        val repo = run.targetRepo.takeIf { it.isNotBlank() } ?: return null
        val branch = run.baseBranch?.takeIf { it.isNotBlank() } ?: "main"
        return runCatching { gitHubApi.latestCommitSha(repo, branch) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun process(
        subtask: TrackerIssue,
        phase: SubtaskPhase?,
        advanceChain: (TrackerIssue) -> IssueProcessResult,
    ): IssueProcessResult {
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
            SubtaskPhase.DEPLOYING -> pollDeploy(subtask, deployConfig, parentKey)
            SubtaskPhase.DEPLOY_APPROVED -> advanceChain(subtask)
            SubtaskPhase.DEPLOY_FAILED -> IssueProcessResult.Skipped(subtask.key, "deploy-failed-terminal")
            else -> IssueProcessResult.Skipped(subtask.key, "deploy-unexpected:${phase.trackerValue}")
        }
    }

    private fun startDeploy(subtask: TrackerIssue, config: DeployConfig): IssueProcessResult {
        return when (config) {
            is DeployConfig.RestRestart -> {
                val token = resolveSecret(config.tokenEnvVar)
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

    private fun pollDeploy(subtask: TrackerIssue, config: DeployConfig, parentKey: String): IssueProcessResult {
        val startedAt = subtask.fields.agentStartedAt ?: OffsetDateTime.now(clock)
        return when (config) {
            is DeployConfig.RestRestart -> pollRestRestart(subtask, config, startedAt, parentKey)
            is DeployConfig.OpenshiftWatch -> pollOpenshiftWatch(subtask, config, startedAt, parentKey)
            DeployConfig.Skip -> IssueProcessResult.Skipped(subtask.key, "deploy-skip")
        }
    }

    private fun approve(subtask: TrackerIssue): IssueProcessResult {
        issueTrackerClient.updateIssueFields(
            subtask.key,
            TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.DEPLOY_APPROVED.trackerValue),
        )
        return IssueProcessResult.Recovered(subtask.key, SubtaskPhase.DEPLOY_APPROVED.trackerValue)
    }

    private fun failWithTimeout(subtask: TrackerIssue, config: DeployConfig, startedAt: OffsetDateTime): IssueProcessResult? {
        val timeoutMinutes = when (config) {
            is DeployConfig.RestRestart -> config.timeoutMinutes
            is DeployConfig.OpenshiftWatch -> config.timeoutMinutes
            DeployConfig.Skip -> return null
        }
        if (!OffsetDateTime.now(clock).isAfter(startedAt.plusMinutes(timeoutMinutes.toLong()))) return null
        logger.warn("Deploy timeout voor {} na {} minuten.", subtask.key, timeoutMinutes)
        issueTrackerClient.updateIssueFields(
            subtask.key,
            TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.DEPLOY_FAILED.trackerValue),
        )
        return IssueProcessResult.Errored(subtask.key, "deploy-timeout")
    }

    private fun pollRestRestart(
        subtask: TrackerIssue,
        config: DeployConfig.RestRestart,
        startedAt: OffsetDateTime,
        parentKey: String,
    ): IssueProcessResult {
        failWithTimeout(subtask, config, startedAt)?.let { return it }
        return try {
            val request = HttpRequest.newBuilder(URI.create(config.versionUrl))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                return IssueProcessResult.Skipped(subtask.key, "version-api-not-ready:${response.statusCode()}")
            }
            val body = response.body()
            // SHA-gebaseerde verificatie (SF-771) heeft voorrang: de deploy geldt pas als geslaagd
            // wanneer /api/version de verwachte (zojuist gemergede) commit-SHA rapporteert. Blijft de
            // oude build live, dan matcht de SHA nooit en loopt 'ie netjes in de (verruimde) timeout.
            val reportedSha = parseCommitHash(body)
            val expected = expectedSha(parentKey)
            if (expected != null && reportedSha != null) {
                return if (shaPrefixMatch(reportedSha, expected)) {
                    logger.info("Deploy geslaagd voor {}: live-SHA {} matcht verwachte {}.", subtask.key, reportedSha, expected)
                    approve(subtask)
                } else {
                    IssueProcessResult.Skipped(subtask.key, "sha-mismatch-waiting")
                }
            }
            // Terugval (geen verwachte SHA bepaalbaar of /api/version rapporteert geen commitHash):
            // het oude gedrag — geslaagd zodra de service ná ons restart-trigger-tijdstip ([startedAt],
            // = AGENT_STARTED_AT) opnieuw is opgestart. We lezen `startedAt` uit /api/version.
            // (NIET "commitDate > baseline": bij een her-deploy van dezelfde commit verandert de
            // commit-datum niet, waardoor die check nooit slaagt — zie SF-179.)
            val restartedAt = parseStartedAt(body)
            if (restartedAt != null && restartedAt.isAfter(startedAt)) {
                logger.info("Deploy geslaagd voor {}: service opnieuw opgestart op {} (na trigger {}).", subtask.key, restartedAt, startedAt)
                approve(subtask)
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

    /** Parseert het `commitHash`-veld uit de JSON-response van /api/version (best-effort). */
    internal fun parseCommitHash(json: String): String? =
        Regex(""""commitHash"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Prefix-tolerante SHA-vergelijking (short vs. full SHA), hoofdletter-ongevoelig. */
    internal fun shaPrefixMatch(a: String, b: String): Boolean {
        val x = a.trim().lowercase()
        val y = b.trim().lowercase()
        if (x.isEmpty() || y.isEmpty()) return false
        return x.startsWith(y) || y.startsWith(x)
    }

    private fun pollOpenshiftWatch(
        subtask: TrackerIssue,
        config: DeployConfig.OpenshiftWatch,
        startedAt: OffsetDateTime,
        parentKey: String,
    ): IssueProcessResult {
        failWithTimeout(subtask, config, startedAt)?.let { return it }
        // ArgoCD als waarheidsbron (SF-771) zodra app + namespace geconfigureerd zijn; anders het
        // bestaande "image niet-leeg"-gedrag (geen regressie voor deploys zonder ArgoCD-config).
        val argoApp = config.argocdApp
        val argoNamespace = config.argocdNamespace
        if (argoApp != null && argoNamespace != null) {
            return pollArgoCd(subtask, argoApp, argoNamespace, parentKey)
        }
        // Via de DeploymentStatusProbe-poort: null = status niet opvraagbaar (kubectl-fout in de
        // runtime-adapter), zodat deze pipeline-class zelf geen extern proces hoeft te starten.
        val image = deploymentStatusProbe.currentImage(config.namespace, config.deployment)
            ?: return IssueProcessResult.Skipped(subtask.key, "kubectl-error")
        logger.debug("OpenShift image voor {}: {}.", subtask.key, image)
        // Een nieuwe image na de deployStart wordt als succesvol beschouwd.
        // Exacte commit-matching vereist dat het image een commit-label draagt; hier is een
        // best-effort check: als kubectl überhaupt een image teruggeeft is het pod-niveau
        // bereikt. In productie moet een commit-label worden gecontroleerd.
        return if (image.isNotEmpty()) {
            approve(subtask)
        } else {
            IssueProcessResult.Skipped(subtask.key, "openshift-no-image-yet")
        }
    }

    private fun pollArgoCd(
        subtask: TrackerIssue,
        argoApp: String,
        argoNamespace: String,
        parentKey: String,
    ): IssueProcessResult {
        val status: ArgoApplicationStatus = deploymentStatusProbe.argoApplicationStatus(argoNamespace, argoApp)
            ?: return IssueProcessResult.Skipped(subtask.key, "argocd-error")
        val synced = status.syncStatus.equals("Synced", ignoreCase = true)
        val healthy = status.healthStatus.equals("Healthy", ignoreCase = true)
        val succeeded = status.operationPhase.equals("Succeeded", ignoreCase = true)
        // Als de verwachte SHA bepaalbaar is, moet de gesyncte revisie daar ook mee prefix-matchen;
        // is die niet bepaalbaar, dan volstaat Synced+Healthy+Succeeded (terugval).
        val expected = expectedSha(parentKey)
        val revisionOk = expected == null || shaPrefixMatch(status.revision, expected)
        return if (synced && healthy && succeeded && revisionOk) {
            logger.info(
                "ArgoCD-deploy geslaagd voor {}: {}/{} Synced+Healthy+Succeeded op revisie {}.",
                subtask.key, argoNamespace, argoApp, status.revision,
            )
            approve(subtask)
        } else {
            IssueProcessResult.Skipped(
                subtask.key,
                "argocd-not-ready:sync=${status.syncStatus},health=${status.healthStatus}," +
                    "phase=${status.operationPhase},revisionOk=$revisionOk",
            )
        }
    }
}
