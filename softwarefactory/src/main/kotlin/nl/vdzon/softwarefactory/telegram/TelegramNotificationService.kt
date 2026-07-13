package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.core.FactoryOperations
import nl.vdzon.softwarefactory.core.HumanActionPolicy
import nl.vdzon.softwarefactory.core.HumanGate
import nl.vdzon.softwarefactory.core.IssueType
import nl.vdzon.softwarefactory.core.MergeReadyInfo
import nl.vdzon.softwarefactory.core.StoryPhase
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.SubtaskType
import nl.vdzon.softwarefactory.core.TesterScreenshots
import nl.vdzon.softwarefactory.core.TrackerAttachment
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.tracker.TrackerApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files

/**
 * Soort melding. De eerste drie zijn "actie nodig" — daarop kun je via een Telegram-reply reageren
 * (zie [TelegramReplyService]); PROGRESS/DONE/ERROR zijn puur informatief.
 */
private enum class NotifyCategory { QUESTION, APPROVAL, MANUAL, PROGRESS, DONE, ERROR }

private val NotifyCategory.replyable: Boolean
    get() = this == NotifyCategory.QUESTION || this == NotifyCategory.APPROVAL || this == NotifyCategory.MANUAL

/** Een melding-waardige toestand van één issue, met een idempotentie-signature. */
private data class NotifyEvent(
    val category: NotifyCategory,
    /** Stabiele signature per toestand: één melding per transitie. */
    val signature: String,
    /** De bron-fase (trackerValue) bij een reply-bare melding, voor de reply-koppeling. */
    val sourcePhase: String? = null,
    /** Override-header (bv. voor de per-fase PROGRESS-voortgangsmeldingen). */
    val header: String? = null,
)

/**
 * Stuurt een Telegram-melding zodra een story/subtask iets van je vraagt of een mijlpaal raakt. De set
 * volgt exact de "My actions"-logica (zie `DashboardQueryService.awaitsHuman`): staat het in je
 * actie-inbox, dan krijg je een Telegram-bericht.
 *
 *  - **QUESTION** — een agent stelt een vraag (`*-with-questions`); reply = antwoord.
 *  - **APPROVAL** — een stap is klaar en wacht op goedkeuring (refined/planned/developed/reviewed/
 *    tested/summarized); reply `approve` = goedkeuren, andere tekst = terugsturen met feedback.
 *  - **MANUAL** — een handmatige subtaak (`awaiting-human`); reply = als klaar markeren.
 *  - **PROGRESS** — bij actieve auto-approve: voortgangsmijlpaal (refining/planning klaar); informatief.
 *  - **DONE** — refinement goedgekeurd of een subtaak afgerond (terminaal).
 *  - **ERROR** — het issue staat in error.
 *
 * Idempotent via [TelegramStore]: per (issue, toestand) hooguit één bericht, ook over herstarts heen.
 * Draait op de orchestrator-poll-cadans (geen eigen polling).
 */
