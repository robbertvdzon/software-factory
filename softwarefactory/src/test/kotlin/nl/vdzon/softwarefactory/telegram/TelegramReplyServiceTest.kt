package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.core.ChangeNotifier
import nl.vdzon.softwarefactory.core.FactoryCommand
import nl.vdzon.softwarefactory.core.FactoryOperations
import nl.vdzon.softwarefactory.core.FactoryStateChangedEvent
import nl.vdzon.softwarefactory.core.MergeReadyInfo
import nl.vdzon.softwarefactory.core.TrackerIssue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

/**
 * Flowtests voor [TelegramReplyService]: een reply op een eerder verstuurde melding (via
 * [TelegramStore.savePending] gekoppeld aan een issue) moet de juiste YouTrack-actie uitvoeren —
 * vraag beantwoorden, goed-/afkeuren, handmatige actie afvinken of de merge queue'en. Alles tegen
 * handgeschreven fakes; de asserts kijken naar wat er richting factory/Telegram gaat.
 */
class TelegramReplyServiceTest {

    // ── vraag-antwoord ──────────────────────────────────────────────────────────

    @Test
    fun `reply op een story-vraag zet questions-answered met de tekst als antwoord`() {
        val fixture = fixture(pending("SF-1", "STORY", "refined-with-questions"))

        val handled = fixture.service.handleReply(reply("Gebruik optie B, met migratie."))

        assertTrue(handled)
        assertEquals(
            listOf(Triple("SF-1", "questions-answered", "Gebruik optie B, met migratie.")),
            fixture.operations.storyPhases,
        )
        assertTrue(fixture.store.pending.isEmpty(), "pending moet na verwerking weg zijn")
        assertTrue("SF-1" in fixture.store.clearedNotifications, "melding-registratie moet gewist zijn voor een volgende vragenronde")
        val sent = fixture.client.sent.single()
        assertTrue(sent.text.contains("✅ Antwoord doorgestuurd voor SF-1"), sent.text)
        assertEquals(100L, sent.replyToMessageId, "bevestiging hoort als reply op de oorspronkelijke melding")
        assertTrue(fixture.events.any { it == FactoryStateChangedEvent("telegram-reply:SF-1") }, "orchestrator moet gewekt worden")
    }

    @Test
    fun `reply op een subtaak-vraag zet de bijbehorende questions-answered-fase`() {
        val fixture = fixture(pending("SF-12", "SUBTASK", "developed-with-questions"))

        val handled = fixture.service.handleReply(reply("De API-key staat in het secret."))

        assertTrue(handled)
        assertEquals(
            listOf(Triple("SF-12", "development-questions-answered", "De API-key staat in het secret.")),
            fixture.operations.subtaskPhases,
        )
        assertTrue(fixture.client.sent.single().text.contains("Antwoord doorgestuurd"))
    }

    // ── goedkeuren / afkeuren ───────────────────────────────────────────────────

    @Test
    fun `approve-reply op een refined story keurt goed zonder feedback-comment`() {
        val fixture = fixture(pending("SF-1", "STORY", "refined"))

        assertTrue(fixture.service.handleReply(reply("approve")))

        assertEquals(listOf(Triple("SF-1", "refined-approved", null as String?)), fixture.operations.storyPhases)
        assertTrue(fixture.client.sent.single().text.contains("Goedgekeurd"))
    }

    @Test
    fun `instemmend woord met leestekens zoals Oke! geldt ook als goedkeuring`() {
        val fixture = fixture(pending("SF-1", "STORY", "planned"))

        assertTrue(fixture.service.handleReply(reply("Oké!")))

        assertEquals(listOf(Triple("SF-1", "planning-approved", null as String?)), fixture.operations.storyPhases)
    }

    @Test
    fun `afwijzende reply op een planned story zet planning-rejected met de tekst als reden`() {
        val fixture = fixture(pending("SF-1", "STORY", "planned"))

        assertTrue(fixture.service.handleReply(reply("De aanpak mist de database-migratie.")))

        assertEquals(
            listOf(Triple("SF-1", "planning-rejected", "De aanpak mist de database-migratie.")),
            fixture.operations.storyPhases,
        )
        assertTrue(fixture.client.sent.single().text.contains("Teruggestuurd met feedback"))
    }

