package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.telegram.clients.*
import nl.vdzon.softwarefactory.telegram.repositories.*
import nl.vdzon.softwarefactory.telegram.services.*

import nl.vdzon.softwarefactory.telegram.models.*

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.knowledge.models.AgentKnowledgeEntry
import nl.vdzon.softwarefactory.knowledge.models.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.OffsetDateTime

/**
 * Flowtests voor [TelegramAssistantService.handle]: een vrij bericht start/vervolgt een Runtime-
 * gesprek (thread-administratie + antwoord als reply), en /stop breekt het lopende gesprek van de
 * ge-reply-de thread af. De Runtime-kant is een fake ([FakeAssistant]).
 */
class TelegramAssistantFlowTest {

    // ── gespreksflow ────────────────────────────────────────────────────────────

    @Test
    fun `een vrij bericht start een nieuwe assistentsessie en stuurt het antwoord als reply`() {
        val fixture = fixture()

        fixture.service.handle("chat-1", "hoe staat SF-1 ervoor?", photoFileId = null, messageId = 10L, replyToMessageId = null)

        val ask = fixture.assistant.asks.single()
        assertEquals("chat-1", ask.chatId)
        assertEquals(false, ask.isResume, "zonder reply en zonder actieve root hoort een nieuwe sessie te starten")
        assertEquals("hoe staat SF-1 ervoor?", ask.userMessage)
        val sent = fixture.client.sent.single()
        assertEquals("antwoord van Runtime", sent.text)
        assertEquals(10L, sent.replyToMessageId, "antwoord hoort als reply op het gebruikersbericht")
        // Thread-administratie: beide berichten wijzen naar de sessie en de root is actief.
        assertEquals(ask.sessionId, fixture.threads.mappings["chat-1" to 10L])
        assertEquals(ask.sessionId, fixture.threads.mappings["chat-1" to sent.messageId])
        assertEquals(ask.sessionId, fixture.threads.activeRoot)
    }

    @Test
    fun `een reply op een eerder antwoord vervolgt de bestaande assistentsessie`() {
        val fixture = fixture()
        fixture.threads.mappings["chat-1" to 55L] = "sessie-bestaand"

        fixture.service.handle("chat-1", "en de subtaken?", photoFileId = null, messageId = 60L, replyToMessageId = 55L)

        val ask = fixture.assistant.asks.single()
        assertEquals("sessie-bestaand", ask.sessionId)
        assertEquals(true, ask.isResume, "reply op een gespreksbericht hoort de thread te hervatten")
    }

    @Test
    fun `zonder Runtime-token meldt de assistent dat hij uitstaat en start geen beurt`() {
        val fixture = fixture(assistant = FakeAssistant(enabledResult = false))

        fixture.service.handle("chat-1", "hallo", photoFileId = null, messageId = 10L, replyToMessageId = null)

        assertTrue(fixture.assistant.asks.isEmpty())
        assertTrue(fixture.client.sent.single().text.contains("Agent Runtime"))
    }

    @Test
    fun `tips uit het Runtime-antwoord worden als assistent-kennis opgeslagen`() {
        val tip = AssistantTip("login", "news-feed", "testaccount staat in private/secrets.env")
        val knowledge = RecordingKnowledge()
        val fixture = fixture(
            assistant = FakeAssistant(reply = okReply.copy(tips = listOf(tip))),
            knowledge = knowledge,
        )

        fixture.service.handle("chat-1", "log eens in op de feed", photoFileId = null, messageId = 10L, replyToMessageId = null)

        val upsert = knowledge.upserts.single()
        assertEquals(AgentRole.ASSISTANT.markerKeyPart, upsert.role, "tips horen onder de assistent-rol")
        assertEquals("factory", upsert.targetRepo, "zonder projectkanaal vallen tips onder 'factory'")
        assertEquals("login", upsert.category)
        assertEquals("news-feed", upsert.key)
        assertEquals("testaccount staat in private/secrets.env", upsert.content)
    }

    @Test
    fun `een door stop afgebroken beurt stuurt geen antwoord en wijzigt de actieve thread niet`() {
        val stoppedReply = AssistantReply("", isError = true, sessionId = null, costUsd = 0.0, stopped = true)
        val fixture = fixture(assistant = FakeAssistant(reply = stoppedReply))

        fixture.service.handle("chat-1", "doe iets langdurigs", photoFileId = null, messageId = 10L, replyToMessageId = null)

        assertTrue(fixture.client.sent.isEmpty(), "een gestopte beurt mag geen (half) antwoord meer sturen")
        assertNull(fixture.threads.activeRoot, "de gestopte thread mag niet de actieve root worden")
    }

    // ── stop-commando ───────────────────────────────────────────────────────────

    @Test
    fun `stop-commando als reply op een gespreksbericht breekt die thread af zonder assistentbeurt`() {
        val fixture = fixture()
        fixture.threads.mappings["chat-1" to 55L] = "sessie-1"

        fixture.service.handle("chat-1", "/stop", photoFileId = null, messageId = 60L, replyToMessageId = 55L)

        assertEquals(listOf("sessie-1"), fixture.assistant.stops, "de sessie van de ge-reply-de thread wordt gestopt")
        assertTrue(fixture.assistant.asks.isEmpty(), "/stop mag geen nieuwe Runtime-beurt starten")
        assertTrue(fixture.client.sent.single().text.contains("Gesprek afgebroken"))
    }

