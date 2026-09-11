package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.telegram.clients.*
import nl.vdzon.softwarefactory.telegram.repositories.*
import nl.vdzon.softwarefactory.telegram.services.*

import nl.vdzon.softwarefactory.telegram.models.*

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.knowledge.models.AgentKnowledgeEntry
import nl.vdzon.softwarefactory.knowledge.models.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * Unit-tests voor TelegramAssistantService (systemPrompt, tips en threadselectie).
 */
class TelegramAssistantServiceTest {

    // --- FactorySecrets stubs ---

    private val minimalSecrets = FactorySecrets(
        trackerProjects = emptyList(),
        githubToken = "gh",
        factoryDatabaseUrl = "jdbc:postgresql://db/sf",
        factoryDatabaseSchema = "sf",
        kubeconfig = null,
        loadedFrom = "test",
    )

    // --- KnowledgeApi stubs ---

    private fun knowledgeWithTips(vararg tips: AgentKnowledgeEntry): KnowledgeApi = object : KnowledgeApi {
        override fun find(targetRepo: String, role: String): List<AgentKnowledgeEntry> = tips.toList()
        override fun upsert(request: AgentKnowledgeUpdateRequest): AgentKnowledgeEntry = tips.first()
        override fun delete(targetRepo: String, role: String, category: String, key: String): Boolean = false
    }

    private fun knowledgeEmpty(): KnowledgeApi = object : KnowledgeApi {
        override fun find(targetRepo: String, role: String): List<AgentKnowledgeEntry> = emptyList()
        override fun upsert(request: AgentKnowledgeUpdateRequest): AgentKnowledgeEntry =
            throw UnsupportedOperationException()
        override fun delete(targetRepo: String, role: String, category: String, key: String): Boolean = false
    }

    private fun knowledgeFailing(): KnowledgeApi = object : KnowledgeApi {
        override fun find(targetRepo: String, role: String): List<AgentKnowledgeEntry> =
            throw RuntimeException("DB down")
        override fun upsert(request: AgentKnowledgeUpdateRequest): AgentKnowledgeEntry =
            throw UnsupportedOperationException()
        override fun delete(targetRepo: String, role: String, category: String, key: String): Boolean = false
    }

    private fun tip(category: String, key: String, content: String) = AgentKnowledgeEntry(
        targetRepo = "my-project", role = "assistant",
        category = category, key = key, content = content,
        updatedByStory = null, updatedAt = OffsetDateTime.now(),
    )

    // --- Minimal stubs voor constructie van TelegramAssistantService ---

    private val noopThreadStore = object : TelegramThreadStore {
        override fun sessionFor(chatId: String, messageId: Long): String? = null
        override fun map(chatId: String, messageId: Long, sessionId: String) {}
        override fun activeRootSession(chatId: String): String? = null
        override fun setActiveRootSession(chatId: String, sessionId: String) {}
    }

    private class TrackingThreadStore(
        private val sessions: Map<Long, String> = emptyMap(),
        private var activeRoot: String? = null,
    ) : TelegramThreadStore {
        val setActiveCalls = mutableListOf<String>()

        override fun sessionFor(chatId: String, messageId: Long) = sessions[messageId]
        override fun map(chatId: String, messageId: Long, sessionId: String) {}
        override fun activeRootSession(chatId: String) = activeRoot
        override fun setActiveRootSession(chatId: String, sessionId: String) {
            activeRoot = sessionId
            setActiveCalls += sessionId
        }
    }

    private val noopAssistant = object : InteractiveAssistantClient {
        override val enabled: Boolean = true
        override fun ask(
            chatId: String,
            projectKey: String?,
            sessionId: String,
            isResume: Boolean,
            systemPrompt: String,
            userMessage: String,
            inputFile: AssistantInputFile?,
            timeoutSecondsOverride: Long?,
        ) = AssistantReply("antwoord", false, sessionId, 0.0)
        override fun stop(sessionId: String): Boolean = true
    }

