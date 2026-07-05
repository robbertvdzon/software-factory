package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.knowledge.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Status van de assistent voor het Agents-scherm (§5 bridge-operatie `assistant.status`). */
data class AssistantStatus(
    val enabled: Boolean,
    val busy: Boolean,
    val activeChatCount: Int,
    val lastActivityAt: OffsetDateTime?,
)

/**
 * De conversationele assistent achter een Telegram-(project)kanaal. Elke reply-keten is een **thread**
 * met z'n eigen claude-sessie (Fase 5): een niet-reply-bericht start een nieuw gesprek, het antwoord
 * komt als reply, en een reply daarop zet die thread voort. Zo kun je meerdere gesprekken tegelijk en
 * los van elkaar voeren in één groep.
 *
 * De assistent draait geïsoleerd in Docker met code + secrets van het project (zie
 * [AssistantWorkspaceService]) en tools (sf-youtrack, sf-browser).
 */
@Service
class TelegramAssistantService(
    private val claude: ClaudeAssistantClient,
    private val threadStore: TelegramThreadStore,
    private val telegramClient: TelegramClient,
    private val projectRepoResolver: ProjectRepoResolver,
    private val workspaceService: AssistantWorkspaceService,
    private val knowledgeApi: KnowledgeApi,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // Eén lock per sessie: parallel over threads, maar serieel binnen een thread (geen dubbele --resume).
    private val sessionLocks = ConcurrentHashMap<String, Any>()

    // Voor het Agents-scherm (§5 `assistant.status`): welke sessies nu een claude.ask() draaien +
    // wanneer de assistent voor het laatst een bericht kreeg (ook commando's zonder claude-call).
    private val activeSessions = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastActivityAt: OffsetDateTime? = null

    val enabled: Boolean get() = claude.enabled

    fun status(): AssistantStatus = AssistantStatus(
        enabled = enabled,
        busy = activeSessions.isNotEmpty(),
        activeChatCount = activeSessions.size,
        lastActivityAt = lastActivityAt,
    )

    /**
     * Verwerkt een vrij bericht (evt. met foto) uit [chatId]. [messageId] = het bericht zelf,
     * [replyToMessageId] = waarop het een reply is (bepaalt de thread).
     */
    fun handle(chatId: String, rawText: String, photoFileId: String?, messageId: Long?, replyToMessageId: Long?) {
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

        if (!claude.enabled) {
            telegramClient.sendMessage(
                "⚠️ De assistent staat uit: er is geen Claude-token (SF_AI_OAUTH_TOKEN) geconfigureerd.",
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
        // Lagen (factory + project) klaarzetten; alleen bij een nieuwe thread bijwerken naar remote.
        val layout = runCatching { workspaceService.prepare(chatId, refresh = !isResume) }
            .getOrElse {
                logger.warn("Workspace voorbereiden faalde (zonder lagen verder).", it)
                AssistantWorkspaceService.Layout(emptyList(), emptyList())
            }
        // Binnenkomende foto -> /work/in, als pad meegeven (claude leest 'm met Read = vision).
        val effectiveText = buildString {
            append(effectiveTextAfterPrefix)
            if (photoFileId != null) {
                val dest = claude.inputDir(chatId, sessionId).resolve("input-${UUID.randomUUID().toString().take(8)}.jpg")
                if (telegramClient.downloadFile(photoFileId, dest)) {
                    if (isNotEmpty()) append("\n\n")
                    append("[De gebruiker stuurde een afbeelding: /work/in/${dest.fileName} — bekijk die met je Read-tool.]")
                } else {
                    logger.warn("Kon de Telegram-foto niet downloaden voor chat {}.", chatId)
                }
            }
        }
        val reply = claude.ask(chatId, sessionId, isResume, systemPrompt(chatId, layout), effectiveText, layout.mounts)
        if (reply.stopped) {
            logger.info("Assistent-thread {} door gebruiker gestopt; geen antwoord gestuurd.", sessionId.take(8))
            return
        }
        // Tips die de assistent teruggaf opslaan — net als bij de werk-agents doet de factory dit, niet de agent.
        persistTips(chatId, reply.tips)
        val actualSid = reply.sessionId ?: sessionId
        // Antwoord als reply op het bericht van de gebruiker → houdt de thread visueel bij elkaar.
        val answerMessageId = telegramClient.sendMessage(reply.text, replyToMessageId = messageId, chatId = chatId)
        // Koppel beide berichten aan de thread-sessie (zodat een reply hierop de thread voortzet).
        if (!reply.isError) {
            messageId?.let { threadStore.map(chatId, it, actualSid) }
            answerMessageId?.let { threadStore.map(chatId, it, actualSid) }
            // Actieve root bijhouden zodat een volgend bericht (zonder reply/prefix) deze thread hervat.
            threadStore.setActiveRootSession(chatId, actualSid)
        }
        claude.outputImages(chatId, actualSid).forEach { img ->
            if (telegramClient.sendPhoto(chatId, img)) runCatching { Files.deleteIfExists(img) }
        }
        logger.info("Assistent beantwoordde een bericht in chat {} (thread {}, kosten ~${'$'}{}).", chatId, actualSid.take(8), reply.costUsd)
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
        val stopped = claude.stop(sessionId)
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

    private fun systemPrompt(chatId: String, layout: AssistantWorkspaceService.Layout): String {
        val project = projectName(chatId)
        val repo = project?.let { projectRepoResolver.repoFor(it) }
        val projectLine = if (project != null) {
            "Dit kanaal hoort bij project '$project'${repo?.let { " (repo: $it)" } ?: ""}."
        } else {
            "Dit is het algemene factory-kanaal (geen specifiek project)."
        }
        val layersBlock = if (layout.layers.isEmpty()) {
            ""
        } else {
            val lines = layout.layers.joinToString("\n") { l ->
                val parts = listOfNotNull(
                    l.repoPath?.let { "code in $it" },
                    l.privatePath?.let { "secrets/config in $it" },
                )
                "            - ${l.name}${if (l.isBase) " (factory-basislaag)" else ""}: ${parts.joinToString(", ")}"
            }
            """

            In de container staan de relevante mappen klaar (read-only):
$lines
            Lees `runbook.md` in een repo voor hoe dat project draait/getest wordt. De `private`-map bevat
            secrets/config — laad die alleen in (bv. `source <bestand>`) wanneer je ze echt nodig hebt.
            """.trimIndent()
        }
        val tipsBlock = loadedTips(chatId)
        return """
            Je bent de assistent van de Software Factory, bereikbaar via Telegram. $projectLine
            $layersBlock$tipsBlock

            De Software Factory stuurt AI-agents aan om software-stories te bouwen via een vaste keten:
            refine → plan → develop → review → test → summary → merge. Stories en hun fases staan in
            YouTrack; per story bepaalt het `Repo`-veld het project. Een lege fase of leeg `Repo`-veld
            betekent dat een story NIET wordt opgepakt.

            Je hebt een shell-tool `sf-youtrack`:
            - `sf-youtrack status <STORYKEY>` — fase/repo/fout van een story of subtaak + waarom 'ie
              (nog) niet wordt opgepakt.
            - `sf-youtrack projects` — lijst van YouTrack-projecten (key + naam).
            - `sf-youtrack create [--project <YT_KEY>] --title <...> [--description <...>] [--repo <naam>] [--ai-supplier <claude|..>] [--ai-model <..>] [--start]`
              — maakt een story aan. Zonder --project komt 'ie in het Software Factory-project. Zet
              `--ai-supplier`/`--ai-model` als de gebruiker die noemt.
            - `sf-youtrack update <STORYKEY> [--summary ...] [--description ...] [--phase ...] [--comment ...] [--ai-supplier ..] [--ai-model ..]`
              — past een story/subtaak aan.
            - `sf-youtrack delete <STORYKEY>` — verwijdert een story volledig (incl. subtaken). Onomkeerbaar.

            Tips opslaan voor de volgende keer: heb je tijdens deze taak iets moeten UITZOEKEN dat later tijd
            bespaart — hoe je inlogt (login-URL, waar de testaccounts staan), dat een Flutter-pagina pas ná
            een wait te screenshotten is, een werkend script/commando, of hoe een cluster-onderdeel in elkaar
            zit — voeg dan AAN HET EIND van je antwoord één los JSON-object toe met die lessen:
            {"agent_tips_update":[{"category":"...","key":"...","content":"..."}]}
            Gebruik {"agent_tips_update":[]} als je niets nieuws hebt geleerd. Sla concrete, herbruikbare
            kennis op — geen losse feiten die alleen voor deze ene vraag gelden. De factory haalt dit
            JSON-blok automatisch uit je antwoord en bewaart het; de gebruiker ziet het niet. Reeds geleerde
            tips staan hierboven onder "Geleerde inzichten".

            REGELS:
            - Opzoeken (`status`, `projects`) doe je vrij.
            - Story aanmaken voor dit kanaal: geef `--repo <de projectnaam van dit kanaal>` mee zodat de
              factory tegen de juiste repo werkt (zonder repo wordt de story niet opgepakt). De story komt
              in het Software Factory YouTrack-project (`--project SF`) tenzij de gebruiker een ander
              YouTrack-project noemt. `ai-supplier=claude` en `auto-approve=on` zijn al de defaults van
              `create` — die hoef je niet expliciet mee te geven.
            - START NIET automatisch: maak de story aan ZONDER `--start`. Vraag daarna of de gebruiker 'm
              wil starten; zegt die ja, dan `sf-youtrack update <KEY> --phase start` (zo gaat 'ie lopen).
            - Aanmaken/aanpassen: verzamel eerst de nodige info en vat kort voor wat je gaat doen.
            - VERWIJDEREN is onomkeerbaar: doe `delete` alleen na een expliciete bevestiging ("ja, verwijder").
            - Verzin geen story-keys; controleer met `status` als de gebruiker er een noemt.

            Afbeeldingen in/uit: een foto die de gebruiker stuurt staat in `/work/in/` (lees 'm met je
            Read-tool). Wil je de gebruiker zelf een afbeelding/screenshot tonen, schrijf 'm dan naar
            `/work/out/`; de factory stuurt alles uit die map daarna door.

            Browser/web (Playwright is geïnstalleerd):
            - Snel screenshot van een URL:
              `playwright-chromium --headless --no-sandbox --screenshot=/work/out/shot.png <url>`
            - Inloggen/klikken/testen: schrijf een Playwright-Node-script en draai het met `sf-browser <script.js>`.
              Gebruik `chromium.launchPersistentContext("/work/.browser", { headless: true, args: ["--no-sandbox"] })`
              — het profiel in `/work/.browser` blijft tussen beurten bewaard, dus een login overleeft. Sla
              screenshots op in `/work/out/`. Hoe je inlogt (URL, waar de testaccounts staan) lees je in de
              `runbook.md` van het project.

            Cluster (OpenShift): `oc` en `kubectl` zijn ingelogd op het cluster — de kubeconfig staat
            klaar en `KUBECONFIG` is gezet, dus je kunt direct zelf checks doen i.p.v. de gebruiker om
            output te vragen. Bijvoorbeeld `oc get pods -n <namespace>`, `oc describe pod <pod> -n <ns>`
            of `oc logs <pod> --previous -n <ns>`. Noem een namespace (`-n`) als die bekend is. Doe vrij
            read-only opzoekwerk, maar voer GEEN wijzigende cluster-commando's uit (delete/apply/scale/…)
            zonder expliciete bevestiging van de gebruiker.

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
            • meedenken en een story aanmaken/aanpassen (ik vat eerst voor en vraag bevestiging)
            • een afbeelding bekijken die je stuurt, of zelf een screenshot van een pagina sturen

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
