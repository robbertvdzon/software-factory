package nl.vdzon.softwarefactory.pipeline.service

import nl.vdzon.softwarefactory.config.DeployConfig
import nl.vdzon.softwarefactory.core.contracts.ApkReleaseProbe
import nl.vdzon.softwarefactory.core.contracts.DeploymentStatusProbe
import nl.vdzon.softwarefactory.core.contracts.StoryRunRecord
import nl.vdzon.softwarefactory.core.contracts.StoryRunRepository
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.pipeline.DeployRolloutStatusApi
import nl.vdzon.softwarefactory.pipeline.DeployTargetStatusApi
import nl.vdzon.softwarefactory.pipeline.models.DeployTargetLiveStatus
import nl.vdzon.softwarefactory.pipeline.models.MatchedDeployTarget
import nl.vdzon.softwarefactory.tracker.TrackerCapabilities
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Story 5 (`docs/plan-multi-deployment-en-rollout-2026-07.md`): periodieke reconciler die bijhoudt
 * wánneer een al-gemergede story écht (op alle geraakte deploy-doelen) live staat.
 *
 * Ontwerpbeslissing (zie `docs/idee-multi-deployment-per-project.md`, "story-lifecycle loskoppelen
 * van 'echt live'"): de story mag naar Done zodra gemerged — de factory kan er ná de merge toch
 * niets meer aan doen/afdwingen — dus "echt live" wordt hier apart bijgehouden
 * ([StoryRunRecord.deployedAt]), niet als blokkerende voorwaarde voor storyafronding. Kandidaten zijn
 * daarom runs met `final_status = 'merged'` en `deployedAt = null`
 * ([StoryRunRepository.runsAwaitingDeployConfirmation]).
 *
 * Analoog aan `TelegramResultNotifyPoller`: een `@Scheduled`-poller die stopt zonder externe calls
 * zodra er niets te doen is, en idempotent is doordat elke schrijfactie een `WHERE ... IS NULL`-guard
 * heeft ([StoryRunRepository.markDeployed]) — een herhaalde run zonder nieuwe informatie heeft dus
 * geen (dubbel) effect.
 *
 * Databron voor "welke deploy-doelen raakte deze story": [DeployTargetStatusApi.matchedDeployTargetsFor]
 * (Story 4's poort) — hergebruikt exact `DeploySubtaskHandler`'s eigen matchPaths-/story-diff-bepaling
 * in plaats van die een derde keer te implementeren. Die poort leunt zelf weer op de GitHub-PR-
 * bestandslijst (werkt ook lang na de merge, GitHub blijft de diff van een gemergede PR rapporteren),
 * dus er hoeft geen aparte "geraakte doelen"-snapshot bewaard te worden na Done.
 *
 * Voor de "is dit doel al live"-vraag per doel-type:
 * - OpenShift/rest-restart: een ancestor-check ([GitHubApi.isAncestor], functioneel equivalent aan
 *   `git merge-base --is-ancestor <merge-commit> <live-SHA>` — zie de docstring daar voor waarom dit
 *   via de GitHub-compare-API loopt i.p.v. een lokale git-clone, dezelfde afweging als
 *   `GitHubApi.changedFiles` voor de story-diff) tussen déze story's EIGEN merge-commit
 *   ([GitHubApi.mergeInfo] — expliciet niet [GitHubApi.latestCommitSha]/"huidige HEAD van main": die
 *   verandert zodra een latere story merget, en zou dan niet meer déze story's merge representeren)
 *   en de huidige live-SHA van dat doel (ArgoCD-revisie/pod-image resp. `/api/version`). Is de
 *   merge-commit een voorouder van (of gelijk aan) de live-SHA, dan zit deze story's wijziging er
 *   sowieso al in — ook als een latere, alweer gedeployde story de "echte" live-SHA veroorzaakte
 *   (die latere live-SHA is dan automatisch een ancestor-nakomeling van déze merge-commit).
 * - Skip+apkCheck (APK-achtig doel): een nieuwe GitHub-release gevonden ná het merge-tijdstip
 *   ([GitHubApi.mergeInfo].mergedAt), via dezelfde [ApkReleaseProbe]-poort als `DeploySubtaskHandler`.
 * - Een niet-bewaakt doel ([MatchedDeployTarget.watched] == false, bv. Skip zonder apkCheck) telt
 *   altijd als live — zelfde semantiek als Story 4's `DeployTargetRuntimeStatus.DONE` daarvoor.
 *
 * Fail-safe: is een van de benodigde gegevens (merge-commit, merge-tijd, live-SHA, ancestor-status)
 * niet te bepalen, dan telt dat doel als "nog niet bevestigd live" (nooit een gok/approve) —
 * `deployedAt` blijft dan null tot een latere poll het wél kan bepalen.
 */
@Component
class StoryDeployReconciler(
    private val storyRunRepository: StoryRunRepository,
    private val issueTrackerClient: TrackerCapabilities,
    private val deployTargetStatusApi: DeployTargetStatusApi,
    private val gitHubApi: GitHubApi,
    private val deploymentStatusProbe: DeploymentStatusProbe,
    private val apkReleaseProbe: ApkReleaseProbe,
    private val clock: Clock,
    // Geen bean beschikbaar voor HttpClient; de default is er puur zodat tests 'm kunnen vervangen.
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : DeployRolloutStatusApi {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${softwarefactory.story-deploy-reconcile-poll-ms:120000}")
    fun poll() {
        val candidates = runCatching { storyRunRepository.runsAwaitingDeployConfirmation() }
            .getOrElse {
                logger.warn("StoryDeployReconciler: kon kandidaten niet laden (genegeerd).", it)
                return
            }
        if (candidates.isEmpty()) return
        candidates.forEach { run ->
            runCatching { reconcile(run) }
                .onFailure { logger.warn("StoryDeployReconciler: reconcile voor {} mislukt (genegeerd).", run.storyKey, it) }
        }
    }

    private fun reconcile(run: StoryRunRecord) {
        val prNumber = run.prNumber
        if (prNumber == null || run.targetRepo.isBlank()) {
            logger.debug("StoryDeployReconciler: {} heeft geen PR-nummer/targetRepo; sla over.", run.storyKey)
            return
        }
        val statuses = liveStatusFor(run.storyKey, run.targetRepo, prNumber)
        if (statuses == null) {
            logger.debug("StoryDeployReconciler: live-status (nog) niet bepaalbaar voor {}; sla over.", run.storyKey)
            return
        }
        if (statuses.any { !it.live }) {
            logger.debug("StoryDeployReconciler: nog niet alle deploy-doelen live voor {}.", run.storyKey)
            return
        }
        storyRunRepository.markDeployed(run.id, OffsetDateTime.now(clock))
        logger.info("StoryDeployReconciler: {} is nu volledig live; deployedAt gezet.", run.storyKey)
    }

    /**
     * [DeployRolloutStatusApi] — hergebruikt door de Rollout-lijst (dashboard-module) om exact
     * dezelfde ancestor-/APK-check te tonen die hierboven ook `deployedAt` bepaalt; puur read-only
     * (geen tracker-writes, geen `deployedAt`-mutatie hier).
     */
    override fun liveStatusFor(storyKey: String, targetRepo: String, prNumber: Int): List<DeployTargetLiveStatus>? {
        val mergeInfo = runCatching { gitHubApi.mergeInfo(targetRepo, prNumber) }.getOrNull()
        val mergeCommitSha = mergeInfo?.mergeCommitSha ?: return null
        val mergedAt = mergeInfo?.mergedAt ?: return null
        val issue = runCatching { issueTrackerClient.getIssue(storyKey) }.getOrNull() ?: return null
        val matched = runCatching {
            deployTargetStatusApi.matchedDeployTargetsFor(storyKey, issue.fields.repo)
        }.getOrNull() ?: return null
        return matched.map { target ->
            DeployTargetLiveStatus(
                name = target.target.name,
                live = isLive(target, targetRepo, issue.projectKey, mergeCommitSha, mergedAt),
            )
        }
    }

    private fun isLive(
        matchedTarget: MatchedDeployTarget,
        targetRepo: String,
        projectKey: String,
        mergeCommitSha: String,
        mergedAt: OffsetDateTime,
    ): Boolean {
        // Een niet-bewaakt doel (Skip zonder apkCheck) heeft niets te bevestigen — telt altijd als
        // live, zelfde semantiek als Story 4's DeployTargetRuntimeStatus.DONE.
        if (!matchedTarget.watched) return true
        return when (val config = matchedTarget.target.config) {
            is DeployConfig.Skip -> apkLive(targetRepo, projectKey, mergedAt)
            is DeployConfig.RestRestart -> restRestartLive(config, targetRepo, mergeCommitSha)
            is DeployConfig.OpenshiftWatch -> openshiftLive(config, targetRepo, mergeCommitSha)
        }
    }

    private fun apkLive(targetRepo: String, projectKey: String, mergedAt: OffsetDateTime): Boolean =
        runCatching { apkReleaseProbe.newestApkReleaseAfter(targetRepo, projectKey, mergedAt) }.getOrNull() != null

    private fun restRestartLive(config: DeployConfig.RestRestart, targetRepo: String, mergeCommitSha: String): Boolean {
        val liveSha = fetchVersionCommitHash(config.versionUrl) ?: return false
        return ancestor(targetRepo, mergeCommitSha, liveSha)
    }

    private fun openshiftLive(config: DeployConfig.OpenshiftWatch, targetRepo: String, mergeCommitSha: String): Boolean {
        val liveSha = liveShaFor(config) ?: return false
        return ancestor(targetRepo, mergeCommitSha, liveSha)
    }

    /**
     * Huidige live-SHA van een OpenShift-doel: ArgoCD-revisie als [DeployConfig.OpenshiftWatch.argocdApp]/
     * `argocdNamespace` geconfigureerd zijn (zelfde bron als `DeploySubtaskHandler.argoCdReady`), anders
     * de korte SHA uit het draaiende pod-image (zelfde [DeploymentStatusProbe]-poort/methode als
     * `DashboardQueryService.fetchLiveComponents` voor de Projects-pagina). `null` bij een onbepaalbare
     * status (kubectl-/ArgoCD-fout) — de aanroeper behandelt dat als "nog niet live" (fail-safe).
     */
    private fun liveShaFor(config: DeployConfig.OpenshiftWatch): String? {
        val argoApp = config.argocdApp
        val argoNamespace = config.argocdNamespace
        if (argoApp != null && argoNamespace != null) {
            return runCatching { deploymentStatusProbe.argoApplicationStatus(argoNamespace, argoApp) }
                .getOrNull()?.revision?.takeIf { it.isNotBlank() }
        }
        val image = runCatching { deploymentStatusProbe.runningPod(config.namespace, config.deployment) }
            .getOrNull()?.image ?: return null
        return shortShaFromImage(image)
    }

    private fun ancestor(targetRepo: String, mergeCommitSha: String, liveSha: String): Boolean =
        runCatching { gitHubApi.isAncestor(targetRepo, mergeCommitSha, liveSha) }.getOrNull() == true

    private fun fetchVersionCommitHash(versionUrl: String): String? = try {
        val request = HttpRequest.newBuilder(URI.create(versionUrl)).GET().timeout(Duration.ofSeconds(10)).build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) null else parseCommitHash(response.body())
    } catch (ex: Exception) {
        logger.debug("StoryDeployReconciler: kon /api/version niet ophalen van {} ({}).", versionUrl, ex.message)
        null
    }

    /** Parseert het `commitHash`-veld uit de JSON-response van /api/version (best-effort). */
    internal fun parseCommitHash(json: String): String? =
        Regex(""""commitHash"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Korte commit-sha uit een image-tag (bv. `ghcr.io/x/y:sha-66d1019` -> `66d1019`), of null. */
    internal fun shortShaFromImage(image: String): String? {
        val tag = image.substringAfterLast('/').substringAfter(':', missingDelimiterValue = "")
            .takeIf { it.isNotEmpty() } ?: return null
        return tag.removePrefix("sha-").takeIf { it.isNotEmpty() }
    }
}
