package nl.vdzon.softwarefactory.telegram.services

import nl.vdzon.softwarefactory.telegram.models.*

import nl.vdzon.softwarefactory.telegram.clients.*
import nl.vdzon.softwarefactory.telegram.repositories.*
import nl.vdzon.softwarefactory.telegram.TelegramNotifier
import nl.vdzon.softwarefactory.telegram.MERGE_READY_PHASE

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectTelegramSettings
import nl.vdzon.softwarefactory.core.contracts.FactoryOperations
import nl.vdzon.softwarefactory.core.contracts.HumanActionPolicy
import nl.vdzon.softwarefactory.core.contracts.HumanGate
import nl.vdzon.softwarefactory.core.contracts.IssueType
import nl.vdzon.softwarefactory.core.contracts.MergeReadyInfo
import nl.vdzon.softwarefactory.core.contracts.NotificationEvent
import nl.vdzon.softwarefactory.core.contracts.StoryPhase
import nl.vdzon.softwarefactory.core.contracts.SubtaskPhase
import nl.vdzon.softwarefactory.core.contracts.SubtaskType
import nl.vdzon.softwarefactory.core.contracts.TesterScreenshots
import nl.vdzon.softwarefactory.core.contracts.TrackerAttachment
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.support.ControlJsonStripper
import nl.vdzon.softwarefactory.tracker.TrackerCapabilities
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files

/**
 * Interne berichtcategorie. De eerste drie zijn "actie nodig" — daarop kun je via een
 * Telegram-reply reageren (zie [TelegramReplyService]); de overige zijn informatief. Elke
 * categorie vertaalt exact naar een publiek [NotificationEvent].
 */
private enum class NotifyCategory { QUESTION, APPROVAL, MANUAL, QUOTA, PROGRESS, DONE, WORKFLOW_DONE, ERROR }

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
 * Stuurt een Telegram-melding zodra een geselecteerd [NotificationEvent] op een story/subtask
 * optreedt. Workflowbesturing en Telegramselectie zijn bewust onafhankelijk.
 *
 *  - **QUESTION** — een agent stelt een vraag (`*-with-questions`); reply = antwoord.
 *  - **APPROVAL** — een stap is klaar en wacht op goedkeuring (refined/planned/developed/reviewed/
 *    tested/summarized); reply `approve` = goedkeuren, andere tekst = terugsturen met feedback.
 *  - **MANUAL** — een handmatige subtaak (`awaiting-human`); reply = als klaar markeren.
 *  - **PROGRESS/DONE** — een workflowstap of subtaak is afgerond (`STEP_COMPLETED`).
 *  - **WORKFLOW_DONE** — de volledige workflow is afgerond (`WORKFLOW_COMPLETED`).
 *  - **QUOTA** — de workflow wacht tot het quota-resetmoment (`QUOTA_WAIT`).
 *  - **ERROR** — het issue staat in error.
 *
 * Idempotent via [TelegramStore]: per (issue, toestand) hooguit één bericht, ook over herstarts heen.
 * Draait op de orchestrator-poll-cadans (geen eigen polling).
 */
