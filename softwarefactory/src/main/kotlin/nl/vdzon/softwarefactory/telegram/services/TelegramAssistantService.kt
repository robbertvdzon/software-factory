package nl.vdzon.softwarefactory.telegram.services

import nl.vdzon.softwarefactory.telegram.clients.*
import nl.vdzon.softwarefactory.telegram.repositories.*
import nl.vdzon.softwarefactory.telegram.models.*
import nl.vdzon.softwarefactory.telegram.InteractiveAssistantClient
import nl.vdzon.softwarefactory.telegram.models.AssistantInputFile
import nl.vdzon.softwarefactory.core.contracts.AssistantStatus
import nl.vdzon.softwarefactory.core.contracts.TelegramAssistantApi

import nl.vdzon.softwarefactory.config.ProjectAssistantSettings
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.knowledge.models.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * De conversationele assistent achter een Telegram-(project)kanaal. Elke reply-keten is een **thread**:
 * een niet-reply-bericht start een nieuw gesprek, het antwoord
 * komt als reply, en een reply daarop zet die thread voort. Zo kun je meerdere gesprekken tegelijk en
 * los van elkaar voeren in één groep.
 *
 * Iedere assistentbeurt draait als Agent Runtime-job. De factory geeft alleen gesprekstekst en een
 * eventuele foto door; er is geen lokale checkout, container, toolmount of projectsecret.
 */