@Service
class TelegramNotificationService(
    private val issueTrackerClient: TrackerApi,
    private val dashboardService: FactoryOperations,
    private val telegramClient: TelegramClient,
    private val store: TelegramStore,
    private val secrets: FactorySecrets,
    private val projectRepoResolver: ProjectRepoResolver,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun notifyPending() {
        if (!telegramClient.enabled) return
        val defaultChat = telegramClient.defaultChatId ?: return
        val issues = runCatching { issueTrackerClient.findWorkIssues(maxResults = 200) }
            .getOrElse {
                logger.debug("Telegram-notify: kon work-issues niet laden (genegeerd).", it)
                return
            }
        for (issue in issues) {
            // SF-335 — een silent story (en haar subtaken, via parent-lookup) krijgt géén enkel bericht,
            // ook geen error-melding. Volledig autonoom verwerken zonder Telegram-ruis.
            if (runCatching { issueTrackerClient.effectiveSilent(issue) }.getOrDefault(false)) continue
            val event = classify(issue) ?: continue
            // Context hoort bij reply-bare meldingen (vraagtekst/agent-resultaat) én bij PROGRESS-
            // mijlpalen (gepromote description of subtaak-overzicht). Tracker-calls degraderen netjes.
            val context = when {
                event.category.replyable -> runCatching { dashboardService.questionFor(issue) }.getOrNull()
                event.category == NotifyCategory.PROGRESS -> runCatching { progressContext(issue) }.getOrNull()
                else -> null
            }
            // Signature uniek per inhoud bij meldingen met context: zo geeft een NIEUWE vraag-/resultaat-
            // ronde in dezelfde fase (na een antwoord -> her-refine) wél weer een melding.
            val signature = context?.takeIf { it.isNotBlank() }?.let { "${event.signature}:${it.hashCode()}" } ?: event.signature
            if (store.alreadyNotified(issue.key, signature)) continue
            // Project-kanaal van het issue (story: eigen Repo; subtaak: van de parent); anders globaal.
            val chatId = channelFor(issue, defaultChat)
            // Een subtaak die terminaal wordt: bij auto-approve UIT het bestaande merge-aanbod
            // (tryNotifyMergeReady); bij auto-approve AAN een eigen 'klaar'-melding met story-overzicht
            // en — als de hele story af is en er een PR ligt — een merge-actie in hetzelfde bericht.
            if (event.category == NotifyCategory.DONE && issue.issueType == IssueType.SUBTASK) {
                if (dashboardService.autoApproveActive(issue)) {
                    notifySubtaskDone(issue, event, issues, signature, chatId)
                    continue
                }
                if (tryNotifyMergeReady(issue, doneSignature = signature, chatId = chatId)) continue
            }
            val messageId = telegramClient.sendMessage(buildMessage(issue, event, context), chatId = chatId)
            // Pas vastleggen als het bericht ook echt verstuurd is, anders proberen we het later opnieuw.
            if (messageId == null) {
                logger.warn("Telegram-melding voor {} kon niet verstuurd worden; volgende poll opnieuw.", issue.key)
                continue
            }
            store.recordNotified(issue.key, signature)
            if (event.category.replyable && event.sourcePhase != null) {
                val level = if (issue.issueType == IssueType.SUBTASK) "SUBTASK" else "STORY"
                store.savePending(chatId, messageId, issue.key, level, event.sourcePhase)
            }
        }
    }

    /** Het Telegram-kanaal voor [issue]: het projectkanaal (uit projects.yaml) of [defaultChat]. */
    private fun channelFor(issue: TrackerIssue, defaultChat: String): String {
        val projectName = if (issue.issueType == IssueType.SUBTASK) {
            runCatching {
                issueTrackerClient.parentStoryKey(issue.key)?.let { issueTrackerClient.getIssue(it).fields.repo }
            }.getOrNull()
        } else {
            issue.fields.repo
        }
        return projectRepoResolver.telegramChatIdFor(projectName) ?: defaultChat
    }

    /**
     * Stuurt een "klaar om te mergen"-melding wanneer [subtask] de hele story afrondt. Onderdrukt dan
     * de losse subtaak-'klaar' (door diens signature ook vast te leggen). @return true als de
     * merge-melding is afgehandeld (of de story al merge-gemeld was), zodat de caller stopt.
     */
    private fun tryNotifyMergeReady(subtask: TrackerIssue, doneSignature: String, chatId: String): Boolean {
        val merge = runCatching { dashboardService.mergeReadyForSubtask(subtask) }.getOrNull() ?: return false
        if (store.alreadyNotified(merge.storyKey, MERGE_READY_SIGNATURE)) {
            // Story is al als merge-ready gemeld; onderdruk alleen de losse subtaak-'klaar'.
            store.recordNotified(subtask.key, doneSignature)
            return true
        }
        val messageId = telegramClient.sendMessage(buildMergeReadyMessage(merge), chatId = chatId) ?: return false
        store.recordNotified(merge.storyKey, MERGE_READY_SIGNATURE)
        store.recordNotified(subtask.key, doneSignature)
        store.savePending(chatId, messageId, merge.storyKey, "STORY", MERGE_READY_PHASE)
        return true
    }

    private fun buildMergeReadyMessage(merge: MergeReadyInfo): String {
        val lines = mutableListOf("🎉 Klaar om te mergen", "", "${merge.storyKey} is afgerond.")
        merge.prUrl?.let { lines += listOf("", "PR #${merge.prNumber}: $it") }
        lines += listOf("", "↩️ Reply \"merge\" om de PR naar main te mergen (squash).")
        linkFor(merge.storyKey)?.let { lines += listOf("", it) }
        return lines.joinToString("\n")
    }

    private fun classify(issue: TrackerIssue): NotifyEvent? {
        val error = issue.fields.error?.takeIf { it.isNotBlank() }
        if (error != null) {
            return NotifyEvent(NotifyCategory.ERROR, "error:${error.hashCode()}")
        }
        // De wacht-op-mens-beslissing komt uit de centrale HumanActionPolicy (auto-approve via de
        // parent-story, zie SF-170); alleen de vertaling naar meldingscategorie/header en de
        // voortgangs-/klaar-meldingen hieronder zijn Telegram-specifiek.
        val autoApprove = dashboardService.autoApproveActive(issue)
        val phaseValue = when (issue.issueType) {
            IssueType.STORY -> StoryPhase.fromTracker(issue.fields.storyPhase)?.trackerValue
            IssueType.SUBTASK -> SubtaskPhase.fromTracker(issue.fields.subtaskPhase)?.trackerValue
        }
        when (HumanActionPolicy.gateFor(issue)) {
            HumanGate.QUESTION -> return NotifyEvent(NotifyCategory.QUESTION, "q:$phaseValue", phaseValue)
            HumanGate.MANUAL -> return NotifyEvent(NotifyCategory.MANUAL, "manual:$phaseValue", phaseValue)
            HumanGate.APPROVAL ->
                return if (autoApprove) null else NotifyEvent(NotifyCategory.APPROVAL, "approve:$phaseValue", phaseValue)
            null -> Unit
        }
        return when (issue.issueType) {
            IssueType.STORY -> classifyStoryProgress(StoryPhase.fromTracker(issue.fields.storyPhase), autoApprove)
            IssueType.SUBTASK -> classifySubtaskDone(SubtaskPhase.fromTracker(issue.fields.subtaskPhase))
        }
    }

    /** Telegram-specifiek: voortgangs-/klaar-meldingen op story-niveau (geen wacht-op-mens-momenten). */
    private fun classifyStoryProgress(phase: StoryPhase?, autoApprove: Boolean): NotifyEvent? = when (phase) {
        // Bij auto-approve: refining is klaar, de planner gaat aan de slag. De gepromote description
        // is op dit punt al gezet (promoteRefinedDescription draait vóór de dispatch).
        StoryPhase.PLANNING,
        -> if (autoApprove) {
            NotifyEvent(NotifyCategory.PROGRESS, "progress:${phase.trackerValue}", header = "ℹ️ Refining klaar, begint met plannen")
        } else {
            null
        }
        // Bij auto-approve: planning is klaar, de uitvoering begint -> voortgangsmelding met
        // subtaak-overzicht i.p.v. de losse 'klaar'. Zonder auto-approve blijft de DONE-melding.
        StoryPhase.PLANNING_APPROVED,
        -> if (autoApprove) {
            NotifyEvent(NotifyCategory.PROGRESS, "progress:${phase.trackerValue}", header = "ℹ️ Planning klaar, begint met uitvoeren")
        } else {
            NotifyEvent(NotifyCategory.DONE, "done:${phase.trackerValue}")
        }
        else -> null
    }

    /** Context bij een PROGRESS-melding: de gepromote description (refining klaar) of het subtaak-overzicht. */
    private fun progressContext(issue: TrackerIssue): String? =
        when (StoryPhase.fromTracker(issue.fields.storyPhase)) {
            StoryPhase.PLANNING -> issue.description?.takeIf { it.isNotBlank() }
            StoryPhase.PLANNING_APPROVED -> planningOverview(issue)
            else -> null
        }

    /** Subtaak-overzicht voor de 'planning klaar'-melding: storyregel + per subtaak `[ ]`/`[X]`. */
    private fun planningOverview(issue: TrackerIssue): String? {
        val subtasks = runCatching { issueTrackerClient.subtasksOf(issue.key) }.getOrNull() ?: return null
        val lines = mutableListOf("${issue.key} ${issue.summary}")
        subtasks.forEach { lines += "${checkbox(it)} ${it.key} ${it.summary}" }
        return lines.joinToString("\n")
    }

    /** `[X]` als de subtaak terminaal is, anders `[ ]`. */
    private fun checkbox(subtask: TrackerIssue): String =
        if (SubtaskPhase.fromTracker(subtask.fields.subtaskPhase)?.isTerminal == true) "[X]" else "[ ]"

    /** Bericht + reply-koppeling voor een terminaal geworden subtaak bij actieve auto-approve. */
    private fun notifySubtaskDone(
        subtask: TrackerIssue,
        event: NotifyEvent,
        allIssues: List<TrackerIssue>,
        signature: String,
        chatId: String,
    ) {
        val info = buildSubtaskDoneInfo(subtask, allIssues)
        // Een afgeronde test-subtaak krijgt extra context (testrapport + preview-link in de tekst) en de
        // tester-screenshots als losse foto's. Alle andere subtaaktypen blijven exact zoals voorheen.
        val isTest = SubtaskType.fromTracker(subtask.fields.subtaskType) == SubtaskType.TEST
        val parentKey = info.parentKey
        val extraSections = mutableListOf<String>()
        var screenshots: List<TrackerAttachment> = emptyList()
        if (isTest && parentKey != null) {
            testerReport(parentKey)?.let { extraSections += "📋 Testrapport\n$it" }
            previewUrlLine(parentKey)?.let { extraSections += it }
            screenshots = testerScreenshots(parentKey)
            val overflow = screenshots.drop(MAX_SCREENSHOT_PHOTOS).mapNotNull { screenshotLink(it) }
            if (overflow.isNotEmpty()) {
                extraSections += "🖼️ Meer screenshots:\n${overflow.joinToString("\n")}"
            }
        }
        val message = buildMessage(subtask, event, info.text, mergeOffer = info.mergeInfo != null, extraSections = extraSections)
        val messageId = telegramClient.sendMessage(message, chatId = chatId)
        if (messageId == null) {
            logger.warn("Telegram-melding voor {} kon niet verstuurd worden; volgende poll opnieuw.", subtask.key)
            return
        }
        // Eerst de idempotentie vastleggen, dan pas de foto's: zo triggert een gefaalde sendPhoto geen
        // herverzending van de tekstmelding bij de volgende poll.
        store.recordNotified(subtask.key, signature)
        // Reply-koppeling zodat "merge" werkt; loopt expliciet (PROGRESS/DONE zijn niet replyable).
        info.mergeInfo?.let { merge ->
            store.savePending(chatId, messageId, merge.storyKey, "STORY", MERGE_READY_PHASE)
        }
        if (isTest && screenshots.isNotEmpty()) {
            sendTesterScreenshots(screenshots.take(MAX_SCREENSHOT_PHOTOS), chatId)
        }
    }

    /** Het tester-rapport voor [parentKey], afgekapt op een Telegram-veilige lengte. Soft-fail → null. */
    private fun testerReport(parentKey: String): String? =
        runCatching { dashboardService.testerReportFor(parentKey) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.take(TESTER_REPORT_LIMIT)

    /** Preview-/test-URL-regel voor [parentKey], of null voor projecten zonder preview. Soft-fail. */
    private fun previewUrlLine(parentKey: String): String? =
        runCatching { dashboardService.previewUrlFor(parentKey) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { "🔗 Preview: $it" }

    /** De tester-screenshot-attachments op [parentKey], op naam gesorteerd. Soft-fail → leeg. */
    private fun testerScreenshots(parentKey: String): List<TrackerAttachment> =
        runCatching {
            issueTrackerClient.listIssueAttachments(parentKey)
                .filter { it.name.startsWith(TesterScreenshots.ATTACHMENT_PREFIX) }
                .sortedBy { it.name }
        }.getOrNull().orEmpty()

    /** Klikbare link naar een screenshot-attachment, of null zonder (absolute) URL. */
    private fun screenshotLink(attachment: TrackerAttachment): String? =
        attachment.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    /**
     * Stuurt elke screenshot als foto naar [chatId]. Bytes gaan naar een tijdelijk bestand dat na verzenden
     * weer wordt opgeruimd. Soft-fail per screenshot: een mislukte download of [TelegramClient.sendPhoto]
     * (return false) blokkeert de rest niet en heeft geen invloed op de al-verstuurde tekstmelding.
     */
    private fun sendTesterScreenshots(attachments: List<TrackerAttachment>, chatId: String) {
        attachments.forEach { attachment ->
            val bytes = runCatching { issueTrackerClient.downloadAttachmentBytes(attachment) }.getOrNull()
                ?: return@forEach
            runCatching {
                val tempFile = Files.createTempFile("sf-tester-screenshot-", screenshotSuffix(attachment.name))
                try {
                    Files.write(tempFile, bytes)
                    telegramClient.sendPhoto(chatId, tempFile, caption = screenshotCaption(attachment.name))
                } finally {
                    runCatching { Files.deleteIfExists(tempFile) }
                }
            }.onFailure {
                logger.debug("Tester-screenshot {} kon niet verstuurd worden (genegeerd).", attachment.name, it)
            }
        }
    }

    /** Bestandssuffix voor de tempfile, afgeleid van de attachment-naam (default .png). */
    private fun screenshotSuffix(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return if (extension in TesterScreenshots.EXTENSIONS) ".$extension" else ".png"
    }

    /** Korte caption: de attachment-naam zonder de interne factory-prefix. */
    private fun screenshotCaption(name: String): String =
        name.removePrefix(TesterScreenshots.ATTACHMENT_PREFIX).ifBlank { name }

    private data class SubtaskDoneInfo(
        val text: String,
        val mergeInfo: MergeReadyInfo?,
        val parentKey: String?,
    )

    /**
     * Bouwt de context voor een afgeronde subtaak: het story-overzicht met `[X]`/`[ ]`-markering per
     * subtaak. Is de hele story af, dan een afrond-regel en — als er een nog-niet-gemergede PR ligt —
     * de merge-info zodat het bericht een merge-actie kan aanbieden. Ontbrekende data degradeert netjes.
     */
    private fun buildSubtaskDoneInfo(subtask: TrackerIssue, allIssues: List<TrackerIssue>): SubtaskDoneInfo {
        val parentKey = runCatching { issueTrackerClient.parentStoryKey(subtask.key) }.getOrNull()
        val parent = parentKey?.let { key ->
            allIssues.firstOrNull { it.key == key } ?: runCatching { issueTrackerClient.getIssue(key) }.getOrNull()
        }
        val subtasks = parentKey
            ?.let { runCatching { issueTrackerClient.subtasksOf(it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(subtask)
        val lines = mutableListOf<String>()
        parent?.let { lines += "${it.key} ${it.summary}" }
        subtasks.forEach { lines += "${checkbox(it)} ${it.key} ${it.summary}" }
        val allTerminal = subtasks.all { SubtaskPhase.fromTracker(it.fields.subtaskPhase)?.isTerminal == true }
        var text = lines.joinToString("\n")
        if (allTerminal) text += "\n\nStory helemaal afgerond! 🎉"
        val mergeInfo = if (allTerminal && parentKey != null) {
            runCatching { dashboardService.mergeReady(parentKey) }.getOrNull()
        } else {
            null
        }
        return SubtaskDoneInfo(text, mergeInfo, parentKey)
    }

    /** Telegram-specifiek: 'klaar'-melding voor een terminale subtaak (geen wacht-op-mens-moment). */
    private fun classifySubtaskDone(phase: SubtaskPhase?): NotifyEvent? =
        if (phase != null && phase.isTerminal) {
            NotifyEvent(NotifyCategory.DONE, "done:${phase.trackerValue}")
        } else {
            null
        }

    private fun buildMessage(
        issue: TrackerIssue,
        event: NotifyEvent,
        context: String?,
        mergeOffer: Boolean = false,
        extraSections: List<String> = emptyList(),
    ): String {
        val header = event.header ?: when (event.category) {
            NotifyCategory.QUESTION -> "❓ De Software Factory heeft een vraag"
            NotifyCategory.APPROVAL -> "🔍 Beoordeling nodig"
            NotifyCategory.MANUAL -> "🙋 Handmatige actie nodig"
            NotifyCategory.PROGRESS -> "ℹ️ Voortgang"
            NotifyCategory.DONE -> "✅ Klaar"
            NotifyCategory.ERROR -> "⚠️ Fout in de Software Factory"
        }
        val lines = mutableListOf(header, "", "${issue.key}: ${issue.summary}")
        when (event.category) {
            NotifyCategory.QUESTION,
            NotifyCategory.APPROVAL,
            NotifyCategory.MANUAL,
            -> context?.takeIf { it.isNotBlank() }?.let { lines += listOf("", it.take(800)) }
            // PROGRESS-description en subtaak-overzicht / afgeronde-subtaak-context: ruimere afkapping.
            NotifyCategory.PROGRESS,
            NotifyCategory.DONE,
            -> context?.takeIf { it.isNotBlank() }?.let { lines += listOf("", it.take(1200)) }
            NotifyCategory.ERROR -> issue.fields.error?.takeIf { it.isNotBlank() }?.let { lines += listOf("", it.take(500)) }
        }
        // Extra secties (bv. testrapport + preview-link bij een afgeronde test-subtaak), niet her-afgekapt:
        // de aanroeper kapt elke sectie zelf netjes af.
        extraSections.forEach { section -> section.takeIf { it.isNotBlank() }?.let { lines += listOf("", it) } }
        when (event.category) {
            NotifyCategory.QUESTION -> lines += listOf("", "↩️ Antwoord door op dit bericht te replyen.")
            NotifyCategory.APPROVAL -> lines += listOf("", "↩️ Reply \"approve\" om goed te keuren, of typ feedback om terug te sturen.")
            NotifyCategory.MANUAL -> if (event.sourcePhase == SubtaskPhase.MANUAL_APPROVE_NEEDED.trackerValue) {
                lines += listOf("", "↩️ Reply \"approve\" om goed te keuren, of typ een reden om af te keuren en de story opnieuw te starten.")
            } else {
                lines += listOf("", "↩️ Reply op dit bericht om als klaar te markeren.")
            }
            NotifyCategory.DONE -> if (mergeOffer) {
                lines += listOf("", "↩️ Reply \"merge\" om de PR naar main te mergen (squash).")
            }
            else -> Unit
        }
        linkFor(issue.key)?.let { lines += listOf("", it) }
        return lines.joinToString("\n")
    }

    /** Klikbare dashboard-link, of `null` als er geen `SF_DASHBOARD_BASE_URL` is geconfigureerd. */
    private fun linkFor(issueKey: String): String? =
        secrets.dashboardBaseUrl?.takeIf { it.isNotBlank() }?.trimEnd('/')?.let { "$it/stories/$issueKey" }

    private companion object {
        private const val MERGE_READY_SIGNATURE = "merge-ready"

        /** Maximaal aantal screenshots dat als foto wordt verstuurd; de rest komt als link in de tekst. */
        private const val MAX_SCREENSHOT_PHOTOS = 10

        /** Afkaplengte van het testrapport, in dezelfde orde als de bestaande DONE-context-afkapping. */
        private const val TESTER_REPORT_LIMIT = 1200
    }
}
