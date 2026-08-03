package nl.vdzon.softwarefactory.telegram.services

import nl.vdzon.softwarefactory.config.DeployConfig
import nl.vdzon.softwarefactory.config.ProjectDeploymentSettings
import nl.vdzon.softwarefactory.config.ProjectRepositoryCatalog
import nl.vdzon.softwarefactory.config.ProjectTelegramSettings
import nl.vdzon.softwarefactory.core.contracts.ApkReleaseProbe
import nl.vdzon.softwarefactory.core.contracts.FactoryOperations
import nl.vdzon.softwarefactory.core.contracts.IssueType
import nl.vdzon.softwarefactory.core.contracts.NotifyMode
import nl.vdzon.softwarefactory.core.contracts.SubtaskPhase
import nl.vdzon.softwarefactory.core.contracts.SubtaskType
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.support.ControlJsonStripper
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
 * bereikt, heeft die handler al ArgoCD Synced+Healthy+Succeeded (of de image-heuristiek), de
 * SHA-gebaseerde `/api/version`-check voor rest-restart, resp. (sinds SF-2) de APK-release zelf
 * (voor Skip-doelen met `apkCheck: true`) geverifieerd. Deze poller voegt alleen de checks toe die
 * de deploy-handler niet doet: een HTTP-200 op de publieke live-URL (openshift-watch, optioneel
 * geconfigureerd) en — voor projecten met een `Skip`-deploy-config — een nette "Er staat een
 * nieuwe APK-release klaar"-melding met downloadlink via [ApkReleaseProbe].
 *
 * SF-2: vóór deze fix zette `DeploySubtaskHandler` een Skip-doel instant op `deploy-approved`,
 * waardoor de generieke "✅ klaar"-DONE-melding (`TelegramNotificationService.classifySubtaskDone`)
 * te vroeg verstuurd werd (vaak vóórdat de APK er echt was) — deze poller was daar de losse,
 * opt-in correctie voor. Nu `DeploySubtaskHandler` zelf al op de APK wacht vóórdat de subtaak
 * terminaal wordt (voor Skip-doelen met `apkCheck: true`), is de DONE-melding zelf niet meer
 * premature. Deze poller blijft desondanks bestaan als vangnet + verrijkte melding (downloadlink,
 * en de losse openshift-watch-liveUrl-check die geen equivalent heeft in `DeploySubtaskHandler`):
 * `confirmApk()` gebruikt nog altijd `deployConfigFor()` (single-target, per Story 1 het eerste
 * doel als representatieve config) i.p.v. de multi-target-lijst, dus 'm nu verwijderen zou de
 * live-URL-check en de rijkere APK-melding voor single-target-projecten laten vervallen zonder
 * vervanging.
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
    private val factoryOperations: FactoryOperations,
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
            is DeployConfig.RestRestart -> Confirmation()
            is DeployConfig.Skip -> confirmApk(story, projectName, referenceTime)
        } ?: return
        send(story, projectName, confirmation)
    }

    /**
     * De uitkomst van de externe bevestiging: `null` = nog niet melden, een [Confirmation] = melden.
     * SF-1830: draagt alleen nog de eventuele URL — de bevestigende zin staat niet meer in het bericht,
     * de checks eromheen bepalen nog steeds ÓF, WANNEER en met welke URL er gemeld wordt.
     */
    private data class Confirmation(val url: String? = null)

    /** ArgoCD/image-status is al bevestigd door DeploySubtaskHandler; hier alleen de extra live-URL-check. */
    private fun confirmOpenshift(config: DeployConfig.OpenshiftWatch): Confirmation? {
        val liveUrl = config.liveUrl ?: return Confirmation()
        return if (isHttp200(liveUrl)) Confirmation(liveUrl) else null
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
        return Confirmation(release.downloadUrl)
    }

    private fun send(story: TrackerIssue, projectName: String?, confirmation: Confirmation) {
        val chatId = telegramSettings.telegramChatIdFor(projectName) ?: telegramClient.defaultChatId ?: return
        val blocks = mutableListOf(headline(story.key, story.summary))
        functionalSummary(story)?.let { blocks += it }
        confirmation.url?.let { blocks += it }
        val messageId = telegramClient.sendMessage(blocks.joinToString("\n\n"), chatId = chatId)
        if (messageId == null) {
            logger.warn("Telegram-result-notify voor {} kon niet verstuurd worden; volgende poll opnieuw.", story.key)
            return
        }
        store.recordNotified(story.key, SIGNATURE)
    }

    /**
     * SF-1830: de korte functionele samenvatting in het bericht; eerste niet-lege bron wint:
     * 1. het PO-blok dat de summarizer tussen de `deploy-summary`-markers aflevert,
     * 2. de `## Samenvatting`-sectie uit de story-description,
     * 3. niets — dan bestaat het bericht alleen uit de kop (+ eventuele URL).
     *
     * Elke bron is soft-fail (`runCatching`): een fout bij ophalen of parsen mag de melding nooit
     * tegenhouden, hij valt gewoon door naar de volgende bron.
     */
    private fun functionalSummary(story: TrackerIssue): String? =
        (deploySummaryBlock(story.key) ?: descriptionSummary(story))
            ?.let { ControlJsonStripper.stripTrailingControlJson(it) }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.take(SUMMARY_LIMIT)

    private fun deploySummaryBlock(storyKey: String): String? =
        runCatching { factoryOperations.deploySummaryFor(storyKey) }
            .onFailure { logger.debug("Telegram-result-notify: PO-samenvatting voor {} niet leesbaar.", storyKey, it) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /** De tekst vanaf de kopregel `## Samenvatting` tot de volgende `## `-kop of het einde. */
    private fun descriptionSummary(story: TrackerIssue): String? =
        runCatching { summarySectionOf(story.description) }.getOrNull()

    private companion object {
        /** Signature in [TelegramStore]: per story hooguit één melding, ook na een opgeef-timeout. */
        const val SIGNATURE = "result-notify"

        /** Opgeef-timeout: zelfde orde grootte als DeployConfig.timeoutMinutes, maar ruimer (uren). */
        const val GIVEUP_HOURS = 4L

        /** Telegram-veilige lengte voor de samenvatting (orde van TelegramNotificationService). */
        const val SUMMARY_LIMIT = 1000

        /** SF-1858: maximale lengte van de story-titel in de kopregel (alleen de titel zelf). */
        const val TITLE_LIMIT = 120

        const val SUMMARY_HEADING = "## Samenvatting"

        /**
         * SF-1858: de kopregel van de deployed-melding, met de story-titel achter de key zodat in
         * Telegram meteen zichtbaar is wélke story live staat (bijv. een nachtelijke audit-story).
         * Puur functioneel, zodat de opbouw los testbaar blijft. Een lege of whitespace-only titel
         * valt terug op alleen de key — zonder dubbele punt en zonder losse spatie. Een titel langer
         * dan [TITLE_LIMIT] wordt afgekapt met een `…` erachter.
         */
        fun headline(key: String, summary: String?): String =
            summary?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { if (it.length > TITLE_LIMIT) it.take(TITLE_LIMIT) + "…" else it }
                ?.let { "🚀 Story $key: $it is deployed!" }
                ?: "🚀 Story $key is deployed!"

        /** Puur functioneel, zodat de sectie-parsing los testbaar blijft. */
        fun summarySectionOf(description: String?): String? {
            val lines = description?.lines().orEmpty()
            return lines.indexOfFirst { it.trim() == SUMMARY_HEADING }
                .takeIf { it >= 0 }
                ?.let { start -> lines.drop(start + 1).takeWhile { !it.trimStart().startsWith("## ") } }
                ?.joinToString("\n")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }
    }
}
