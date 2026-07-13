package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.telegram.clients.*
import nl.vdzon.softwarefactory.telegram.repositories.*
import nl.vdzon.softwarefactory.telegram.services.*

import nl.vdzon.softwarefactory.telegram.models.*

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.git.GitProcessResult
import nl.vdzon.softwarefactory.knowledge.AgentKnowledgeEntry
import nl.vdzon.softwarefactory.knowledge.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.OffsetDateTime

/**
 * Unit-tests voor TelegramAssistantService (systemPrompt/tips) en ClaudeAssistantClient (dockerCommand).
 */
class TelegramAssistantServiceTest {

    // --- FactorySecrets stubs ---

    private val minimalSecrets = FactorySecrets(
        trackerProjects = emptyList(),
        githubToken = "gh",
        factoryDatabaseUrl = "jdbc:postgresql://db/sf",
        factoryDatabaseSchema = "sf",
        kubeconfig = null,
        aiCredentialsDir = null,
        loadedFrom = "test",
        aiOauthToken = "oauth-tok",
    )

    // --- KnowledgeApi stubs ---

    private fun knowledgeWithTips(vararg tips: AgentKnowledgeEntry): KnowledgeApi = object : KnowledgeApi {
        override fun find(targetRepo: String, role: String): List<AgentKnowledgeEntry> = tips.toList()
        override fun upsert(request: AgentKnowledgeUpdateRequest): AgentKnowledgeEntry = tips.first()
    }

    private fun knowledgeEmpty(): KnowledgeApi = object : KnowledgeApi {
        override fun find(targetRepo: String, role: String): List<AgentKnowledgeEntry> = emptyList()
        override fun upsert(request: AgentKnowledgeUpdateRequest): AgentKnowledgeEntry =
            throw UnsupportedOperationException()
    }

    private fun knowledgeFailing(): KnowledgeApi = object : KnowledgeApi {
        override fun find(targetRepo: String, role: String): List<AgentKnowledgeEntry> =
            throw RuntimeException("DB down")
        override fun upsert(request: AgentKnowledgeUpdateRequest): AgentKnowledgeEntry =
            throw UnsupportedOperationException()
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

    private val noopGitApi = object : GitApi {
        override fun clone(repoUrl: String, targetDir: Path, githubToken: String?) {}
        override fun checkoutBase(repoRoot: Path, baseBranch: String, githubToken: String?) {}
        override fun checkoutStoryBranch(repoRoot: Path, branchName: String, baseBranch: String, createIfMissing: Boolean, githubToken: String?) {}
        override fun commitAll(repoRoot: Path, message: String, githubToken: String?): Boolean = false
        override fun push(repoRoot: Path, branchName: String, githubToken: String?) {}
        override fun remoteBranchExists(repoRoot: Path, branchName: String, githubToken: String?): Boolean = false
        override fun runCommand(command: List<String>, cwd: Path?, env: Map<String, String>, timeoutSeconds: Long): GitProcessResult =
            GitProcessResult(0, "", "")
        override fun repositorySlug(repoUrl: String): String? = null
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
        val claude = ClaudeAssistantClient(secrets)
        val workspaceService = AssistantWorkspaceService(noopGitApi, secrets, resolver)
        return TelegramAssistantService(claude, threadStore, telegramClient, resolver, workspaceService, knowledgeApi)
    }

    private fun callSystemPrompt(service: TelegramAssistantService, chatId: String): String {
        val method = TelegramAssistantService::class.java.getDeclaredMethod(
            "systemPrompt", String::class.java, AssistantWorkspaceService.Layout::class.java,
        )
        method.isAccessible = true
        return method.invoke(service, chatId, AssistantWorkspaceService.Layout(mounts = emptyList(), layers = emptyList())) as String
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
    fun `systemPrompt legt uit hoe tips opgeslagen worden via agent_tips_update`() {
        val service = makeService()
        val prompt = callSystemPrompt(service, "my-chat")
        assertTrue(prompt.contains("agent_tips_update"), "agent_tips_update-instructie ontbreekt")
    }

    // --- Tests: tip-parsing uit het assistent-antwoord (ClaudeAssistantClient) ---

    @Test
    fun `extractTips haalt de tips uit het agent_tips_update-JSON`() {
        val text = "Hier is je antwoord.\n\n" +
            "{\"agent_tips_update\":[{\"category\":\"login\",\"key\":\"news-feed\",\"content\":\"account staat in private\"}]}"
        val tips = extractTips(text)
        assertEquals(1, tips.size)
        assertEquals("login", tips[0].category)
        assertEquals("news-feed", tips[0].key)
        assertEquals("account staat in private", tips[0].content)
    }

    @Test
    fun `stripTipsJson verwijdert het tips-JSON maar houdt de gewone tekst`() {
        val text = "Antwoord voor de gebruiker.\n\n{\"agent_tips_update\":[{\"category\":\"a\",\"key\":\"b\",\"content\":\"c\"}]}"
        val clean = stripTipsJson(text)
        assertFalse(clean.contains("agent_tips_update"), "tips-JSON mag niet meer in de tekst staan")
        assertTrue(clean.contains("Antwoord voor de gebruiker."), "gewone tekst moet blijven")
    }

    @Test
    fun `extractTips geeft lege lijst bij een lege agent_tips_update-array`() {
        assertTrue(extractTips("Niets nieuws geleerd.\n{\"agent_tips_update\":[]}").isEmpty())
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

    @Suppress("UNCHECKED_CAST")
    private fun extractTips(text: String): List<AssistantTip> {
        val m = ClaudeAssistantClient::class.java.getDeclaredMethod("extractTips", String::class.java)
        m.isAccessible = true
        return m.invoke(ClaudeAssistantClient(minimalSecrets), text) as List<AssistantTip>
    }

    private fun stripTipsJson(text: String): String {
        val m = ClaudeAssistantClient::class.java.getDeclaredMethod("stripTipsJson", String::class.java)
        m.isAccessible = true
        return m.invoke(ClaudeAssistantClient(minimalSecrets), text) as String
    }
}