    private fun makeService(
        secrets: FactorySecrets = minimalSecrets,
        knowledgeApi: KnowledgeApi = knowledgeEmpty(),
        projectName: String? = "my-project",
        threadStore: TelegramThreadStore = noopThreadStore,
    ): TelegramAssistantService {
        val resolver = ProjectConfiguration(
            repos = if (projectName != null) mapOf(projectName to "git@github.com:example/$projectName.git") else emptyMap(),
            telegramChatIds = if (projectName != null) mapOf(projectName to "my-chat") else emptyMap(),
        )
        val telegramClient = TelegramClient(secrets)
        return TelegramAssistantService(noopAssistant, threadStore, telegramClient, resolver, knowledgeApi)
    }

    private fun callSystemPrompt(service: TelegramAssistantService, chatId: String): String {
        val method = TelegramAssistantService::class.java.getDeclaredMethod("systemPrompt", String::class.java)
        method.isAccessible = true
        return method.invoke(service, chatId) as String
    }

    // --- Tests: systemPrompt met/zonder tips ---

    @Test
    fun `systemPrompt bevat geleerde inzichten als KnowledgeApi tips teruggeeft`() {
        val tip = tip("cluster", "pods-ophalen", "gebruik oc get pods -n namespace")
        val service = makeService(knowledgeApi = knowledgeWithTips(tip))

        val prompt = callSystemPrompt(service, "my-chat")
        assertTrue(prompt.contains("## Geleerde inzichten"), "Sectietitel ontbreekt")
        assertTrue(prompt.contains("cluster/pods-ophalen"), "Tip-sleutel ontbreekt")
        assertTrue(prompt.contains("gebruik oc get pods"), "Tip-inhoud ontbreekt")
    }

    @Test
    fun `systemPrompt bevat geen geleerde-inzichten-sectie als er geen tips zijn`() {
        val service = makeService(knowledgeApi = knowledgeEmpty())
        val prompt = callSystemPrompt(service, "my-chat")
        assertFalse(prompt.contains("## Geleerde inzichten"), "Sectie mag niet aanwezig zijn bij lege tips")
    }

    @Test
    fun `systemPrompt gooit geen exception als KnowledgeApi faalt`() {
        val service = makeService(knowledgeApi = knowledgeFailing())
        val prompt = callSystemPrompt(service, "my-chat")
        assertFalse(prompt.contains("## Geleerde inzichten"))
    }

    @Test
    fun `systemPrompt legt uit dat tips apart in het resultaat staan`() {
        val service = makeService()
        val prompt = callSystemPrompt(service, "my-chat")
        assertTrue(prompt.contains("`tips`-veld"), "instructie voor gestructureerde tips ontbreekt")
    }

    // --- Tests: detectPrefix ---

    private fun callDetectPrefix(service: TelegramAssistantService, text: String): String? {
        val m = TelegramAssistantService::class.java.getDeclaredMethod("detectPrefix", String::class.java)
        m.isAccessible = true
        return m.invoke(service, text) as String?
    }

    @Test
    fun `detectPrefix herkent 'nieuw' prefix en strippt hem`() {
        val s = makeService()
        assertEquals("vraag?", callDetectPrefix(s, "nieuw: vraag?"))
    }

    @Test
    fun `detectPrefix herkent 'nieuwe vraag' prefix`() {
        val s = makeService()
        assertEquals("test", callDetectPrefix(s, "NIEUWE VRAAG: test"))
    }

    @Test
    fun `detectPrefix herkent 'new' prefix`() {
        val s = makeService()
        assertEquals("iets", callDetectPrefix(s, "new: iets"))
    }

    @Test
    fun `detectPrefix herkent 'new question' prefix`() {
        val s = makeService()
        assertEquals("hallo", callDetectPrefix(s, "New Question: hallo"))
    }