    @Test
    fun `stop-commando zonder reply legt uit hoe je een gesprek stopt`() {
        val fixture = fixture()

        fixture.service.handle("chat-1", "/stop", photoFileId = null, messageId = 60L, replyToMessageId = null)

        assertTrue(fixture.assistant.stops.isEmpty())
        assertTrue(fixture.assistant.asks.isEmpty())
        assertTrue(fixture.client.sent.single().text.contains("Reply met /stop"))
    }

    @Test
    fun `stop-commando op een thread zonder lopende beurt meldt dat er niets loopt`() {
        val fixture = fixture(assistant = FakeAssistant(stopResult = false))
        fixture.threads.mappings["chat-1" to 55L] = "sessie-1"

        fixture.service.handle("chat-1", "/stop", photoFileId = null, messageId = 60L, replyToMessageId = 55L)

        assertEquals(listOf("sessie-1"), fixture.assistant.stops)
        assertTrue(fixture.client.sent.single().text.contains("loopt op dit moment niets"))
    }

    // ── fixture & fakes ─────────────────────────────────────────────────────────

    private val okReply = AssistantReply("antwoord van Runtime", isError = false, sessionId = null, costUsd = 0.0)

    private class Fixture(
        val service: TelegramAssistantService,
        val assistant: FakeAssistant,
        val client: RecordingTelegramClient,
        val threads: InMemoryThreadStore,
    )

    private fun fixture(
        assistant: FakeAssistant = FakeAssistant(reply = okReply),
        knowledge: KnowledgeApi = NoopKnowledge,
    ): Fixture {
        val resolver = ProjectConfiguration(emptyMap())
        val secrets = secrets()
        val client = RecordingTelegramClient(secrets)
        val threads = InMemoryThreadStore()
        val service = TelegramAssistantService(assistant, threads, client, resolver, knowledge)
        return Fixture(service, assistant, client, threads)
    }

    /** Fake voor de Runtime-kant: alleen registratie van ask/stop. */
    private class FakeAssistant(
        private val reply: AssistantReply = AssistantReply("antwoord van Runtime", isError = false, sessionId = null, costUsd = 0.0),
        private val stopResult: Boolean = true,
        private val enabledResult: Boolean = true,
    ) : InteractiveAssistantClient {
        data class Ask(val chatId: String, val sessionId: String, val isResume: Boolean, val userMessage: String)
        val asks = mutableListOf<Ask>()
        val stops = mutableListOf<String>()

        override val enabled: Boolean get() = enabledResult

        override fun ask(
            chatId: String,
            projectKey: String?,
            sessionId: String,
            isResume: Boolean,
            systemPrompt: String,
            userMessage: String,
            inputFile: AssistantInputFile?,
            timeoutSecondsOverride: Long?,
        ): AssistantReply {
            asks += Ask(chatId, sessionId, isResume, userMessage)
            // Net als de echte client: geef de daadwerkelijk gebruikte sessie-id terug.
            return reply.copy(sessionId = reply.sessionId ?: sessionId)
        }

        override fun stop(sessionId: String): Boolean {
            stops += sessionId
            return stopResult
        }
    }

    private class InMemoryThreadStore : TelegramThreadStore {
        val mappings = mutableMapOf<Pair<String, Long>, String>()
        var activeRoot: String? = null
        override fun sessionFor(chatId: String, messageId: Long): String? = mappings[chatId to messageId]
        override fun map(chatId: String, messageId: Long, sessionId: String) { mappings[chatId to messageId] = sessionId }
        override fun activeRootSession(chatId: String): String? = activeRoot
        override fun setActiveRootSession(chatId: String, sessionId: String) { activeRoot = sessionId }
    }

    private data class SentMessage(val text: String, val replyToMessageId: Long?, val chatId: String?, val messageId: Long)

    private class RecordingTelegramClient(secrets: FactorySecrets) : TelegramClient(secrets) {
        val sent = mutableListOf<SentMessage>()
        private var counter = 1000L
        override val enabled: Boolean get() = true
        override fun sendMessage(text: String, replyToMessageId: Long?, chatId: String?): Long {
            val id = ++counter
            sent += SentMessage(text, replyToMessageId, chatId, id)
            return id
        }
        override fun sendChatAction(chatId: String, action: String) {}
        override fun sendPhoto(chatId: String, file: Path, caption: String?): Boolean = true
    }

    private class RecordingKnowledge : KnowledgeApi {
        val upserts = mutableListOf<AgentKnowledgeUpdateRequest>()
        override fun find(targetRepo: String, role: String): List<AgentKnowledgeEntry> = emptyList()
        override fun upsert(request: AgentKnowledgeUpdateRequest): AgentKnowledgeEntry {
            upserts += request
            return AgentKnowledgeEntry(
                targetRepo = request.targetRepo,
                role = request.role,
                category = request.category,
                key = request.key,
                content = request.content,
                updatedByStory = null,
                updatedAt = OffsetDateTime.now(),
            )
        }
        override fun delete(targetRepo: String, role: String, category: String, key: String): Boolean = false
    }

    private object NoopKnowledge : KnowledgeApi {
        override fun find(targetRepo: String, role: String): List<AgentKnowledgeEntry> = emptyList()
        override fun upsert(request: AgentKnowledgeUpdateRequest): AgentKnowledgeEntry =
            throw UnsupportedOperationException()
        override fun delete(targetRepo: String, role: String, category: String, key: String): Boolean = false
    }

}

private fun secrets() = FactorySecrets(
    trackerProjects = emptyList(),
    githubToken = "gh",
    factoryDatabaseUrl = "jdbc:postgresql://localhost/test",
    factoryDatabaseSchema = "public",
    kubeconfig = null,
    loadedFrom = "test",
)
