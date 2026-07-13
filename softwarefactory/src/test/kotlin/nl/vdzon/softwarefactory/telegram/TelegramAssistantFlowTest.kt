package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.telegram.clients.*
import nl.vdzon.softwarefactory.telegram.repositories.*
import nl.vdzon.softwarefactory.telegram.services.*

import nl.vdzon.softwarefactory.telegram.models.*

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.git.GitProcessResult
import nl.vdzon.softwarefactory.knowledge.AgentKnowledgeEntry
import nl.vdzon.softwarefactory.knowledge.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.OffsetDateTime

/**
 * Flowtests voor [TelegramAssistantService.handle]: een vrij bericht start/vervolgt een claude-
 * gesprek (thread-administratie + antwoord als reply), en /stop breekt het lopende gesprek van de
 * ge-reply-de thread af. De Claude-kant is een fake ([FakeClaude]) — er draait geen Docker/proces.
 */
class TelegramAssistantFlowTest {

    // ── gespreksflow ────────────────────────────────────────────────────────────

    @Test
    fun `een vrij bericht start een nieuwe claude-sessie en stuurt het antwoord als reply`() {
        val fixture = fixture()

        fixture.service.handle("chat-1", "hoe staat SF-1 ervoor?", photoFileId = null, messageId = 10L, replyToMessageId = null)

        val ask = fixture.claude.asks.single()
        assertEquals("chat-1", ask.chatId)
        assertEquals(false, ask.isResume, "zonder reply en zonder actieve root hoort een nieuwe sessie te starten")
        assertEquals("hoe staat SF-1 ervoor?", ask.userMessage)
        val sent = fixture.client.sent.single()
        assertEquals("antwoord van claude", sent.text)
        assertEquals(10L, sent.replyToMessageId, "antwoord hoort als reply op het gebruikersbericht")
        // Thread-administratie: beide berichten wijzen naar de sessie en de root is actief.
        assertEquals(ask.sessionId, fixture.threads.mappings["chat-1" to 10L])
        assertEquals(ask.sessionId, fixture.threads.mappings["chat-1" to sent.messageId])
        assertEquals(ask.sessionId, fixture.threads.activeRoot)
    }

    @Test
    fun `een reply op een eerder antwoord vervolgt de bestaande claude-sessie`() {
        val fixture = fixture()
        fixture.threads.mappings["chat-1" to 55L] = "sessie-bestaand"

        fixture.service.handle("chat-1", "en de subtaken?", photoFileId = null, messageId = 60L, replyToMessageId = 55L)

        val ask = fixture.claude.asks.single()
        assertEquals("sessie-bestaand", ask.sessionId)
        assertEquals(true, ask.isResume, "reply op een gespreksbericht hoort de thread te hervatten")
    }

    @Test
    fun `zonder claude-token meldt de assistent dat hij uitstaat en start geen beurt`() {
        val fixture = fixture(claude = FakeClaude(secrets(), enabledResult = false))

        fixture.service.handle("chat-1", "hallo", photoFileId = null, messageId = 10L, replyToMessageId = null)

        assertTrue(fixture.claude.asks.isEmpty())
        assertTrue(fixture.client.sent.single().text.contains("SF_AI_OAUTH_TOKEN"))
    }