    @Test
    fun `detectPrefix herkent 'iets anders' prefix`() {
        val s = makeService()
        assertEquals("onderwerp", callDetectPrefix(s, "iets anders: onderwerp"))
    }

    @Test
    fun `detectPrefix herkent 'story' prefix`() {
        val s = makeService()
        assertEquals("beschrijving", callDetectPrefix(s, "story: beschrijving"))
    }

    @Test
    fun `detectPrefix is case-insensitief`() {
        val s = makeService()
        assertEquals("x", callDetectPrefix(s, "NIEUW: x"))
        assertEquals("y", callDetectPrefix(s, "Story: y"))
        assertEquals("z", callDetectPrefix(s, "NEW: z"))
    }

    @Test
    fun `detectPrefix geeft null als er geen prefix is`() {
        val s = makeService()
        assertNull(callDetectPrefix(s, "gewoon een vraag"))
        assertNull(callDetectPrefix(s, ""))
        assertNull(callDetectPrefix(s, "reply op antwoord"))
    }

    @Test
    fun `detectPrefix detecteert alleen op de eerste regel`() {
        val s = makeService()
        // 'nieuw:' op tweede regel mag niet matchen
        assertNull(callDetectPrefix(s, "eerste regel\nnieuw: tweede regel"))
    }

    @Test
    fun `detectPrefix behoudt resterende regels na de eerste`() {
        val s = makeService()
        val result = callDetectPrefix(s, "nieuw: titel\nregel twee\nregel drie")
        assertEquals("titel\nregel twee\nregel drie", result)
    }

    @Test
    fun `detectPrefix geeft lege string na prefix zonder verdere inhoud`() {
        val s = makeService()
        // "nieuw:" zonder verdere tekst → lege string (niet null); handle() negeert dit geval.
        assertEquals("", callDetectPrefix(s, "nieuw:"))
        assertEquals("", callDetectPrefix(s, "new:"))
        assertEquals("", callDetectPrefix(s, "story:"))
    }

    // --- Tests: determineSession ---

    private fun callDetermineSession(
        service: TelegramAssistantService,
        chatId: String,
        replyToMessageId: Long?,
        forceNew: Boolean,
    ): Pair<*, *> {
        val m = TelegramAssistantService::class.java.getDeclaredMethod(
            "determineSession", String::class.java, Long::class.javaObjectType, Boolean::class.java,
        )
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return m.invoke(service, chatId, replyToMessageId, forceNew) as Pair<*, *>
    }

    @Test
    fun `determineSession volgt reply-keten als replyToMessageId bekend is`() {
        val store = TrackingThreadStore(sessions = mapOf(42L to "existing-session"))
        val s = makeService(threadStore = store)
        val (sessionId, isResume) = callDetermineSession(s, "chat1", 42L, false)
        assertEquals("existing-session", sessionId)
        assertEquals(true, isResume)
    }

    @Test
    fun `determineSession maakt nieuwe UUID als forceNew is true`() {
        val store = TrackingThreadStore(activeRoot = "old-session")
        val s = makeService(threadStore = store)
        val (sessionId, isResume) = callDetermineSession(s, "chat1", null, true)
        assertNotEquals("old-session", sessionId)
        assertEquals(false, isResume)
    }

    @Test
    fun `determineSession gebruikt actieve root als er geen reply en geen prefix is`() {
        val store = TrackingThreadStore(activeRoot = "active-session")
        val s = makeService(threadStore = store)
        val (sessionId, isResume) = callDetermineSession(s, "chat1", null, false)
        assertEquals("active-session", sessionId)
        assertEquals(true, isResume)
    }

    @Test
    fun `determineSession maakt nieuwe UUID als geen reply en geen actieve root`() {
        val store = TrackingThreadStore(activeRoot = null)
        val s = makeService(threadStore = store)
        val (sessionId, isResume) = callDetermineSession(s, "chat1", null, false)
        assertNotNull(sessionId)
        assertEquals(false, isResume)
    }
}
