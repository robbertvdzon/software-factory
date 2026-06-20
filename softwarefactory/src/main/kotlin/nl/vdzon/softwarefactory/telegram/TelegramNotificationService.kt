package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.core.IssueType
import nl.vdzon.softwarefactory.core.StoryPhase
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.web.services.FactoryDashboardService
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Soort melding; bepaalt de kop en of er op geantwoord kan worden. */
private enum class NotifyCategory { QUESTION, DONE, ERROR }

/** Een melding-waardige toestand van één issue, met een idempotentie-signature. */
private data class NotifyEvent(
    val category: NotifyCategory,
    /** Stabiele signature per toestand: één melding per transitie. */
    val signature: String,
    /** De bron-fase (trackerValue) bij een QUESTION, voor de reply-koppeling. */
    val sourcePhase: String? = null,
)

/**
 * Stuurt een Telegram-melding zodra een story/subtask in een melding-waardige fase komt:
 *  - **QUESTION** — de factory wacht op een antwoord (`*-with-questions` / `awaiting-human`);
 *    op deze berichten kun je via reply antwoorden.
 *  - **DONE** — refinement goedgekeurd (story) of een subtaak afgerond (terminaal).
 *  - **ERROR** — het issue staat in error.
 *
 * Idempotent via [TelegramStore]: per (issue, toestand) hooguit één bericht, ook over herstarts heen.
 * Wordt aangeroepen vanuit de orchestrator-poll, dus op diens adaptieve cadans (geen eigen polling).
 */
@Service
class TelegramNotificationService(
    private val issueTrackerClient: YouTrackApi,
    private val dashboardService: FactoryDashboardService,
    private val telegramClient: TelegramClient,
    private val store: TelegramStore,
    private val secrets: FactorySecrets,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun notifyPending() {
        if (!telegramClient.enabled) return
        val issues = runCatching { issueTrackerClient.findWorkIssues(maxResults = 200) }
            .getOrElse {
                logger.debug("Telegram-notify: kon work-issues niet laden (genegeerd).", it)
                return
            }
        for (issue in issues) {
            val event = classify(issue) ?: continue
            if (store.alreadyNotified(issue.key, event.signature)) continue
            val question = if (event.category == NotifyCategory.QUESTION) {
                runCatching { dashboardService.questionFor(issue) }.getOrNull()
            } else {
                null
            }
            val messageId = telegramClient.sendMessage(buildMessage(issue, event, question))
            // Pas vastleggen als het bericht ook echt verstuurd is, anders proberen we het later opnieuw.
            if (messageId == null) {
                logger.warn("Telegram-melding voor {} kon niet verstuurd worden; volgende poll opnieuw.", issue.key)
                continue
            }
            store.recordNotified(issue.key, event.signature)
            if (event.category == NotifyCategory.QUESTION && event.sourcePhase != null) {
                val level = if (issue.issueType == IssueType.SUBTASK) "SUBTASK" else "STORY"
                store.savePending(messageId, issue.key, level, event.sourcePhase)
            }
        }
    }

    private fun classify(issue: TrackerIssue): NotifyEvent? {
        val error = issue.fields.error?.takeIf { it.isNotBlank() }
        if (error != null) {
            return NotifyEvent(NotifyCategory.ERROR, "error:${error.hashCode()}")
        }
        return when (issue.issueType) {
            IssueType.STORY -> when (val phase = StoryPhase.fromTracker(issue.fields.storyPhase)) {
                StoryPhase.REFINED_WITH_QUESTIONS,
                StoryPhase.PLANNED_WITH_QUESTIONS,
                -> NotifyEvent(NotifyCategory.QUESTION, "q:${phase.trackerValue}", phase.trackerValue)
                StoryPhase.PLANNING_APPROVED,
                -> NotifyEvent(NotifyCategory.DONE, "done:${phase.trackerValue}")
                else -> null
            }
            IssueType.SUBTASK -> when (val phase = SubtaskPhase.fromTracker(issue.fields.subtaskPhase)) {
                SubtaskPhase.DEVELOPED_WITH_QUESTIONS,
                SubtaskPhase.REVIEWED_WITH_QUESTIONS,
                SubtaskPhase.TESTED_WITH_QUESTIONS,
                SubtaskPhase.SUMMARY_WITH_QUESTIONS,
                SubtaskPhase.AWAITING_HUMAN,
                -> NotifyEvent(NotifyCategory.QUESTION, "q:${phase.trackerValue}", phase.trackerValue)
                else -> if (phase != null && phase.isTerminal) {
                    NotifyEvent(NotifyCategory.DONE, "done:${phase.trackerValue}")
                } else {
                    null
                }
            }
        }
    }

    private fun buildMessage(issue: TrackerIssue, event: NotifyEvent, question: String?): String {
        val header = when (event.category) {
            NotifyCategory.QUESTION -> "❓ De Software Factory heeft een vraag"
            NotifyCategory.DONE -> "✅ Klaar"
            NotifyCategory.ERROR -> "⚠️ Fout in de Software Factory"
        }
        val lines = mutableListOf(header, "", "${issue.key}: ${issue.summary}")
        when (event.category) {
            NotifyCategory.QUESTION -> question?.takeIf { it.isNotBlank() }?.let { lines += listOf("", it) }
            NotifyCategory.ERROR -> issue.fields.error?.takeIf { it.isNotBlank() }?.let { lines += listOf("", it.take(500)) }
            NotifyCategory.DONE -> Unit
        }
        if (event.category == NotifyCategory.QUESTION) {
            lines += listOf("", "↩️ Antwoord door op dit bericht te replyen.")
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
}