    @Test
    fun `tips uit het claude-antwoord worden als assistent-kennis opgeslagen`() {
        val tip = AssistantTip("login", "news-feed", "testaccount staat in private/secrets.env")
        val knowledge = RecordingKnowledge()
        val fixture = fixture(
            claude = FakeClaude(secrets(), reply = okReply.copy(tips = listOf(tip))),
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
        val fixture = fixture(claude = FakeClaude(secrets(), reply = stoppedReply))

        fixture.service.handle("chat-1", "doe iets langdurigs", photoFileId = null, messageId = 10L, replyToMessageId = null)

        assertTrue(fixture.client.sent.isEmpty(), "een gestopte beurt mag geen (half) antwoord meer sturen")
        assertNull(fixture.threads.activeRoot, "de gestopte thread mag niet de actieve root worden")
    }

    // ── stop-commando ───────────────────────────────────────────────────────────

    @Test
    fun `stop-commando als reply op een gespreksbericht breekt die thread af zonder claude-beurt`() {
        val fixture = fixture()
        fixture.threads.mappings["chat-1" to 55L] = "sessie-1"

        fixture.service.handle("chat-1", "/stop", photoFileId = null, messageId = 60L, replyToMessageId = 55L)

        assertEquals(listOf("sessie-1"), fixture.claude.stops, "de sessie van de ge-reply-de thread wordt gestopt")
        assertTrue(fixture.claude.asks.isEmpty(), "/stop mag geen nieuwe claude-beurt starten")
        assertTrue(fixture.client.sent.single().text.contains("Gesprek afgebroken"))
    }

    @Test
    fun `stop-commando zonder reply legt uit hoe je een gesprek stopt`() {
        val fixture = fixture()

        fixture.service.handle("chat-1", "/stop", photoFileId = null, messageId = 60L, replyToMessageId = null)

        assertTrue(fixture.claude.stops.isEmpty())
        assertTrue(fixture.claude.asks.isEmpty())
        assertTrue(fixture.client.sent.single().text.contains("Reply met /stop"))
    }

    @Test
    fun `stop-commando op een thread zonder lopende beurt meldt dat er niets loopt`() {
        val fixture = fixture(claude = FakeClaude(secrets(), stopResult = false))
        fixture.threads.mappings["chat-1" to 55L] = "sessie-1"

        fixture.service.handle("chat-1", "/stop", photoFileId = null, messageId = 60L, replyToMessageId = 55L)

        assertEquals(listOf("sessie-1"), fixture.claude.stops)
        assertTrue(fixture.client.sent.single().text.contains("loopt op dit moment niets"))
    }

    // ── fixture & fakes ─────────────────────────────────────────────────────────

    private val okReply = AssistantReply("antwoord van claude", isError = false, sessionId = null, costUsd = 0.0)

    private class Fixture(
        val service: TelegramAssistantService,
        val claude: FakeClaude,
        val client: RecordingTelegramClient,
        val threads: InMemoryThreadStore,
    )

    private fun fixture(
        claude: FakeClaude = FakeClaude(secrets(), reply = okReply),
        knowledge: KnowledgeApi = NoopKnowledge,
    ): Fixture {
        // Geen projecten geconfigureerd => geen workspace-lagen en geen git-activiteit in de test.
        val resolver = ProjectConfiguration(emptyMap())
        val secrets = secrets()
        val client = RecordingTelegramClient(secrets)
        val threads = InMemoryThreadStore()
        val workspace = AssistantWorkspaceService(NoopGitApi, secrets, resolver)
        val service = TelegramAssistantService(claude, threads, client, resolver, workspace, knowledge)
        return Fixture(service, claude, client, threads)
    }

    /** Fake voor de Claude-kant: geen Docker/proces, alleen registratie van ask/stop. */
    private class FakeClaude(
        secrets: FactorySecrets,
        private val reply: AssistantReply = AssistantReply("antwoord van claude", isError = false, sessionId = null, costUsd = 0.0),
        private val stopResult: Boolean = true,
        private val enabledResult: Boolean = true,
    ) : ClaudeAssistantClient(secrets) {
        data class Ask(val chatId: String, val sessionId: String, val isResume: Boolean, val userMessage: String)
        val asks = mutableListOf<Ask>()
        val stops = mutableListOf<String>()

        override val enabled: Boolean get() = enabledResult

        override fun ask(
            chatId: String,
            sessionId: String,
            isResume: Boolean,
            systemPrompt: String,
            userMessage: String,
            extraMounts: List<String>,
            extraEnv: Map<String, String>,
            timeoutSecondsOverride: Long?,
        ): AssistantReply {
            asks += Ask(chatId, sessionId, isResume, userMessage)
            // Net als de echte claude: geef de daadwerkelijk gebruikte sessie-id terug.
            return reply.copy(sessionId = reply.sessionId ?: sessionId)
        }

        override fun stop(sessionId: String): Boolean {
            stops += sessionId
            return stopResult
        }

        override fun outputImages(chatId: String, sessionId: String): List<Path> = emptyList()
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
    }

    private object NoopKnowledge : KnowledgeApi {
        override fun find(targetRepo: String, role: String): List<AgentKnowledgeEntry> = emptyList()
        override fun upsert(request: AgentKnowledgeUpdateRequest): AgentKnowledgeEntry =
            throw UnsupportedOperationException()
    }

    private object NoopGitApi : GitApi {
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
}

private fun secrets() = FactorySecrets(
    trackerProjects = emptyList(),
    githubToken = "gh",
    factoryDatabaseUrl = "jdbc:postgresql://localhost/test",
    factoryDatabaseSchema = "public",
    kubeconfig = null,
    aiCredentialsDir = null,
    aiOauthToken = "oauth-token",
    loadedFrom = "test",
)
