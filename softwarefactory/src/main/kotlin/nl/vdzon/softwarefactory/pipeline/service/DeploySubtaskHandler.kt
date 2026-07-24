package nl.vdzon.softwarefactory.pipeline.service

import nl.vdzon.softwarefactory.config.ConfigApi
import nl.vdzon.softwarefactory.config.DeployConfig
import nl.vdzon.softwarefactory.config.DeployTarget
import nl.vdzon.softwarefactory.config.ProjectDeploymentSettings
import nl.vdzon.softwarefactory.core.contracts.ArgoApplicationStatus
import nl.vdzon.softwarefactory.core.contracts.DeploymentStatusProbe
import nl.vdzon.softwarefactory.core.contracts.IssueProcessResult
import nl.vdzon.softwarefactory.core.contracts.StoryRunRepository
import nl.vdzon.softwarefactory.core.contracts.SubtaskPhase
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.tracker.TrackerCapabilities
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
    private val issueTrackerClient: TrackerCapabilities,
    private val projectRepoResolver: ProjectDeploymentSettings,
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

    /**
     * De bestandspaden die deze story wijzigt (via de PR van de parent-story), voor de
     * `matchPaths`-filter op deploy-doelen (SF-1, multi-deployment-routing). `null` bij onzekerheid
     * (geen PR-nummer bekend, gh-fout) — [matchedTargets] behandelt dat fail-open (alle doelen met
     * `matchPaths` blijven meedoen i.p.v. stilzwijgend een deploy-doel over te slaan). Werkt ook ná de
     * merge: GitHub blijft de bestandslijst van een gemergede/gesloten PR rapporteren.
     */
    private fun changedPaths(parentKey: String): Set<String>? {
        val run = runCatching { storyRunRepository.openOrCreate(parentKey, "") }.getOrNull() ?: return null
        val repo = run.targetRepo.takeIf { it.isNotBlank() } ?: return null
        val prNumber = run.prNumber ?: return null
        return runCatching { gitHubApi.changedFiles(repo, prNumber) }.getOrNull()?.toSet()
    }

    /**
     * Filtert [targets] op de story-diff: een doel met lege `matchPaths` is altijd van toepassing
     * (backward-compat voor het enkelvoudige `deploy:`-blok); een doel mét `matchPaths` doet alleen
     * mee als [changedPaths] een pad met een van die prefixen bevat. Is [changedPaths] `null`
     * (onbepaalbaar), dan doen alle doelen mee — fail-open, net als de `pathPrefixes`-check van
     * `VerificationCommand`.
     */
    private fun matchedTargets(targets: List<DeployTarget>, changedPaths: Set<String>?): List<DeployTarget> =
        targets.filter { target ->
            target.matchPaths.isEmpty() ||
                changedPaths == null ||
                changedPaths.any { path -> target.matchPaths.any { prefix -> path.startsWith(prefix) } }
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
        val matched = matchedTargets(projectRepoResolver.deployTargetsFor(projectName), changedPaths(parentKey))
        // Skip-doelen (geraakt of niet) hebben niets te bewaken; alleen de niet-Skip geraakte doelen
        // tellen mee voor de "wacht op alle"-aggregatie hieronder.
        val watchTargets = matched.filterNot { it.config is DeployConfig.Skip }

        return when (phase) {
            // De directe-approve-afhandeling mag pas als de keten deze subtaak bereikt (fase `start`).
            // Eerder (fase leeg) markeren zou de deploy al "klaar" maken vóór development/merge,
            // en de terminale advance-loop zou dan elke poll siblings proberen te starten.
            null -> IssueProcessResult.Skipped(subtask.key, "not-started")
            SubtaskPhase.START ->
                if (watchTargets.isEmpty()) {
                    // Geen enkel geraakt doel heeft iets te bewaken: hetzij geen enkele matchPaths-
                    // prefix geraakt (bv. een docs-only wijziging), hetzij alle geraakte doelen zijn
                    // Skip. Beide gevallen: direct goedkeuren, net als het oude Skip-gedrag.
                    issueTrackerClient.updateIssueFields(
                        subtask.key,
                        TrackerFieldUpdate.of(TrackerField.SUBTASK_PHASE to SubtaskPhase.DEPLOY_APPROVED.trackerValue),
                    )
                    advanceChain(subtask)
                } else {
                    startDeployTargets(subtask, watchTargets)
                }
            SubtaskPhase.DEPLOYING -> pollDeployTargets(subtask, watchTargets, parentKey)
            SubtaskPhase.DEPLOY_APPROVED -> advanceChain(subtask)
            SubtaskPhase.DEPLOY_FAILED -> IssueProcessResult.Skipped(subtask.key, "deploy-failed-terminal")
            else -> IssueProcessResult.Skipped(subtask.key, "deploy-unexpected:${phase.trackerValue}")
        }
    }

    /**
     * Triggert alle geraakte, niet-Skip [targets] in één keer. Eén gedeeld DEPLOYING/AGENT_STARTED_AT-
     * schrijfmoment vóór welke trigger dan ook (zelfde reden als voorheen: bij een self-deploy killt
     * een rest-restart-target dít JVM kort daarna, dus de fase moet al gepersisteerd zijn). Per
     * rest-restart-target wordt de restart-POST vervolgens best-effort verstuurd; de eerste harde fout
     * (token ontbreekt, 401, request-exceptie) zet de subtaak terug naar START met een foutmelding —
     * al eerder getriggerde restarts van andere doelen in dezelfde aanroep kunnen niet meer worden
     * teruggedraaid (idempotent genoeg: een dubbele restart bij de eerstvolgende START-poging is
     * onschuldig).
     */
    private fun startDeployTargets(subtask: TrackerIssue, targets: List<DeployTarget>): IssueProcessResult {
        issueTrackerClient.updateIssueFields(
            subtask.key,
            TrackerFieldUpdate.of(
                TrackerField.SUBTASK_PHASE to SubtaskPhase.DEPLOYING.trackerValue,
                TrackerField.AGENT_STARTED_AT to OffsetDateTime.now(clock),
            ),
        )
        for (target in targets) {
            val config = target.config as? DeployConfig.RestRestart ?: continue
            val token = resolveSecret(config.tokenEnvVar)
                ?: run {
                    val errorMsg = "[ORCHESTRATOR] Token '${config.tokenEnvVar}' niet gevonden (secrets.env/properties.env/env-var) voor deploy-doel '${target.name}' van ${subtask.key}."
                    issueTrackerClient.updateIssueFields(
                        subtask.key,
                        TrackerFieldUpdate.of(
                            TrackerField.SUBTASK_PHASE to SubtaskPhase.START.trackerValue,
                            TrackerField.ERROR to errorMsg,
                        ),
                    )
                    return IssueProcessResult.Errored(subtask.key, errorMsg)
                }
            try {
                val request = HttpRequest.newBuilder(URI.create(config.restartUrl))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .header("Authorization", "Bearer $token")
                    .timeout(Duration.ofSeconds(30))
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 401) {
                    // Geen herstart uitgevoerd → niet in DEPLOYING blijven hangen: terug naar START + error.
                    val errorMsg = "[ORCHESTRATOR] Restart-API gaf 401 voor deploy-doel '${target.name}' van ${subtask.key}; controleer ${config.tokenEnvVar}."
                    issueTrackerClient.updateIssueFields(
                        subtask.key,
                        TrackerFieldUpdate.of(
                            TrackerField.SUBTASK_PHASE to SubtaskPhase.START.trackerValue,
                            TrackerField.ERROR to errorMsg,
                        ),
                    )
                    return IssueProcessResult.Errored(subtask.key, errorMsg)
                }
                logger.info("Restart-aanvraag verstuurd voor {} (doel '{}'): HTTP {}.", subtask.key, target.name, response.statusCode())
            } catch (ex: Exception) {
                // Restart niet verstuurd (bv. URL onbereikbaar) → JVM leeft nog → terug naar START + error,
                // zodat 'ie niet vruchteloos in DEPLOYING blijft pollen naar een versie die nooit verandert.
                val errorMsg = "[ORCHESTRATOR] Fout bij restart-aanvraag voor deploy-doel '${target.name}' van ${subtask.key}: ${ex.message}"
                logger.error(errorMsg, ex)
                issueTrackerClient.updateIssueFields(
                    subtask.key,
                    TrackerFieldUpdate.of(
                        TrackerField.SUBTASK_PHASE to SubtaskPhase.START.trackerValue,
                        TrackerField.ERROR to errorMsg,
                    ),
                )
                return IssueProcessResult.Errored(subtask.key, errorMsg)
            }
        }
        return IssueProcessResult.Recovered(subtask.key, SubtaskPhase.DEPLOYING.trackerValue)
    }

    /**
     * Bewaakt alle geraakte, niet-Skip [targets] tegelijk: eerst een timeout-check per doel (het
     * eerste doel dat zijn eigen `timeoutMinutes` overschrijdt zet de hele subtaak op DEPLOY_FAILED —
     * zelfde fail-fast als het oude single-target-gedrag), daarna een read-only "is dit doel klaar"-
     * check per doel. Pas als ALLE doelen klaar zijn wordt de subtaak in één keer op DEPLOY_APPROVED
     * gezet; blijft er minstens één doel achter, dan blijft de subtaak wachten (Skipped, met de namen
     * van de nog niet-klare doelen in de reden).
     */
    private fun pollDeployTargets(subtask: TrackerIssue, targets: List<DeployTarget>, parentKey: String): IssueProcessResult {
        val startedAt = subtask.fields.agentStartedAt ?: OffsetDateTime.now(clock)
        for (target in targets) {
            failWithTimeout(subtask, target.config, startedAt)?.let { return it }
        }
        val pendingNames = targets.filterNot { target ->
            when (val config = target.config) {
                is DeployConfig.RestRestart -> restRestartReady(subtask, config, startedAt, parentKey)
                is DeployConfig.OpenshiftWatch -> openshiftWatchReady(subtask, config, parentKey)
                DeployConfig.Skip -> true
            }
        }.map { it.name }
        return if (pendingNames.isEmpty()) {
            approve(subtask)
        } else {
            IssueProcessResult.Skipped(subtask.key, "deploy-targets-pending:${pendingNames.joinToString(",")}")
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
        val errorMsg = "[ORCHESTRATOR] Deploy-timeout voor ${subtask.key} na $timeoutMinutes minuten, " +
            "geen bevestiging via ArgoCD/rest-restart."
        issueTrackerClient.updateIssueFields(
            subtask.key,
            TrackerFieldUpdate.of(
                TrackerField.SUBTASK_PHASE to SubtaskPhase.DEPLOY_FAILED.trackerValue,
                TrackerField.ERROR to errorMsg,
            ),
        )
        return IssueProcessResult.Errored(subtask.key, errorMsg)
    }

    /**
     * Read-only "is dit rest-restart-doel al live"-check (geen tracker-writes): gebruikt door
     * [pollDeployTargets] om meerdere doelen te kunnen combineren vóórdat er één keer DEPLOY_APPROVED
     * geschreven wordt. Zelfde SHA-gebaseerde verificatie (SF-771) + startedAt-terugval als voorheen.
     */
    private fun restRestartReady(
        subtask: TrackerIssue,
        config: DeployConfig.RestRestart,
        startedAt: OffsetDateTime,
        parentKey: String,
    ): Boolean = try {
        val request = HttpRequest.newBuilder(URI.create(config.versionUrl))
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            false
        } else {
            val body = response.body()
            // SHA-gebaseerde verificatie (SF-771) heeft voorrang: de deploy geldt pas als geslaagd
            // wanneer /api/version de verwachte (zojuist gemergede) commit-SHA rapporteert. Blijft de
            // oude build live, dan matcht de SHA nooit en loopt 'ie netjes in de (verruimde) timeout.
            val reportedSha = parseCommitHash(body)
            val expected = expectedSha(parentKey)
            if (expected != null && reportedSha != null) {
                val matches = shaPrefixMatch(reportedSha, expected)
                if (matches) logger.info("Deploy geslaagd voor {}: live-SHA {} matcht verwachte {}.", subtask.key, reportedSha, expected)
                matches
            } else {
                // Terugval (geen verwachte SHA bepaalbaar of /api/version rapporteert geen commitHash):
                // het oude gedrag — geslaagd zodra de service ná ons restart-trigger-tijdstip
                // ([startedAt], = AGENT_STARTED_AT) opnieuw is opgestart. We lezen `startedAt` uit
                // /api/version. (NIET "commitDate > baseline": bij een her-deploy van dezelfde commit
                // verandert de commit-datum niet, waardoor die check nooit slaagt — zie SF-179.)
                val restartedAt = parseStartedAt(body)
                val ready = restartedAt != null && restartedAt.isAfter(startedAt)
                if (ready) logger.info("Deploy geslaagd voor {}: service opnieuw opgestart op {} (na trigger {}).", subtask.key, restartedAt, startedAt)
                ready
            }
        }
    } catch (ex: Exception) {
        logger.warn("Poll-fout voor {}: {}.", subtask.key, ex.message)
        false
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

    /**
     * Read-only "is dit openshift-watch-doel al live"-check (geen tracker-writes), zie
     * [restRestartReady]. ArgoCD is de waarheidsbron zodra `argocdApp`/`argocdNamespace` geconfigureerd
     * zijn; anders het bestaande "image niet-leeg"-gedrag (geen regressie voor deploys zonder
     * ArgoCD-config).
     */
    private fun openshiftWatchReady(
        subtask: TrackerIssue,
        config: DeployConfig.OpenshiftWatch,
        parentKey: String,
    ): Boolean {
        val argoApp = config.argocdApp
        val argoNamespace = config.argocdNamespace
        if (argoApp != null && argoNamespace != null) {
            return argoCdReady(subtask, argoApp, argoNamespace, parentKey)
        }
        // Via de DeploymentStatusProbe-poort: null = status niet opvraagbaar (kubectl-fout in de
        // runtime-adapter), zodat deze pipeline-class zelf geen extern proces hoeft te starten.
        val image = deploymentStatusProbe.currentImage(config.namespace, config.deployment) ?: return false
        logger.debug("OpenShift image voor {}: {}.", subtask.key, image)
        // Een nieuwe image na de deployStart wordt als succesvol beschouwd.
        // Exacte commit-matching vereist dat het image een commit-label draagt; hier is een
        // best-effort check: als kubectl überhaupt een image teruggeeft is het pod-niveau
        // bereikt. In productie moet een commit-label worden gecontroleerd.
        return image.isNotEmpty()
    }

    private fun argoCdReady(
        subtask: TrackerIssue,
        argoApp: String,
        argoNamespace: String,
        parentKey: String,
    ): Boolean {
        val status: ArgoApplicationStatus = deploymentStatusProbe.argoApplicationStatus(argoNamespace, argoApp) ?: return false
        val synced = status.syncStatus.equals("Synced", ignoreCase = true)
        val healthy = status.healthStatus.equals("Healthy", ignoreCase = true)
        val succeeded = status.operationPhase.equals("Succeeded", ignoreCase = true)
        // Als de verwachte SHA bepaalbaar is, moet de gesyncte revisie daar ook mee prefix-matchen;
        // is die niet bepaalbaar, dan volstaat Synced+Healthy+Succeeded (terugval).
        val expected = expectedSha(parentKey)
        val revisionOk = expected == null || shaPrefixMatch(status.revision, expected)
        val ready = synced && healthy && succeeded && revisionOk
        if (ready) {
            logger.info(
                "ArgoCD-deploy geslaagd voor {}: {}/{} Synced+Healthy+Succeeded op revisie {}.",
                subtask.key, argoNamespace, argoApp, status.revision,
            )
        } else {
            logger.debug(
                "ArgoCD-deploy nog niet klaar voor {}: sync={},health={},phase={},revisionOk={}",
                subtask.key, status.syncStatus, status.healthStatus, status.operationPhase, revisionOk,
            )
        }
        return ready
    }
}
