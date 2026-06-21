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
        val reply = claude.ask(chatId, systemPrompt(chatId), text, sessionId)
        // Sla de (nieuwe of hervatte) sessie op zodat de volgende vraag de context meeneemt.
        reply.sessionId?.takeIf { !reply.isError }?.let { conversations.setSessionId(chatId, it) }
        telegramClient.sendMessage(reply.text, chatId = chatId)
        logger.info("Assistent beantwoordde een bericht in chat {} (kosten ~${'$'}{}).", chatId, reply.costUsd)
    }

    private fun projectName(chatId: String): String? = projectRepoResolver.projectNameForChatId(chatId)

    private fun systemPrompt(chatId: String): String {
        val project = projectName(chatId)
        val repo = project?.let { projectRepoResolver.repoFor(it) }
        val projectLine = if (project != null) {
            "Dit kanaal hoort bij project '$project'${repo?.let { " (repo: $it)" } ?: ""}."
        } else {
            "Dit is het algemene factory-kanaal (geen specifiek project)."
        }
        return """
            Je bent de assistent van de Software Factory, bereikbaar via Telegram. $projectLine

            De Software Factory stuurt AI-agents aan om software-stories te bouwen via een vaste keten:
            refine → plan → develop → review → test → summary → merge. Stories en hun fases staan in
            YouTrack; per story bepaalt het `Repo`-veld het project. Een lege fase of leeg `Repo`-veld
            betekent dat een story NIET wordt opgepakt.

            Je hebt een shell-tool `sf-youtrack`:
            - `sf-youtrack status <STORYKEY>` — fase/repo/fout van een story of subtaak + waarom 'ie
              (nog) niet wordt opgepakt.
            - `sf-youtrack projects` — lijst van YouTrack-projecten (key + naam).
            - `sf-youtrack create [--project <YT_KEY>] --title <...> [--description <...>] [--repo <naam>] [--start]`
              — maakt een story aan. Zonder --project komt 'ie in het Software Factory-project.
            - `sf-youtrack update <STORYKEY> [--summary ...] [--description ...] [--phase ...] [--comment ...]`
              — past een story/subtaak aan (titel/omschrijving/fase/commentaar).
            - `sf-youtrack delete <STORYKEY>` — verwijdert een story volledig (incl. subtaken). Onomkeerbaar.

            REGELS:
            - Opzoeken (`status`, `projects`) doe je vrij.
            - Nieuwe story's komen STANDAARD in het Software Factory-project. Wil de gebruiker een ander
              project (bv. de newsfeed), gebruik dan `sf-youtrack projects` om de juiste --project key te
              vinden en geef die mee.
            - Aanmaken/aanpassen: verzamel eerst de nodige info, vat kort voor wat je gaat doen, en voer
              het dan uit. Wees concreet.
            - VERWIJDEREN is onomkeerbaar: doe `delete` alleen na een expliciete bevestiging ("ja, verwijder").
            - Verzin geen story-keys; controleer met `status` als de gebruiker er een noemt.

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
            • story-status opzoeken ("hoe staat NF-101 ervoor?", "waarom wordt die niet opgepakt?")
            • meedenken en een nieuwe story aanmaken (ik vraag eerst om details en jouw bevestiging)

            Ik onthoud de context binnen dit gesprek.

            Commando's:
            /new — vergeet de huidige context en begin een nieuw gesprek
            /help — dit bericht
        """.trimIndent()
    }

    /** Verwijdert een leidende bot-mention (@bot) die Telegram in groepen aan het bericht plakt. */
    private fun stripMention(text: String): String =
        text.trim().replace(Regex("^@\\S+\\s+"), "")
}
