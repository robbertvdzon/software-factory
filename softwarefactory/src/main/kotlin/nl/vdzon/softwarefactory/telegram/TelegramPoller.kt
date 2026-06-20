package nl.vdzon.softwarefactory.telegram

import jakarta.annotation.PreDestroy
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.Executors

/**
 * Leest inkomende Telegram-updates via long-polling en geeft replies door aan
 * [TelegramReplyService]. Eigen daemon-thread (zoals de orchestrator-poller); start alleen wanneer
 * de integratie is geconfigureerd.
 *
 * Beveiliging: alleen berichten uit de geconfigureerde chat worden verwerkt — een vreemde die de bot
 * vindt kan de factory zo niet aansturen. De offset schuift wél door voor genegeerde berichten, zodat
 * ze niet eindeloos terugkomen.
 */
@Component
class TelegramPoller(
    private val telegramClient: TelegramClient,
    private val replyService: TelegramReplyService,
    private val assistantService: TelegramAssistantService,
    private val store: TelegramStore,
    private val secrets: FactorySecrets,
    private val projectRepoResolver: ProjectRepoResolver,
) {
    /** Toegestane chats: het globale kanaal + alle project-kanalen uit projects.yaml. */
    private val allowedChatIds: Set<String> = buildSet {
        secrets.telegramChatId?.takeIf { it.isNotBlank() }?.let { add(it) }
        addAll(projectRepoResolver.telegramChatIds())
    }

    // Assistent-aanroepen (claude) duren seconden; op een eigen thread zodat de poll-loop door kan
    // lezen. Single-thread => beurten per chat blijven netjes op volgorde.
    private val assistantExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "telegram-assistant").apply { isDaemon = true } }
    private val logger = LoggerFactory.getLogger(javaClass)
    private val worker = Thread(::loop, "telegram-poller").apply { isDaemon = true }
    @Volatile private var running = true

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!telegramClient.enabled) {
            logger.info("Telegram-integratie staat uit (SF_TELEGRAM_BOT_TOKEN/SF_TELEGRAM_CHAT_ID niet gezet).")
            return
        }
        logger.info("Telegram-integratie actief — poller gestart.")
        worker.start()
    }

    @PreDestroy
    fun stop() {
        running = false
        worker.interrupt()
        assistantExecutor.shutdownNow()
    }

    private fun loop() {
        while (running) {
            try {
                val offset = store.getUpdatesOffset()
                val updates = telegramClient.getUpdates(offset, timeoutSeconds = 25)
                for (update in updates.sortedBy { it.updateId }) {
                    if (!running) break
                    process(update)
                    // Offset altijd doorschuiven, ook bij negeren/fout, zodat dezelfde update niet blijft terugkomen.
                    store.setUpdatesOffset(update.updateId + 1)
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (exception: Exception) {
                logger.warn("Telegram-poll faalde; korte pauze en opnieuw.", exception)
                sleepQuietly(5_000)
            }
        }
    }

    private fun process(update: TelegramUpdate) {
        if (allowedChatIds.isNotEmpty() && update.chatId !in allowedChatIds) {
            logger.warn("Telegram-bericht van onbekende chat {} genegeerd.", update.chatId)
            return
        }
        // Een reply op een openstaande melding (antwoord/approve/merge) gaat naar de reply-handler.
        // Lukt dat niet (geen/onbekende reply), dan is het een vrij bericht → de assistent.
        val handledAsReply = runCatching { replyService.handleReply(update) }
            .getOrElse {
                logger.warn("Reply-verwerking van Telegram-update {} faalde.", update.updateId, it)
                false
            }
        if (handledAsReply) return

        val chatId = update.chatId ?: return
        val text = update.text?.takeIf { it.isNotBlank() } ?: return
        assistantExecutor.submit {
            runCatching { assistantService.handle(chatId, text) }
                .onFailure { logger.warn("Assistent-verwerking van update {} faalde.", update.updateId, it) }
        }
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
