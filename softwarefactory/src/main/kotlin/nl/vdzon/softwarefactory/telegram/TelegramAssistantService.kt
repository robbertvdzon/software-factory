package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * De conversationele assistent achter een Telegram-(project)kanaal (Fase B, stap 1). Vrije berichten
 * die geen reply op een melding zijn, komen hier binnen: de assistent praat per kanaal met
 * gespreksgeheugen (claude session per chat) en weet om welk project het gaat.
 *
 * B1 is bewust read-only/zonder tools: de assistent kan meedenken, uitleggen en doorvragen, maar nog
 * geen live data ophalen of acties uitvoeren. Dat staat ook zo in de systeemprompt, zodat hij eerlijk
 * is over wat hij (nog) niet kan.
 */
@Service
class TelegramAssistantService(
    private val claude: ClaudeAssistantClient,
    private val conversations: TelegramConversationStore,
    private val telegramClient: TelegramClient,
    private val projectRepoResolver: ProjectRepoResolver,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    val enabled: Boolean get() = claude.enabled

    /** Verwerkt een vrij bericht uit [chatId]. Geeft zelf het antwoord terug via Telegram. */
    fun handle(chatId: String, rawText: String) {
        val text = stripMention(rawText).trim()
        if (text.isEmpty()) return

        when (text.lowercase()) {
            "/new", "/reset", "/clear" -> {
                conversations.clear(chatId)
                telegramClient.sendMessage("🧹 Nieuw gesprek gestart — ik ben de vorige context vergeten.", chatId = chatId)
                return
            }
            "/help", "/start" -> {
                telegramClient.sendMessage(helpText(chatId), chatId = chatId)
                return
            }
        }

        if (!claude.enabled) {
            telegramClient.sendMessage(
                "⚠️ De assistent staat uit: er is geen Claude-token (SF_AI_OAUTH_TOKEN) geconfigureerd.",
                chatId = chatId,
            )
            return
        }

        telegramClient.sendChatAction(chatId, "typing")
        val sessionId = conversations.sessionId(chatId)
        val reply = claude.ask(systemPrompt(chatId), text, sessionId)
        // Sla de (nieuwe of hervatte) sessie op zodat de volgende vraag de context meeneemt.
        reply.sessionId?.takeIf { !reply.isError }?.let { conversations.setSessionId(chatId, it) }
        telegramClient.sendMessage(reply.text, chatId = chatId)
        logger.info("Assistent beantwoordde een bericht in chat {} (kosten ~${'$'}{}).", chatId, reply.costUsd)
    }

    private fun projectName(chatId: String): String? = projectRepoResolver.projectNameForChatId(chatId)

    private fun systemPrompt(chatId: String): String {
        val project = projectName(chatId)
        val projectLine = if (project != null) {
            val repo = projectRepoResolver.repoFor(project)
            "Je werkt voor project '$project'${repo?.let { " (repo: $it)" } ?: ""}."
        } else {
            "Dit is het algemene factory-kanaal (geen specifiek project)."
        }
        return """
            Je bent de assistent van de Software Factory, bereikbaar via Telegram. $projectLine

            De Software Factory is een systeem dat AI-agents aanstuurt om software-stories te bouwen via
            een vaste keten: refine → plan → develop → review → test → summary → merge. Stories en hun
            fases worden in YouTrack beheerd; per story bepaalt het `Repo`-veld het project.

            Belangrijk over wat je NU kunt: je hebt nog GEEN tools om live data op te halen (YouTrack,
            database) of acties uit te voeren (zoals een story aanmaken). Je kunt meedenken, uitleggen,
            en doorvragen tot iets helder is. Wees eerlijk wanneer iets een live-opzoeking of actie
            vereist die je (nog) niet kunt doen, en zeg dat het in een volgende stap komt.

            Stijl: antwoord in het Nederlands, kort en concreet (dit is een chat). Stel gerichte
            verduidelijkende vragen als informatie ontbreekt.
        """.trimIndent()
    }

    private fun helpText(chatId: String): String {
        val project = projectName(chatId)
        val scope = project?.let { "project '$it'" } ?: "de factory (algemeen kanaal)"
        return """
            🤖 Software Factory-assistent voor $scope.

            Stel gewoon je vraag, bijvoorbeeld "hoe werkt de review-stap?" of "denk mee over een story".
            Ik onthoud de context binnen dit gesprek.

            Commando's:
            /new — vergeet de huidige context en begin een nieuw gesprek
            /help — dit bericht

            Let op: ik kan in deze eerste versie nog geen live data ophalen of acties uitvoeren
            (zoals een story aanmaken). Dat komt in een volgende stap.
        """.trimIndent()
    }

    /** Verwijdert een leidende bot-mention (@bot) die Telegram in groepen aan het bericht plakt. */
    private fun stripMention(text: String): String =
        text.trim().replace(Regex("^@\\S+\\s+"), "")
}
