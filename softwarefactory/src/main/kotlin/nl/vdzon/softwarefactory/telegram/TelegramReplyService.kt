package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.core.ChangeNotifier
import nl.vdzon.softwarefactory.core.FactoryCommand
import nl.vdzon.softwarefactory.core.FactoryStateChangedEvent
import nl.vdzon.softwarefactory.core.StoryPhase
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.web.services.FactoryDashboardService
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

/**
 * Verwerkt een Telegram-reply op een eerder verstuurde "actie nodig"-melding. Het antwoord loopt via
 * exact dezelfde route als het dashboard ([FactoryDashboardService.setStoryPhase] / [setSubtaskPhase]):
 * de tekst wordt als comment gepost en de fase schuift naar de juiste vervolgstap, zodat de keten
 * doorgaat. Daarna wordt de orchestrator-poller direct gewekt.
 *
 * Drie soorten reply, afgeleid uit de opgeslagen bron-fase:
 *  - **vraag** (`*-with-questions`) -> `*-questions-answered` met de tekst als antwoord.
 *  - **beoordeling** (refined/planned/developed/reviewed/tested/summarized) -> `approve` bij een
 *    instemmend woord, anders `reject` met de tekst als feedback.
 *  - **handmatig** (`awaiting-human`) -> `manual-action-done`.
 */
