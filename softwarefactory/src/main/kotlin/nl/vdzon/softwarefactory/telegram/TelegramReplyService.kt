package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.core.ChangeNotifier
import nl.vdzon.softwarefactory.core.FactoryStateChangedEvent
import nl.vdzon.softwarefactory.core.StoryPhase
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.web.services.FactoryDashboardService
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

/**
 * Verwerkt een Telegram-reply op een eerder verstuurde vraag. Het antwoord loopt via exact dezelfde
 * route als het dashboard ([FactoryDashboardService.setStoryPhase] / [setSubtaskPhase]): de tekst
 * wordt als comment gepost en de fase schuift naar de bijbehorende `*-questions-answered`-stap, zodat
 * de agent verder kan. Daarna wordt de orchestrator-poller direct gewekt.
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
     * Probeert [update] als antwoord op een openstaande vraag te verwerken. Geen reply, onbekend
     * bericht of lege tekst => stilletjes negeren (return false). Bij succes return true.
     */
    fun handleReply(update: TelegramUpdate): Boolean {
        val replyTo = update.replyToMessageId ?: return false
        val pending = store.findPending(replyTo) ?: return false
        val answer = update.text?.takeIf { it.isNotBlank() } ?: return false

        val applied = when (pending.issueLevel) {
            "STORY" -> {
                val target = storyAnswerPhase(pending.sourcePhase) ?: return false
                dashboardService.setStoryPhase(pending.issueKey, target.trackerValue, answer)
                true
            }
            "SUBTASK" -> {
                val target = subtaskAnswerPhase(pending.sourcePhase) ?: return false
                dashboardService.setSubtaskPhase(pending.issueKey, target.trackerValue, answer)
                true
            }
            else -> false
        }
        if (!applied) return false

        store.deletePending(replyTo)
        // Wek de orchestrator direct (zoals AgentRunCompletionService) en ververs open dashboards.
        runCatching { eventPublisher.publishEvent(FactoryStateChangedEvent("telegram-reply:${pending.issueKey}")) }
            .onFailure { logger.debug("Kon FactoryStateChangedEvent niet publiceren (genegeerd).", it) }
        runCatching { changeNotifier.notifyChanged() }
            .onFailure { logger.debug("ChangeNotifier faalde (genegeerd).", it) }
        runCatching { telegramClient.sendMessage("✅ Antwoord doorgestuurd naar ${pending.issueKey}.", replyToMessageId = update.replyToMessageId) }
        logger.info("Telegram-antwoord op {} verwerkt.", pending.issueKey)
        return true
    }

    private fun storyAnswerPhase(sourcePhase: String): StoryPhase? = when (StoryPhase.fromTracker(sourcePhase)) {
        StoryPhase.REFINED_WITH_QUESTIONS -> StoryPhase.QUESTIONS_ANSWERED
        StoryPhase.PLANNED_WITH_QUESTIONS -> StoryPhase.PLANNING_QUESTIONS_ANSWERED
        else -> null
    }

    private fun subtaskAnswerPhase(sourcePhase: String): SubtaskPhase? = when (SubtaskPhase.fromTracker(sourcePhase)) {
        SubtaskPhase.DEVELOPED_WITH_QUESTIONS -> SubtaskPhase.DEVELOPMENT_QUESTIONS_ANSWERED
        SubtaskPhase.REVIEWED_WITH_QUESTIONS -> SubtaskPhase.REVIEW_QUESTIONS_ANSWERED
        SubtaskPhase.TESTED_WITH_QUESTIONS -> SubtaskPhase.TEST_QUESTIONS_ANSWERED
        SubtaskPhase.SUMMARY_WITH_QUESTIONS -> SubtaskPhase.SUMMARY_QUESTIONS_ANSWERED
        // Een handmatige subtaak: het antwoord betekent "gedaan".
        SubtaskPhase.AWAITING_HUMAN -> SubtaskPhase.MANUAL_ACTION_DONE
        else -> null
    }
}