    @Test
    fun `beoordelings-reply op een subtaak keurt goed of stuurt terug met reden`() {
        val approveFixture = fixture(pending("SF-12", "SUBTASK", "developed"))
        assertTrue(approveFixture.service.handleReply(reply("ok")))
        assertEquals(listOf(Triple("SF-12", "development-approved", null as String?)), approveFixture.operations.subtaskPhases)

        val rejectFixture = fixture(pending("SF-12", "SUBTASK", "tested"))
        assertTrue(rejectFixture.service.handleReply(reply("De knop doet niets op mobiel.")))
        assertEquals(
            listOf(Triple("SF-12", "test-rejected", "De knop doet niets op mobiel.")),
            rejectFixture.operations.subtaskPhases,
        )
    }

    @Test
    fun `reply op een awaiting-human subtaak markeert de handmatige actie als klaar`() {
        val fixture = fixture(pending("SF-12", "SUBTASK", "awaiting-human"))

        assertTrue(fixture.service.handleReply(reply("DNS-record is aangemaakt.")))

        assertEquals(
            listOf(Triple("SF-12", "manual-action-done", "DNS-record is aangemaakt.")),
            fixture.operations.subtaskPhases,
        )
        assertTrue(fixture.client.sent.single().text.contains("Als klaar gemarkeerd"))
    }

    @Test
    fun `manual-approve-poort - approve queuet een approve-commando en andere tekst een reject met reden`() {
        val approveFixture = fixture(pending("SF-12", "SUBTASK", "manual-approve-needed"))
        assertTrue(approveFixture.service.handleReply(reply("akkoord")))
        assertEquals(listOf(Triple("SF-12", FactoryCommand.APPROVE, null as String?)), approveFixture.operations.commands)

        val rejectFixture = fixture(pending("SF-12", "SUBTASK", "manual-approve-needed"))
        assertTrue(rejectFixture.service.handleReply(reply("Eerst de logging fixen.")))
        assertEquals(listOf(Triple("SF-12", FactoryCommand.REJECT, "Eerst de logging fixen." as String?)), rejectFixture.operations.commands)
        assertTrue(rejectFixture.client.sent.single().text.contains("Afgekeurd met feedback"))
    }

    // ── merge-ready-melding ─────────────────────────────────────────────────────

    @Test
    fun `merge-reply op de merge-ready-melding queuet het merge-commando`() {
        val fixture = fixture(pending("SF-1", "STORY", MERGE_READY_PHASE))

        assertTrue(fixture.service.handleReply(reply("merge")))

        assertEquals(listOf(Triple("SF-1", FactoryCommand.MERGE, null as String?)), fixture.operations.commands)
        assertTrue(fixture.operations.storyPhases.isEmpty(), "merge mag geen fase-wijziging doen")
        assertTrue(fixture.store.pending.isEmpty(), "pending moet na de merge weg zijn")
        assertTrue(fixture.client.sent.single().text.contains("🚀 Merge gestart voor SF-1"))
        assertTrue(fixture.events.any { it == FactoryStateChangedEvent("telegram-reply:SF-1") })
    }

    @Test
    fun `andere tekst op de merge-ready-melding doet niets en laat de melding staan`() {
        val fixture = fixture(pending("SF-1", "STORY", MERGE_READY_PHASE))

        val handled = fixture.service.handleReply(reply("ziet er goed uit!"))

        assertFalse(handled, "geen merge-keyword => niet als reply verwerken (valt door naar de assistent)")
        assertTrue(fixture.operations.commands.isEmpty())
        assertEquals(1, fixture.store.pending.size, "pending moet blijven staan zodat een latere 'merge' alsnog werkt")
        assertTrue(fixture.client.sent.isEmpty())
    }

    // ── negeren van niet-verwerkbare updates ────────────────────────────────────

    @Test
    fun `geen reply, onbekend bericht of lege tekst wordt stilletjes genegeerd`() {
        val fixture = fixture(pending("SF-1", "STORY", "refined"))

        assertFalse(fixture.service.handleReply(reply("approve", replyTo = null)), "geen reply")
        assertFalse(fixture.service.handleReply(reply("approve", replyTo = 999L)), "reply op een onbekend bericht")
        assertFalse(fixture.service.handleReply(reply(null)), "reply zonder tekst")

        assertTrue(fixture.operations.storyPhases.isEmpty())
        assertTrue(fixture.operations.commands.isEmpty())
        assertEquals(1, fixture.store.pending.size, "pending moet onaangeroerd blijven")
        assertTrue(fixture.client.sent.isEmpty())
    }

    @Test
    fun `onbekende bron-fase wordt genegeerd en de pending blijft staan`() {
        // Een fase waar geen reply-actie bij hoort (bv. een actieve agent-fase).
        val fixture = fixture(pending("SF-1", "STORY", "refining"))

        assertFalse(fixture.service.handleReply(reply("approve")))

        assertTrue(fixture.operations.storyPhases.isEmpty())
        assertEquals(1, fixture.store.pending.size)
    }

