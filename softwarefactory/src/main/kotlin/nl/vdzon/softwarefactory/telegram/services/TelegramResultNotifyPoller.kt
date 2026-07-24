package nl.vdzon.softwarefactory.telegram.services

import nl.vdzon.softwarefactory.config.DeployConfig
import nl.vdzon.softwarefactory.config.ProjectDeploymentSettings
import nl.vdzon.softwarefactory.config.ProjectRepositoryCatalog
import nl.vdzon.softwarefactory.config.ProjectTelegramSettings
import nl.vdzon.softwarefactory.core.contracts.ApkReleaseProbe
import nl.vdzon.softwarefactory.core.contracts.IssueType
import nl.vdzon.softwarefactory.core.contracts.NotifyMode
import nl.vdzon.softwarefactory.core.contracts.SubtaskPhase
import nl.vdzon.softwarefactory.core.contracts.SubtaskType
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.telegram.clients.TelegramClient
import nl.vdzon.softwarefactory.telegram.repositories.TelegramStore
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
 * SF-1134: aparte, opt-in Telegram-melding zodra het eindresultaat van een story écht extern
 * zichtbaar/live is — naast (niet i.p.v.) de bestaande subtaak-DONE-melding van
 * [TelegramNotificationService].
 *
 * Hergebruikt bewust de bevestiging die `DeploySubtaskHandler`
 * (`nl.vdzon.softwarefactory.pipeline.service`) al doet: zodra de DEPLOY-subtaak `deploy-approved`
 * bereikt, heeft die handler al ArgoCD Synced+Healthy+Succeeded (of de image-heuristiek) resp. de
 * SHA-gebaseerde `/api/version`-check voor rest-restart geverifieerd. Deze poller voegt alleen de
 * checks toe die de deploy-handler niet doet: een HTTP-200 op de publieke live-URL (openshift-watch,
 * optioneel geconfigureerd) en het verschijnen van een nieuwe `.apk`-release na de
 * deploy-referentietijd (projecten zonder deploy-config, via [ApkReleaseProbe]).
 *
 * "Alleen pollen wanneer nodig": stopt meteen zonder cluster-/GitHub-calls zodra geen enkele story
 * de vlag aan heeft staan. Idempotent via [TelegramStore] (DB-backed, overleeft een herstart) — per
 * story hooguit één melding, ook na een opgeef-timeout (dan wordt de story alsnog als "afgehandeld"
 * gemarkeerd, maar zonder bericht of foutmelding).
 */