@Service
class TelegramReplyService(
    private val dashboardService: FactoryDashboardService,
    private val store: TelegramStore,
    private val telegramClient: TelegramClient,
    private val changeNotifier: ChangeNotifier,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Probeert [update] als antwoord op een openstaande melding te verwerken. Geen reply, onbekend
     * bericht of lege tekst => stilletjes negeren (return false). Bij succes return true.
     */
    fun handleReply(update: TelegramUpdate): Boolean {
        val replyTo = update.replyToMessageId ?: return false
        val chatId = update.chatId ?: return false
        val pending = store.findPending(chatId, replyTo) ?: return false
        val answer = update.text?.takeIf { it.isNotBlank() } ?: return false

        // "Klaar om te mergen"-melding: alleen een expliciete 'merge' triggert de merge naar main.
        // Iets anders => pending laten staan zodat een latere 'merge'-reply alsnog werkt.
        if (pending.sourcePhase == MERGE_READY_PHASE) {
            if (!isMergeKeyword(answer)) return false
            dashboardService.queueCommand(pending.issueKey, FactoryCommand.MERGE)
            store.deletePending(chatId, replyTo)
            store.clearNotifications(pending.issueKey)
            announce(pending.issueKey)
            runCatching {
                telegramClient.sendMessage("🚀 Merge gestart voor ${pending.issueKey}.", replyToMessageId = replyTo, chatId = chatId)
            }
            logger.info("Telegram-reply op {} verwerkt: merge gequeued.", pending.issueKey)
            return true
        }

        val outcome = when (pending.issueLevel) {
            "STORY" -> applyStory(pending.issueKey, pending.sourcePhase, answer)
            "SUBTASK" -> applySubtask(pending.issueKey, pending.sourcePhase, answer)
            else -> null
        } ?: return false

        store.deletePending(chatId, replyTo)
        // Wis de melding-registratie: stelt de refiner/agent na dit antwoord nieuwe vragen, dan moet
        // díé volgende ronde wél weer een melding geven (zelfde fase => zou anders onderdrukt worden).
        store.clearNotifications(pending.issueKey)
        announce(pending.issueKey)
        runCatching {
            telegramClient.sendMessage("✅ ${outcome} voor ${pending.issueKey}.", replyToMessageId = replyTo, chatId = chatId)
        }
        logger.info("Telegram-reply op {} verwerkt: {}.", pending.issueKey, outcome)
        return true
    }

    /** Wek de orchestrator direct (zoals AgentRunCompletionService) en ververs open dashboards. */
    private fun announce(issueKey: String) {
        runCatching { eventPublisher.publishEvent(FactoryStateChangedEvent("telegram-reply:$issueKey")) }
            .onFailure { logger.debug("Kon FactoryStateChangedEvent niet publiceren (genegeerd).", it) }
        runCatching { changeNotifier.notifyChanged() }
            .onFailure { logger.debug("ChangeNotifier faalde (genegeerd).", it) }
    }

    private fun isMergeKeyword(answer: String): Boolean =
        answer.trim().trimEnd('!', '.', ' ').lowercase() in setOf("merge", "mergen", "squash")

    /** @return korte omschrijving van de actie (voor de bevestiging), of null als de fase onbekend is. */
    private fun applyStory(storyKey: String, sourcePhase: String, answer: String): String? {
        val phase = StoryPhase.fromTracker(sourcePhase) ?: return null
        return when (phase) {
            StoryPhase.REFINED_WITH_QUESTIONS -> {
                dashboardService.setStoryPhase(storyKey, StoryPhase.QUESTIONS_ANSWERED.trackerValue, answer)
                "Antwoord doorgestuurd"
            }
            StoryPhase.PLANNED_WITH_QUESTIONS -> {
                dashboardService.setStoryPhase(storyKey, StoryPhase.PLANNING_QUESTIONS_ANSWERED.trackerValue, answer)
                "Antwoord doorgestuurd"
            }
            StoryPhase.REFINED -> approveStory(storyKey, answer, StoryPhase.REFINED_APPROVED, StoryPhase.REFINED_REJECTED)
            StoryPhase.PLANNED -> approveStory(storyKey, answer, StoryPhase.PLANNING_APPROVED, StoryPhase.PLANNING_REJECTED)
            else -> null
        }
    }

    private fun applySubtask(subtaskKey: String, sourcePhase: String, answer: String): String? {
        val phase = SubtaskPhase.fromTracker(sourcePhase) ?: return null
        return when (phase) {
            SubtaskPhase.DEVELOPED_WITH_QUESTIONS -> answerSubtask(subtaskKey, answer, SubtaskPhase.DEVELOPMENT_QUESTIONS_ANSWERED)
            SubtaskPhase.REVIEWED_WITH_QUESTIONS -> answerSubtask(subtaskKey, answer, SubtaskPhase.REVIEW_QUESTIONS_ANSWERED)
            SubtaskPhase.TESTED_WITH_QUESTIONS -> answerSubtask(subtaskKey, answer, SubtaskPhase.TEST_QUESTIONS_ANSWERED)
            SubtaskPhase.SUMMARY_WITH_QUESTIONS -> answerSubtask(subtaskKey, answer, SubtaskPhase.SUMMARY_QUESTIONS_ANSWERED)
            SubtaskPhase.DEVELOPED -> approveSubtask(subtaskKey, answer, SubtaskPhase.DEVELOPMENT_APPROVED, SubtaskPhase.DEVELOPMENT_REJECTED)
            SubtaskPhase.REVIEWED -> approveSubtask(subtaskKey, answer, SubtaskPhase.REVIEW_APPROVED, SubtaskPhase.REVIEW_REJECTED)
            SubtaskPhase.TESTED -> approveSubtask(subtaskKey, answer, SubtaskPhase.TEST_APPROVED, SubtaskPhase.TEST_REJECTED)
            SubtaskPhase.SUMMARIZED -> approveSubtask(subtaskKey, answer, SubtaskPhase.SUMMARY_APPROVED, SubtaskPhase.SUMMARY_REJECTED)
            SubtaskPhase.AWAITING_HUMAN -> {
                dashboardService.setSubtaskPhase(subtaskKey, SubtaskPhase.MANUAL_ACTION_DONE.trackerValue, answer)
                "Als klaar gemarkeerd"
            }
            // SF-192 — manual-approve-poort: een instemmend woord = approve-commando, andere tekst =
            // reject-commando met die tekst als afkeurreden. Loopt via hetzelfde commando-mechanisme.
            SubtaskPhase.MANUAL_APPROVE_NEEDED -> if (isApproval(answer)) {
                dashboardService.queueCommand(subtaskKey, FactoryCommand.APPROVE)
                "Goedgekeurd"
            } else {
                dashboardService.queueCommand(subtaskKey, FactoryCommand.REJECT, answer)
                "Afgekeurd met feedback"
            }
            else -> null
        }
    }

    private fun answerSubtask(subtaskKey: String, answer: String, answered: SubtaskPhase): String {
        dashboardService.setSubtaskPhase(subtaskKey, answered.trackerValue, answer)
        return "Antwoord doorgestuurd"
    }

    private fun approveStory(storyKey: String, answer: String, approved: StoryPhase, rejected: StoryPhase): String =
        if (isApproval(answer)) {
            dashboardService.setStoryPhase(storyKey, approved.trackerValue, null)
            "Goedgekeurd"
        } else {
            dashboardService.setStoryPhase(storyKey, rejected.trackerValue, answer)
            "Teruggestuurd met feedback"
        }

    private fun approveSubtask(subtaskKey: String, answer: String, approved: SubtaskPhase, rejected: SubtaskPhase): String =
        if (isApproval(answer)) {
            dashboardService.setSubtaskPhase(subtaskKey, approved.trackerValue, null)
            "Goedgekeurd"
        } else {
            dashboardService.setSubtaskPhase(subtaskKey, rejected.trackerValue, answer)
            "Teruggestuurd met feedback"
        }

    /** Een kaal instemmend woord => goedkeuren; al het andere => terugsturen met die tekst als feedback. */
    private fun isApproval(answer: String): Boolean =
        answer.trim().trimEnd('!', '.', ' ').lowercase() in APPROVAL_WORDS

    private companion object {
        private val APPROVAL_WORDS = setOf(
            "approve", "approved", "ok", "oke", "oké", "okay", "akkoord", "goedkeuren", "goedgekeurd",
            "ja", "yes", "prima", "👍", "✅",
        )
    }
}