@Service
class TelegramNotificationService(
    private val issueTrackerClient: TrackerCapabilities,
    private val dashboardService: FactoryOperations,
    private val telegramClient: TelegramClient,
    private val store: TelegramStore,
    private val secrets: FactorySecrets,
    private val projectRepoResolver: ProjectTelegramSettings,
) : TelegramNotifier {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun notifyPending() {
        if (!telegramClient.enabled) return
        val defaultChat = telegramClient.defaultChatId ?: return
        val issues = runCatching { issueTrackerClient.findWorkIssues(maxResults = 200) }
            .getOrElse {
                logger.debug("Telegram-notify: kon work-issues niet laden (genegeerd).", it)
                return
            }
        for (issue in issues) {
            notifyIssue(issue, issues, defaultChat)
        }
    }

    private fun notifyIssue(issue: TrackerIssue, allIssues: List<TrackerIssue>, defaultChat: String) {
        val selectedEvents = runCatching { issueTrackerClient.effectiveNotificationEvents(issue) }
            .getOrDefault(NotificationEvent.DEFAULT)
        classify(issue)
            .filter { notificationEventFor(it.category) in selectedEvents }
            .forEach { notifyEvent(issue, it, allIssues, defaultChat) }
    }

    private fun notifyEvent(
        issue: TrackerIssue,
        event: NotifyEvent,
        allIssues: List<TrackerIssue>,
        defaultChat: String,
    ) {
        val context = contextFor(issue, event)
        val signature = signatureFor(event, context)
        if (store.alreadyNotified(issue.key, signature)) return
        val chatId = channelFor(issue, defaultChat)
        when {
            event.signature == MERGE_READY_SIGNATURE -> notifyMergeReady(issue, chatId)
            event.category == NotifyCategory.DONE &&
                issue.issueType == IssueType.SUBTASK &&
                dashboardService.autoApproveActive(issue) ->
                notifySubtaskDone(issue, event, allIssues, signature, chatId)
            else -> notifyStandard(issue, event, context, signature, chatId)
        }
    }

    private fun contextFor(issue: TrackerIssue, event: NotifyEvent): String? = when {
        event.category.replyable -> runCatching { dashboardService.questionFor(issue) }.getOrNull()
        event.category == NotifyCategory.QUOTA -> runCatching { quotaContext(issue) }.getOrNull()
        event.category == NotifyCategory.PROGRESS -> runCatching { progressContext(issue) }.getOrNull()
        else -> null
    }

    private fun signatureFor(event: NotifyEvent, context: String?): String =
        if (event.category == NotifyCategory.QUOTA) {
            event.signature
        } else {
            context?.takeIf { it.isNotBlank() }?.let { "${event.signature}:${it.hashCode()}" } ?: event.signature
        }

    private fun notifyStandard(
        issue: TrackerIssue,
        event: NotifyEvent,
        context: String?,
        signature: String,
        chatId: String,
    ) {
        val messageId = telegramClient.sendMessage(buildMessage(issue, event, context), chatId = chatId)
        if (messageId == null) {
            logger.warn("Telegram-melding voor {} kon niet verstuurd worden; volgende poll opnieuw.", issue.key)
            return
        }
        store.recordNotified(issue.key, signature)
        if (event.category.replyable && event.sourcePhase != null) {
            val level = if (issue.issueType == IssueType.SUBTASK) "SUBTASK" else "STORY"
            store.savePending(chatId, messageId, issue.key, level, event.sourcePhase)
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

    /** Stuurt het zelfstandige `MANUAL_ACTION_REQUIRED`-event voor een mergeklare story. */
    private fun notifyMergeReady(subtask: TrackerIssue, chatId: String) {
        val merge = runCatching { dashboardService.mergeReadyForSubtask(subtask) }.getOrNull()
        if (merge != null && !store.alreadyNotified(merge.storyKey, MERGE_READY_SIGNATURE)) {
            val messageId = telegramClient.sendMessage(buildMergeReadyMessage(merge), chatId = chatId)
            if (messageId != null) {
                store.recordNotified(merge.storyKey, MERGE_READY_SIGNATURE)
                store.recordNotified(subtask.key, MERGE_READY_SIGNATURE)
                store.savePending(chatId, messageId, merge.storyKey, "STORY", MERGE_READY_PHASE)
            }
        }
    }

    private fun buildMergeReadyMessage(merge: MergeReadyInfo): String {
        val lines = mutableListOf("🎉 Klaar om te mergen", "", "${merge.storyKey} is afgerond.")
        merge.prUrl?.let { lines += listOf("", "PR #${merge.prNumber}: $it") }
        lines += listOf("", "↩️ Reply \"merge\" om de PR naar main te mergen (squash).")
        linkFor(merge.storyKey)?.let { lines += listOf("", it) }
        return lines.joinToString("\n")
    }

    private fun notificationEventFor(category: NotifyCategory): NotificationEvent = when (category) {
        NotifyCategory.QUESTION -> NotificationEvent.QUESTION
        NotifyCategory.APPROVAL -> NotificationEvent.APPROVAL_REQUIRED
        NotifyCategory.MANUAL -> NotificationEvent.MANUAL_ACTION_REQUIRED
        NotifyCategory.QUOTA -> NotificationEvent.QUOTA_WAIT
        NotifyCategory.ERROR -> NotificationEvent.ERROR
        NotifyCategory.PROGRESS, NotifyCategory.DONE -> NotificationEvent.STEP_COMPLETED
        NotifyCategory.WORKFLOW_DONE -> NotificationEvent.WORKFLOW_COMPLETED
    }

    /** Is dit de DONE-melding van de subtaak die als laatste van de story terminaal wordt? */
    private fun isStoryCompletingDone(issue: TrackerIssue, event: NotifyEvent): Boolean {
        if (event.category != NotifyCategory.DONE || issue.issueType != IssueType.SUBTASK) return false
        val parentKey = runCatching { issueTrackerClient.parentStoryKey(issue.key) }.getOrNull() ?: return false
        val subtasks = runCatching { issueTrackerClient.subtasksOf(parentKey) }.getOrNull() ?: return false
        return subtasks.isNotEmpty() && subtasks.all { SubtaskPhase.fromTracker(it.fields.subtaskPhase)?.isTerminal == true }
    }

    private fun classify(issue: TrackerIssue): List<NotifyEvent> {
        val retryAfter = issue.fields.retryAfter
        val error = issue.fields.error?.takeIf { it.isNotBlank() }
        return when {
            retryAfter != null -> listOf(NotifyEvent(
                NotifyCategory.QUOTA,
                "claude-quota:$retryAfter",
                header = "⏳ Gepauzeerd wegens Claude-quota",
            ))
            error != null -> listOf(NotifyEvent(NotifyCategory.ERROR, "error:${error.hashCode()}"))
            else -> classifyWorkflowEvents(issue)
        }
    }

    private fun classifyWorkflowEvents(issue: TrackerIssue): List<NotifyEvent> {
        // De wacht-op-mens-beslissing komt uit de centrale HumanActionPolicy (auto-approve via de
        // parent-story, zie SF-170); alleen de vertaling naar meldingscategorie/header en de
        // voortgangs-/klaar-meldingen hieronder zijn Telegram-specifiek.
        val autoApprove = dashboardService.autoApproveActive(issue)
        val phaseValue = when (issue.issueType) {
            IssueType.STORY -> StoryPhase.fromTracker(issue.fields.storyPhase)?.trackerValue
            IssueType.SUBTASK -> SubtaskPhase.fromTracker(issue.fields.subtaskPhase)?.trackerValue
        }
        val gateEvent = when (HumanActionPolicy.gateFor(issue)) {
            HumanGate.QUESTION -> NotifyEvent(NotifyCategory.QUESTION, "q:$phaseValue", phaseValue)
            HumanGate.MANUAL -> NotifyEvent(NotifyCategory.MANUAL, "manual:$phaseValue", phaseValue)
            HumanGate.APPROVAL -> if (autoApprove) null else NotifyEvent(NotifyCategory.APPROVAL, "approve:$phaseValue", phaseValue)
            null -> null
        }
        return gateEvent?.let(::listOf) ?: when (issue.issueType) {
            IssueType.STORY ->
                listOfNotNull(classifyStoryProgress(StoryPhase.fromTracker(issue.fields.storyPhase), autoApprove))
            IssueType.SUBTASK -> classifySubtaskEvents(issue)
        }
    }

    private fun classifySubtaskEvents(issue: TrackerIssue): List<NotifyEvent> {
        val done = classifySubtaskDone(SubtaskPhase.fromTracker(issue.fields.subtaskPhase)) ?: return emptyList()
        val events = mutableListOf(done)
        if (isStoryCompletingDone(issue, done)) {
            events += NotifyEvent(NotifyCategory.WORKFLOW_DONE, "workflow-completed")
            if (runCatching { dashboardService.mergeReadyForSubtask(issue) }.getOrNull() != null) {
                events += NotifyEvent(NotifyCategory.MANUAL, MERGE_READY_SIGNATURE, MERGE_READY_PHASE)
            }
        }
        return events
    }

    private fun quotaContext(issue: TrackerIssue): String {
        val until = requireNotNull(issue.fields.retryAfter)
        val parent = if (issue.issueType == IssueType.SUBTASK) {
            runCatching {
                issueTrackerClient.parentStoryKey(issue.key)?.let(issueTrackerClient::getIssue)
            }.getOrNull()
        } else {
            null
        }
        return buildString {
            parent?.let { appendLine("Story ${it.key}: ${it.summary}") }
            if (issue.issueType == IssueType.SUBTASK) appendLine("Subtaak ${issue.key}: ${issue.summary}")
            append("Automatische hervatting vanaf $until.")
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
        val message = buildMessage(subtask, event, info.text, extraSections = extraSections)
        val messageId = telegramClient.sendMessage(message, chatId = chatId)
        if (messageId == null) {
            logger.warn("Telegram-melding voor {} kon niet verstuurd worden; volgende poll opnieuw.", subtask.key)
            return
        }
        // Eerst de idempotentie vastleggen, dan pas de foto's: zo triggert een gefaalde sendPhoto geen
        // herverzending van de tekstmelding bij de volgende poll.
        store.recordNotified(subtask.key, signature)
        if (isTest && screenshots.isNotEmpty()) {
            sendTesterScreenshots(screenshots.take(MAX_SCREENSHOT_PHOTOS), chatId)
        }
    }

    /** Het tester-rapport voor [parentKey], afgekapt op een Telegram-veilige lengte. Soft-fail → null. */
    private fun testerReport(parentKey: String): String? =
        runCatching { dashboardService.testerReportFor(parentKey) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { ControlJsonStripper.stripTrailingControlJson(it) }
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
        val parentKey: String?,
    )

    /** Bouwt het story-overzicht voor een afgeronde subtaak; andere events blijven apart. */
    private fun buildSubtaskDoneInfo(
        subtask: TrackerIssue,
        allIssues: List<TrackerIssue>,
    ): SubtaskDoneInfo {
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
        return SubtaskDoneInfo(lines.joinToString("\n"), parentKey)
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
        extraSections: List<String> = emptyList(),
    ): String {
        val header = event.header ?: when (event.category) {
            NotifyCategory.QUESTION -> "❓ De Software Factory heeft een vraag"
            NotifyCategory.APPROVAL -> "🔍 Beoordeling nodig"
            NotifyCategory.MANUAL -> "🙋 Handmatige actie nodig"
            NotifyCategory.QUOTA -> "⏳ Gepauzeerd wegens Claude-quota"
            NotifyCategory.PROGRESS -> "ℹ️ Voortgang"
            NotifyCategory.DONE -> "✅ Klaar"
            NotifyCategory.WORKFLOW_DONE -> "🎉 Workflow afgerond"
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
            NotifyCategory.WORKFLOW_DONE,
            NotifyCategory.QUOTA,
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
