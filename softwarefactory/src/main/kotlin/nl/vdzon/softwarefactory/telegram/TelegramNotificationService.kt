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
 * (zie [TelegramReplyService]); DONE/ERROR zijn puur informatief.
 */
private enum class NotifyCategory { QUESTION, APPROVAL, MANUAL, DONE, ERROR }

private val NotifyCategory.replyable: Boolean
    get() = this == NotifyCategory.QUESTION || this == NotifyCategory.APPROVAL || this == NotifyCategory.MANUAL

/** Een melding-waardige toestand van één issue, met een idempotentie-signature. */
private data class NotifyEvent(
    val category: NotifyCategory,
    /** Stabiele signature per toestand: één melding per transitie. */
    val signature: String,
    /** De bron-fase (trackerValue) bij een reply-bare melding, voor de reply-koppeling. */
    val sourcePhase: String? = null,
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
            if (store.alreadyNotified(issue.key, event.signature)) continue
            // Project-kanaal van het issue (story: eigen Repo; subtaak: van de parent); anders globaal.
            val chatId = channelFor(issue, defaultChat)
            // "Het einde": een subtaak die z'n story afrondt -> bied merge aan i.p.v. een losse 'klaar'.
            if (event.category == NotifyCategory.DONE && issue.issueType == IssueType.SUBTASK &&
                tryNotifyMergeReady(issue, doneSignature = event.signature, chatId = chatId)
            ) {
                continue
            }
            // Context (vraagtekst of agent-resultaat) hoort bij alle reply-bare meldingen.
            val context = if (event.category.replyable) {
                runCatching { dashboardService.questionFor(issue) }.getOrNull()
            } else {
                null
            }
            val messageId = telegramClient.sendMessage(buildMessage(issue, event, context), chatId = chatId)
            // Pas vastleggen als het bericht ook echt verstuurd is, anders proberen we het later opnieuw.
            if (messageId == null) {
                logger.warn("Telegram-melding voor {} kon niet verstuurd worden; volgende poll opnieuw.", issue.key)
                continue
            }
            store.recordNotified(issue.key, event.signature)
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
        return when (issue.issueType) {
            IssueType.STORY -> classifyStory(StoryPhase.fromTracker(issue.fields.storyPhase))
            IssueType.SUBTASK -> classifySubtask(issue, SubtaskPhase.fromTracker(issue.fields.subtaskPhase))
        }
    }

    private fun classifyStory(phase: StoryPhase?): NotifyEvent? = when (phase) {
        StoryPhase.REFINED_WITH_QUESTIONS,
        StoryPhase.PLANNED_WITH_QUESTIONS,
        -> NotifyEvent(NotifyCategory.QUESTION, "q:${phase.trackerValue}", phase.trackerValue)
        StoryPhase.REFINED,
        StoryPhase.PLANNED,
        -> NotifyEvent(NotifyCategory.APPROVAL, "approve:${phase.trackerValue}", phase.trackerValue)
        StoryPhase.PLANNING_APPROVED,
        -> NotifyEvent(NotifyCategory.DONE, "done:${phase.trackerValue}")
        else -> null
    }

    private fun classifySubtask(issue: TrackerIssue, phase: SubtaskPhase?): NotifyEvent? = when (phase) {
        SubtaskPhase.DEVELOPED_WITH_QUESTIONS,
        SubtaskPhase.REVIEWED_WITH_QUESTIONS,
        SubtaskPhase.TESTED_WITH_QUESTIONS,
        SubtaskPhase.SUMMARY_WITH_QUESTIONS,
        -> NotifyEvent(NotifyCategory.QUESTION, "q:${phase.trackerValue}", phase.trackerValue)
        SubtaskPhase.AWAITING_HUMAN,
        -> NotifyEvent(NotifyCategory.MANUAL, "manual:${phase.trackerValue}", phase.trackerValue)
        // Een 'developed' subtaak wacht alleen op de mens bij type development (review/test/summary
        // auto-advancen). Mirror van FactoryDashboardService.awaitsHuman.
        SubtaskPhase.DEVELOPED -> if (issue.fields.subtaskType.equals("development", ignoreCase = true)) {
            NotifyEvent(NotifyCategory.APPROVAL, "approve:${phase.trackerValue}", phase.trackerValue)
        } else {
            null
        }
        SubtaskPhase.REVIEWED,
        SubtaskPhase.TESTED,
        SubtaskPhase.SUMMARIZED,
        -> NotifyEvent(NotifyCategory.APPROVAL, "approve:${phase.trackerValue}", phase.trackerValue)
        else -> if (phase != null && phase.isTerminal) {
            NotifyEvent(NotifyCategory.DONE, "done:${phase.trackerValue}")
        } else {
            null
        }
    }

    private fun buildMessage(issue: TrackerIssue, event: NotifyEvent, context: String?): String {
        val header = when (event.category) {
            NotifyCategory.QUESTION -> "❓ De Software Factory heeft een vraag"
            NotifyCategory.APPROVAL -> "🔍 Beoordeling nodig"
            NotifyCategory.MANUAL -> "🙋 Handmatige actie nodig"
            NotifyCategory.DONE -> "✅ Klaar"
            NotifyCategory.ERROR -> "⚠️ Fout in de Software Factory"
        }
        val lines = mutableListOf(header, "", "${issue.key}: ${issue.summary}")
        when (event.category) {
            NotifyCategory.QUESTION,
            NotifyCategory.APPROVAL,
            NotifyCategory.MANUAL,
            -> context?.takeIf { it.isNotBlank() }?.let { lines += listOf("", it.take(800)) }
            NotifyCategory.ERROR -> issue.fields.error?.takeIf { it.isNotBlank() }?.let { lines += listOf("", it.take(500)) }
            NotifyCategory.DONE -> Unit
        }
        when (event.category) {
            NotifyCategory.QUESTION -> lines += listOf("", "↩️ Antwoord door op dit bericht te replyen.")
            NotifyCategory.APPROVAL -> lines += listOf("", "↩️ Reply \"approve\" om goed te keuren, of typ feedback om terug te sturen.")
            NotifyCategory.MANUAL -> lines += listOf("", "↩️ Reply op dit bericht om als klaar te markeren.")
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