@Component
class TelegramResultNotifyPoller(
    private val issueTrackerClient: TrackerCapabilities,
    private val deploySettings: ProjectDeploymentSettings,
    private val repositoryCatalog: ProjectRepositoryCatalog,
    private val telegramSettings: ProjectTelegramSettings,
    private val apkReleaseProbe: ApkReleaseProbe,
    private val telegramClient: TelegramClient,
    private val store: TelegramStore,
    private val clock: Clock,
    // Geen bean beschikbaar voor HttpClient; de default is er puur zodat tests 'm kunnen vervangen.
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${softwarefactory.telegram-result-notify-poll-ms:60000}")
    fun poll() {
        if (!telegramClient.enabled) return
        val candidates = runCatching {
            issueTrackerClient.findWorkIssues(maxResults = 200, includeFinished = true)
        }
            .getOrElse {
                logger.debug("Telegram-result-notify: kon work-issues niet laden (genegeerd).", it)
                return
            }
            // SF-1261 — activatievoorwaarde verschoven van het losse telegramResultNotify-veld naar
            // notify_mode=als-klaar-en-gedeployed; dat is één enum-waarde per story, dus meldingen=geen
            // en als-klaar-en-gedeployed zijn nu inherent wederzijds uitsluitend (fix van de oude bug
            // waarbij deze poller ongeacht `silent` een bericht stuurde als telegramResultNotify=true).
            .filter {
                it.issueType == IssueType.STORY &&
                    NotifyMode.fromTracker(it.fields.notifyMode) == NotifyMode.WHEN_DONE_AND_DEPLOYED
            }
        if (candidates.isEmpty()) {
            logger.debug("Telegram-result-notify: niets te doen, skip (geen story met de vlag aan die wacht).")
            return
        }
        candidates.forEach { story ->
            runCatching { processStory(story) }
                .onFailure { logger.warn("Telegram-result-notify voor {} mislukt (genegeerd).", story.key, it) }
        }
    }

    private fun processStory(story: TrackerIssue) {
        if (store.alreadyNotified(story.key, SIGNATURE)) return
        val deploySubtask = issueTrackerClient.subtasksOf(story.key)
            .firstOrNull { SubtaskType.fromTracker(it.fields.subtaskType) == SubtaskType.DEPLOY }
            ?: return
        // Nog niet gestart of nog bezig (DeploySubtaskHandler zelf pollt nog) -> nog niets te bevestigen.
        val phase = SubtaskPhase.fromTracker(deploySubtask.fields.subtaskPhase)?.takeIf { it.isTerminal } ?: return
        if (phase == SubtaskPhase.DEPLOY_FAILED) {
            // Geen live eindresultaat om te melden; de bestaande ERROR-melding dekt dit al.
            store.recordNotified(story.key, SIGNATURE)
            return
        }
        val referenceTime = deploySubtask.fields.agentStartedAt
            ?: deploySubtask.fields.updatedAt
            ?: deploySubtask.fields.createdAt
            ?: return
        if (OffsetDateTime.now(clock).isAfter(referenceTime.plusHours(GIVEUP_HOURS))) {
            logger.warn(
                "Telegram-result-notify: opgeef-timeout voor {} na {} uur zonder externe bevestiging; stop met wachten.",
                story.key,
                GIVEUP_HOURS,
            )
            store.recordNotified(story.key, SIGNATURE)
            return
        }
        val projectName = story.fields.repo
        val confirmation = when (val config = deploySettings.deployConfigFor(projectName)) {
            is DeployConfig.OpenshiftWatch -> confirmOpenshift(config)
            is DeployConfig.RestRestart -> Confirmation("De nieuwe versie draait live.")
            DeployConfig.Skip -> confirmApk(story, projectName, referenceTime)
        } ?: return
        send(story, projectName, confirmation)
    }

    private data class Confirmation(val text: String, val url: String? = null)

    /** ArgoCD/image-status is al bevestigd door DeploySubtaskHandler; hier alleen de extra live-URL-check. */
    private fun confirmOpenshift(config: DeployConfig.OpenshiftWatch): Confirmation? {
        val liveUrl = config.liveUrl ?: return Confirmation("De nieuwe versie draait live.")
        return if (isHttp200(liveUrl)) Confirmation("De live-URL is bereikbaar.", liveUrl) else null
    }

    private fun isHttp200(url: String): Boolean = runCatching {
        val request = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(10)).build()
        httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
    }.getOrDefault(false)

    /** Projecten zonder deploy-config (skip) publiceren hun eindresultaat als GitHub-release-APK. */
    private fun confirmApk(story: TrackerIssue, projectName: String?, referenceTime: OffsetDateTime): Confirmation? {
        val repoUrl = repositoryCatalog.resolve(projectName) ?: return null
        val release = runCatching { apkReleaseProbe.newestApkReleaseAfter(repoUrl, story.projectKey, referenceTime) }
            .getOrNull() ?: return null
        return Confirmation("Er staat een nieuwe APK-release klaar.", release.downloadUrl)
    }

    private fun send(story: TrackerIssue, projectName: String?, confirmation: Confirmation) {
        val chatId = telegramSettings.telegramChatIdFor(projectName) ?: telegramClient.defaultChatId ?: return
        val lines = mutableListOf("🚀 Eindresultaat live", "", "${story.key}: ${story.summary}", "", confirmation.text)
        confirmation.url?.let { lines += listOf("", it) }
        val messageId = telegramClient.sendMessage(lines.joinToString("\n"), chatId = chatId)
        if (messageId == null) {
            logger.warn("Telegram-result-notify voor {} kon niet verstuurd worden; volgende poll opnieuw.", story.key)
            return
        }
        store.recordNotified(story.key, SIGNATURE)
    }

    private companion object {
        /** Signature in [TelegramStore]: per story hooguit één melding, ook na een opgeef-timeout. */
        const val SIGNATURE = "result-notify"

        /** Opgeef-timeout: zelfde orde grootte als DeployConfig.timeoutMinutes, maar ruimer (uren). */
        const val GIVEUP_HOURS = 4L
    }
}