    // ── fixture & fakes ─────────────────────────────────────────────────────────

    private fun reply(text: String?, replyTo: Long? = 100L, chatId: String? = "chat-1") = TelegramUpdate(
        updateId = 1L,
        chatId = chatId,
        text = text,
        messageId = 200L,
        replyToMessageId = replyTo,
    )

    private fun pending(issueKey: String, level: String, sourcePhase: String) =
        PendingQuestion(chatId = "chat-1", messageId = 100L, issueKey = issueKey, issueLevel = level, sourcePhase = sourcePhase)

    private class Fixture(
        val service: TelegramReplyService,
        val operations: RecordingOperations,
        val store: InMemoryTelegramStore,
        val client: RecordingTelegramClient,
        val events: List<Any>,
    )

    private fun fixture(vararg pending: PendingQuestion): Fixture {
        val operations = RecordingOperations()
        val store = InMemoryTelegramStore()
        pending.forEach { store.pending[it.chatId to it.messageId] = it }
        val client = RecordingTelegramClient(secrets())
        val events = mutableListOf<Any>()
        val publisher = ApplicationEventPublisher { event -> events += event }
        val service = TelegramReplyService(operations, store, client, ChangeNotifier.Noop, publisher)
        return Fixture(service, operations, store, client, events)
    }

    /** Vangt alle factory-acties af die een reply zou moeten triggeren. */
    private class RecordingOperations : FactoryOperations {
        val storyPhases = mutableListOf<Triple<String, String, String?>>()
        val subtaskPhases = mutableListOf<Triple<String, String, String?>>()
        val commands = mutableListOf<Triple<String, FactoryCommand, String?>>()

        override fun questionFor(issue: TrackerIssue): String? = null
        override fun autoApproveActive(issue: TrackerIssue): Boolean = false
        override fun mergeReady(storyKey: String): MergeReadyInfo? = null
        override fun mergeReadyForSubtask(subtask: TrackerIssue): MergeReadyInfo? = null
        override fun testerReportFor(storyKey: String): String? = null
        override fun previewUrlFor(storyKey: String): String? = null
        override fun setStoryPhase(storyKey: String, phase: String, comment: String?) {
            storyPhases += Triple(storyKey, phase, comment)
        }
        override fun setSubtaskPhase(subtaskKey: String, phase: String, comment: String?) {
            subtaskPhases += Triple(subtaskKey, phase, comment)
        }
        override fun queueCommand(storyKey: String, command: FactoryCommand, reason: String?) {
            commands += Triple(storyKey, command, reason)
        }
    }

    private class InMemoryTelegramStore : TelegramStore {
        val pending = mutableMapOf<Pair<String, Long>, PendingQuestion>()
        val clearedNotifications = mutableListOf<String>()

        override fun alreadyNotified(issueKey: String, signature: String): Boolean = false
        override fun recordNotified(issueKey: String, signature: String) {}
        override fun clearNotifications(issueKey: String) { clearedNotifications += issueKey }
        override fun savePending(chatId: String, messageId: Long, issueKey: String, issueLevel: String, sourcePhase: String) {
            pending[chatId to messageId] = PendingQuestion(chatId, messageId, issueKey, issueLevel, sourcePhase)
        }
        override fun findPending(chatId: String, messageId: Long): PendingQuestion? = pending[chatId to messageId]
        override fun deletePending(chatId: String, messageId: Long) { pending.remove(chatId to messageId) }
        override fun getUpdatesOffset(): Long? = null
        override fun setUpdatesOffset(offset: Long) {}
    }

    private data class SentMessage(val text: String, val replyToMessageId: Long?, val chatId: String?)

    private class RecordingTelegramClient(secrets: FactorySecrets) : TelegramClient(secrets) {
        val sent = mutableListOf<SentMessage>()
        private var counter = 1000L
        override val enabled: Boolean get() = true
        override fun sendMessage(text: String, replyToMessageId: Long?, chatId: String?): Long {
            sent += SentMessage(text, replyToMessageId, chatId)
            return ++counter
        }
    }

    private fun secrets() = FactorySecrets(
        youTrackBaseUrl = "https://yt.example",
        youTrackToken = "token",
        youTrackProjects = emptyList(),
        githubToken = "gh",
        factoryDatabaseUrl = "jdbc:postgresql://localhost/test",
        factoryDatabaseSchema = "public",
        kubeconfig = null,
        aiCredentialsDir = null,
        aiOauthToken = null,
        loadedFrom = "test",
    )
}
