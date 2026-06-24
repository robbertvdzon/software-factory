package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.core.IssueType
import nl.vdzon.softwarefactory.core.StoryPhase
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.web.services.FactoryDashboardService
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

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
 * volgt bewust exact de "My actions"-inbox van het dashboard (zie `FactoryDashboardService.awaitsHuman`
 * en de kaarten in `FactoryDashboardViews`): staat het in je inbox, dan krijg je een Telegram-bericht.
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
    private val issueTrackerClient: YouTrackApi,
    private val dashboardService: FactoryDashboardService,
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

    private fun buildMergeReadyMessage(merge: FactoryDashboardService.MergeReadyInfo): String {
        val lines = mutableListOf("🎉 Klaar om te mergen", "", "${merge.storyKey} is afgerond.")
        merge.prUrl?.let { lines += listOf("", "PR #${merge.prNumber}: $it") }
        lines += listOf("", "↩️ Reply \"merge\" om de PR naar main te mergen (squash).")
        lines += listOf("", linkFor(merge.storyKey))
        return lines.joinToString("\n")
    }

    private fun classify(issue: TrackerIssue): NotifyEvent? {
        val error = issue.fields.error?.takeIf { it.isNotBlank() }
        if (error != null) {
            return NotifyEvent(NotifyCategory.ERROR, "error:${error.hashCode()}")
        }
        // Auto-approve staat op de PARENT-story; voor subtaken dus via de parent resolven (mirror van
        // FactoryDashboardService.awaitsHuman / SubtaskExecutionCoordinator). Issue.fields.autoApprove
        // alleen is fout voor subtaken: dan kwam er toch een "Beoordeling nodig"-melding (SF-170).
        val autoApprove = dashboardService.autoApproveActive(issue)
        return when (issue.issueType) {
            IssueType.STORY -> classifyStory(StoryPhase.fromTracker(issue.fields.storyPhase), autoApprove)
            IssueType.SUBTASK -> classifySubtask(issue, SubtaskPhase.fromTracker(issue.fields.subtaskPhase), autoApprove)
        }
    }

    private fun classifyStory(phase: StoryPhase?, autoApprove: Boolean): NotifyEvent? = when (phase) {
        StoryPhase.REFINED_WITH_QUESTIONS,
        StoryPhase.PLANNED_WITH_QUESTIONS,
        -> NotifyEvent(NotifyCategory.QUESTION, "q:${phase.trackerValue}", phase.trackerValue)
        StoryPhase.REFINED,
        StoryPhase.PLANNED,
        -> if (autoApprove) null else NotifyEvent(NotifyCategory.APPROVAL, "approve:${phase.trackerValue}", phase.trackerValue)
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
        val message = buildMessage(subtask, event, info.text, mergeOffer = info.mergeInfo != null)
        val messageId = telegramClient.sendMessage(message, chatId = chatId)
        if (messageId == null) {
            logger.warn("Telegram-melding voor {} kon niet verstuurd worden; volgende poll opnieuw.", subtask.key)
            return
        }
        store.recordNotified(subtask.key, signature)
        // Reply-koppeling zodat "merge" werkt; loopt expliciet (PROGRESS/DONE zijn niet replyable).
        info.mergeInfo?.let { merge ->
            store.savePending(chatId, messageId, merge.storyKey, "STORY", MERGE_READY_PHASE)
        }
    }

    private data class SubtaskDoneInfo(val text: String, val mergeInfo: FactoryDashboardService.MergeReadyInfo?)

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
        return SubtaskDoneInfo(text, mergeInfo)
    }

    private fun classifySubtask(issue: TrackerIssue, phase: SubtaskPhase?, autoApprove: Boolean): NotifyEvent? = when (phase) {
        SubtaskPhase.DEVELOPED_WITH_QUESTIONS,
        SubtaskPhase.REVIEWED_WITH_QUESTIONS,
        SubtaskPhase.TESTED_WITH_QUESTIONS,
        SubtaskPhase.SUMMARY_WITH_QUESTIONS,
        -> NotifyEvent(NotifyCategory.QUESTION, "q:${phase.trackerValue}", phase.trackerValue)
        SubtaskPhase.AWAITING_HUMAN,
        -> NotifyEvent(NotifyCategory.MANUAL, "manual:${phase.trackerValue}", phase.trackerValue)
        // Een 'developed' subtaak wacht alleen op de mens bij type development (review/test/summary
        // auto-advancen). Mirror van FactoryDashboardService.awaitsHuman.
        SubtaskPhase.DEVELOPED -> if (!autoApprove && issue.fields.subtaskType.equals("development", ignoreCase = true)) {
            NotifyEvent(NotifyCategory.APPROVAL, "approve:${phase.trackerValue}", phase.trackerValue)
        } else {
            null
        }
        SubtaskPhase.REVIEWED,
        SubtaskPhase.TESTED,
        SubtaskPhase.SUMMARIZED,
        -> if (autoApprove) null else NotifyEvent(NotifyCategory.APPROVAL, "approve:${phase.trackerValue}", phase.trackerValue)
        else -> if (phase != null && phase.isTerminal) {
            NotifyEvent(NotifyCategory.DONE, "done:${phase.trackerValue}")
        } else {
            null
        }
    }

    private fun buildMessage(issue: TrackerIssue, event: NotifyEvent, context: String?, mergeOffer: Boolean = false): String {
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
        when (event.category) {
            NotifyCategory.QUESTION -> lines += listOf("", "↩️ Antwoord door op dit bericht te replyen.")
            NotifyCategory.APPROVAL -> lines += listOf("", "↩️ Reply \"approve\" om goed te keuren, of typ feedback om terug te sturen.")
            NotifyCategory.MANUAL -> lines += listOf("", "↩️ Reply op dit bericht om als klaar te markeren.")
            NotifyCategory.DONE -> if (mergeOffer) {
                lines += listOf("", "↩️ Reply \"merge\" om de PR naar main te mergen (squash).")
            }
            else -> Unit
        }
        lines += listOf("", linkFor(issue.key))
        return lines.joinToString("\n")
    }

    /** Klikbare link: dashboard wanneer geconfigureerd, anders de YouTrack-issuelink. */
    private fun linkFor(issueKey: String): String {
        val dashboard = secrets.dashboardBaseUrl?.takeIf { it.isNotBlank() }?.trimEnd('/')
        return if (dashboard != null) {
            "$dashboard/stories/$issueKey"
        } else {
            "${secrets.youTrackPublicUrl.trimEnd('/')}/issue/$issueKey"
        }
    }

    private companion object {
        private const val MERGE_READY_SIGNATURE = "merge-ready"
    }
}
