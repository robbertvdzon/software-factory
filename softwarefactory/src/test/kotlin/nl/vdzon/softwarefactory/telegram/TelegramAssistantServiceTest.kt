package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.git.GitProcessResult
import nl.vdzon.softwarefactory.git.services.ProcessResult
import nl.vdzon.softwarefactory.git.services.ProcessRunner
import nl.vdzon.softwarefactory.knowledge.AgentKnowledgeEntry
import nl.vdzon.softwarefactory.knowledge.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        youTrackBaseUrl = "https://yt.example",
        youTrackToken = "tok",
        youTrackProjects = emptyList(),
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

    private val noopProcessRunner = object : ProcessRunner {
        override fun run(command: List<String>, cwd: Path?, env: Map<String, String>, timeoutSeconds: Long): ProcessResult =
            ProcessResult(0, "", "")
    }

    private fun makeService(
        secrets: FactorySecrets = minimalSecrets,
        knowledgeApi: KnowledgeApi = knowledgeEmpty(),
        projectName: String? = "my-project",
    ): TelegramAssistantService {
        val resolver = ProjectRepoResolver(
            repos = if (projectName != null) mapOf(projectName to "git@github.com:example/$projectName.git") else emptyMap(),
            telegramChatIds = if (projectName != null) mapOf(projectName to "my-chat") else emptyMap(),
        )
        val telegramClient = TelegramClient(secrets)
        val claude = ClaudeAssistantClient(secrets)
        val workspaceService = AssistantWorkspaceService(noopGitApi, noopProcessRunner, secrets, resolver)
        return TelegramAssistantService(claude, noopThreadStore, telegramClient, resolver, workspaceService, knowledgeApi)
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