@Service
class TelegramAssistantService(
    private val assistant: InteractiveAssistantClient,
    private val threadStore: TelegramThreadStore,
    private val telegramClient: TelegramClient,
    private val projectRepoResolver: ProjectAssistantSettings,
    private val knowledgeApi: KnowledgeApi,
) : TelegramAssistantApi {
    private val logger = LoggerFactory.getLogger(javaClass)

    // Eén lock per sessie: parallel over threads, maar serieel binnen een thread.
    private val sessionLocks = ConcurrentHashMap<String, Any>()

    // Voor het Agents-scherm (§5 `assistant.status`): welke sessies nu een Runtime-job draaien +
    // wanneer de assistent voor het laatst een bericht kreeg (ook commando's zonder Runtime-job).
    private val activeSessions = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastActivityAt: OffsetDateTime? = null

    override val enabled: Boolean get() = assistant.enabled

    override fun status(): AssistantStatus = AssistantStatus(
        enabled = enabled,
        busy = activeSessions.isNotEmpty(),
        activeChatCount = activeSessions.size,
        lastActivityAt = lastActivityAt,
    )

    /**
     * Verwerkt een vrij bericht (evt. met foto) uit [chatId]. [messageId] = het bericht zelf,
     * [replyToMessageId] = waarop het een reply is (bepaalt de thread).
     */
    override fun handle(chatId: String, rawText: String, photoFileId: String?, messageId: Long?, replyToMessageId: Long?) {
        val text = stripMention(rawText).trim()
        if (text.isEmpty() && photoFileId == null) return
        lastActivityAt = OffsetDateTime.now()

        if (photoFileId == null) {
            when (text.lowercase()) {
                "/new", "/reset", "/clear" -> {
                    telegramClient.sendMessage(
                        "💡 Tip: stuur gewoon een nieuw bericht (geen reply) om verder te gaan in de actieve thread, " +
                            "of gebruik een prefix (bijv. \"nieuw:\", \"new:\") om een nieuw gesprek te starten. " +
                            "Reply op een antwoord om een specifieke thread voort te zetten.",
                        chatId = chatId,
                    )
                    return
                }
                "/help", "/start" -> {
                    telegramClient.sendMessage(helpText(chatId), chatId = chatId)
                    return
                }
                "/stop" -> {
                    handleStop(chatId, replyToMessageId)
                    return
                }
            }
        }

        if (!assistant.enabled) {
            telegramClient.sendMessage(
                "⚠️ De assistent staat uit: Agent Runtime is niet geconfigureerd.",
                chatId = chatId,
            )
            return
        }

        // Prefix-detectie: strip de prefix en start een nieuwe thread.
        val detectedPrefixText = detectPrefix(text)
        val forceNew = detectedPrefixText != null
        val effectiveTextAfterPrefix = if (forceNew) detectedPrefixText!! else text

        // Lege tekst na prefix-stripping zonder foto negeren (zie issue comment 7-1280).
        if (effectiveTextAfterPrefix.isEmpty() && photoFileId == null) return

        // Thread bepalen: reply → bestaande sessie; prefix → nieuwe UUID; geen prefix → actieve root of nieuwe UUID.
        val (sessionId, isResume) = determineSession(chatId, replyToMessageId, forceNew)

        // Koppel het binnenkomende bericht meteen aan de sessie, zodat een /stop-reply hierop de lopende
        // thread terugvindt terwijl de beurt nog draait (na afloop her-mapt het met de definitieve id).
        messageId?.let { threadStore.map(chatId, it, sessionId) }

        synchronized(sessionLocks.computeIfAbsent(sessionId) { Any() }) {
            activeSessions.add(sessionId)
            try {
                handleLocked(chatId, sessionId, isResume, effectiveTextAfterPrefix, photoFileId, messageId)
            } finally {
                activeSessions.remove(sessionId)
                lastActivityAt = OffsetDateTime.now()
            }
        }
    }

    private fun handleLocked(
        chatId: String,
        sessionId: String,
        isResume: Boolean,
        effectiveTextAfterPrefix: String,
        photoFileId: String?,
        messageId: Long?,
    ) {
        telegramClient.sendChatAction(chatId, "typing")
        val inputFile = photoFileId?.let { downloadInputImage(chatId, it) }
        val reply = assistant.ask(
            chatId = chatId,
            projectKey = projectName(chatId),
            sessionId = sessionId,
            isResume = isResume,
            systemPrompt = systemPrompt(chatId),
            userMessage = effectiveTextAfterPrefix,
            inputFile = inputFile,
        )
        if (reply.stopped) {
            logger.info("Assistent-thread {} door gebruiker gestopt; geen antwoord gestuurd.", sessionId.take(8))
            return
        }
        persistTips(chatId, reply.tips)
        val actualSid = reply.sessionId ?: sessionId
        val answerMessageId = telegramClient.sendMessage(reply.text, replyToMessageId = messageId, chatId = chatId)
        if (!reply.isError) {
            messageId?.let { threadStore.map(chatId, it, actualSid) }
            answerMessageId?.let { threadStore.map(chatId, it, actualSid) }
            threadStore.setActiveRootSession(chatId, actualSid)
        }
        logger.info(
            "Assistent beantwoordde een bericht in chat {} (thread {}, kosten ~${'$'}{}).",
            chatId,
            actualSid.take(8),
            reply.costUsd,
        )
    }

    private fun downloadInputImage(chatId: String, fileId: String): AssistantInputFile? {
        val temp = Files.createTempFile("sf-telegram-input-", ".jpg")
        return try {
            if (!telegramClient.downloadFile(fileId, temp)) {
                logger.warn("Kon de Telegram-foto niet downloaden voor chat {}.", chatId)
                null
            } else {
                AssistantInputFile(temp.fileName.toString(), "image/jpeg", Files.readAllBytes(temp))
            }
        } finally {
            runCatching { Files.deleteIfExists(temp) }
        }
    }

    /** Breekt het gesprek af waar je /stop als reply op een bericht uit die thread stuurt. */
    private fun handleStop(chatId: String, replyToMessageId: Long?) {
        val sessionId = replyToMessageId?.let { threadStore.sessionFor(chatId, it) }
        if (sessionId == null) {
            telegramClient.sendMessage(
                "ℹ️ Reply met /stop op een bericht uit het gesprek dat je wilt afbreken.",
                chatId = chatId,
            )
            return
        }
        val stopped = assistant.stop(sessionId)
        telegramClient.sendMessage(
            if (stopped) "🛑 Gesprek afgebroken." else "ℹ️ In dit gesprek loopt op dit moment niets.",
            chatId = chatId,
        )
    }

    /** Slaat de tips uit het antwoord op onder rol 'assistant' — zelfde KnowledgeApi als de werk-agents. */
    private fun persistTips(chatId: String, tips: List<AssistantTip>) {
        if (tips.isEmpty()) return
        val targetRepo = projectName(chatId) ?: "factory"
        var saved = 0
        tips.forEach { tip ->
            runCatching {
                knowledgeApi.upsert(
                    AgentKnowledgeUpdateRequest(
                        targetRepo = targetRepo,
                        role = AgentRole.ASSISTANT.markerKeyPart,
                        category = tip.category,
                        key = tip.key,
                        content = tip.content,
                    ),
                )
            }.onFailure { logger.warn("Assistent-tip opslaan faalde (key={}).", tip.key, it) }
                .onSuccess { saved++ }
        }
        logger.info("Assistent sloeg {}/{} tip(s) op voor {}.", saved, tips.size, targetRepo)
    }

    private fun projectName(chatId: String): String? = projectRepoResolver.projectNameForChatId(chatId)

    private fun loadedTips(chatId: String): String {
        val targetRepo = projectName(chatId) ?: "factory"
        val tips = runCatching { knowledgeApi.find(targetRepo, "assistant") }.getOrElse {
            logger.warn("Tips ophalen voor assistent mislukt (targetRepo={}).", targetRepo, it)
            emptyList()
        }
        if (tips.isEmpty()) return ""
        val lines = tips.joinToString("\n") { "- [${it.category}/${it.key}] ${it.content}" }
        return "\n\n## Geleerde inzichten\n$lines"
    }

    private fun systemPrompt(chatId: String): String {
        val project = projectName(chatId)
        val projectLine = if (project != null) {
            "Dit kanaal hoort bij project '$project'."
        } else {
            "Dit is het algemene factory-kanaal (geen specifiek project)."
        }
        val tipsBlock = loadedTips(chatId)
        return """
            Je bent de assistent van de Software Factory, bereikbaar via Telegram. $projectLine
            $tipsBlock

            De Software Factory stuurt AI-agents aan om software-stories te bouwen via een vaste keten:
            refine → plan → develop → review → test → summary → merge. Stories en hun fases staan in de
            eigen tracker-database van de factory (geen externe issue-tracker); per story bepaalt het
            `Repo`-veld het project. Een lege fase of leeg `Repo`-veld betekent dat een story NIET wordt
            opgepakt.

            REGELS:
            - Je hebt geen directe toegang tot de tracker, repositories, projectsecrets, browser of het cluster.
            - Verzin daarom geen actuele status en claim nooit dat je een actie hebt uitgevoerd.
            - Als de gebruiker een story wil aanmaken of wijzigen, geef dan een concreet voorstel en benoem
              expliciet welke bevestiging of Software Factory-actie nog nodig is.
            - Geef herbruikbare nieuwe inzichten afzonderlijk terug in het `tips`-veld van je resultaat.

            Stijl: antwoord in het Nederlands, kort en concreet (dit is een chat). Stel gerichte
            verduidelijkende vragen als informatie ontbreekt.
        """.trimIndent()
    }

    private fun helpText(chatId: String): String {
        val project = projectName(chatId)
        val scope = project?.let { "project '$it'" } ?: "de factory (algemeen kanaal)"
        return """
            🤖 Software Factory-assistent voor $scope.

            Stel gewoon je vraag. Ik kan o.a.:
            • meedenken over een story, ontwerpkeuze of probleem
            • een concreet voorstel voor een Software Factory-actie maken
            • een afbeelding bekijken die je meestuurt

            Ik heb geen directe toegang tot actuele stories, repositories of het cluster en voer
            zelf geen acties uit. Een voorgestelde wijziging moet dus nog via de Software Factory
            worden bevestigd en uitgevoerd.

            Gesprekken:
            • Nieuw bericht (geen reply) → vervolg in de *laatste actieve thread*.
            • Reply op een antwoord → zet die specifieke thread voort.
            • Begin je bericht met een prefix → nieuw, los gesprek (prefix wordt gestript):
              nieuw: | nieuwe vraag: | new: | new question: | iets anders: | story:

            /stop — een lopend gesprek afbreken (reply met /stop op een bericht uit dat gesprek)
            /help — dit bericht
        """.trimIndent()
    }

    /**
     * Detecteert een nieuw-gesprek-prefix op de eerste regel (case-insensitief). Geeft de tekst
     * terug met de prefix gestript, of null als er geen herkende prefix is.
     */
    private fun detectPrefix(text: String): String? {
        val firstLine = text.substringBefore('\n').trim()
        val prefixes = listOf("nieuwe vraag:", "new question:", "iets anders:", "nieuw:", "new:", "story:")
        for (prefix in prefixes) {
            if (firstLine.lowercase().startsWith(prefix)) {
                val afterPrefix = firstLine.substring(prefix.length).trim()
                val rest = if ('\n' in text) text.substringAfter('\n') else ""
                return when {
                    rest.isEmpty() -> afterPrefix
                    afterPrefix.isEmpty() -> rest
                    else -> "$afterPrefix\n$rest"
                }
            }
        }
        return null
    }

    /**
     * Bepaalt de sessie-id en of het een hervatting is.
     *  - reply → bestaande sessie uit de reply-keten (ongewijzigd gedrag)
     *  - forceNew (prefix) → nieuwe UUID
     *  - geen reply, geen prefix → actieve root-sessie of nieuwe UUID
     */
    private fun determineSession(chatId: String, replyToMessageId: Long?, forceNew: Boolean): Pair<String, Boolean> {
        if (!forceNew && replyToMessageId != null) {
            val existing = threadStore.sessionFor(chatId, replyToMessageId)
            if (existing != null) return existing to true
        }
        if (forceNew) return UUID.randomUUID().toString() to false
        val activeRoot = threadStore.activeRootSession(chatId)
        return (activeRoot ?: UUID.randomUUID().toString()) to (activeRoot != null)
    }

    /** Verwijdert een leidende bot-mention (@bot) die Telegram in groepen aan het bericht plakt. */
    private fun stripMention(text: String): String =
        text.trim().replace(Regex("^@\\S+\\s+"), "")
}
