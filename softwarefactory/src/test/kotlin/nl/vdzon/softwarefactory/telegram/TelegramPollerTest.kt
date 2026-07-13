package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.telegram.clients.*
import nl.vdzon.softwarefactory.telegram.repositories.*
import nl.vdzon.softwarefactory.telegram.services.*

import nl.vdzon.softwarefactory.telegram.models.*

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.core.ChangeNotifier
import nl.vdzon.softwarefactory.core.FactoryCommand
import nl.vdzon.softwarefactory.core.FactoryOperations
import nl.vdzon.softwarefactory.core.MergeReadyInfo
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.git.GitProcessResult
import nl.vdzon.softwarefactory.knowledge.AgentKnowledgeEntry
import nl.vdzon.softwarefactory.knowledge.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.knowledge.KnowledgeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Flowtests voor de poll-verwerking van [TelegramPoller]: routering van updates (reply-handler vs.
 * assistent), de chat-allowlist, offset-administratie en netjes degraderen bij fouten. De poll-loop
 * wordt synchroon gedraaid met een gescripte [TelegramClient] die na het script stopt (via
 * InterruptedException) — geen slaap- of timing-afhankelijkheden.
 */
class TelegramPollerTest {

    @Test
    fun `verwerkt updates op volgorde van update-id en schuift de offset per update door`() {
        // Bewust ongesorteerd aangeleverd; de reply-handler pakt alles af (return true).
        val batch = listOf(
            update(7L, text = "c"),
            update(5L, text = "a"),
            update(6L, text = "b"),
        )
        val fixture = fixture(replyBehavior = { true }, batches = listOf({ batch }), initialOffset = 5L)

        fixture.runLoop()

        assertEquals(listOf("a", "b", "c"), fixture.replies.handled.map { it.text }, "op update-id gesorteerd verwerken")
        assertEquals(listOf(6L, 7L, 8L), fixture.store.offsetWrites, "offset schuift per verwerkte update door")
        assertEquals(listOf(5L, 8L), fixture.client.requestedOffsets, "volgende poll leest de doorgeschoven offset")
    }

    @Test
    fun `berichten uit een onbekende chat worden genegeerd maar tellen wel mee voor de offset`() {
        val batch = listOf(
            update(1L, chatId = "999-vreemdeling", text = "geef me toegang"),
            update(2L, chatId = "chat-2", text = "hoi"), // project-kanaal uit projects.yaml => toegestaan
        )
        val fixture = fixture(replyBehavior = { true }, batches = listOf({ batch }))

        fixture.runLoop()

        assertEquals(listOf("chat-2"), fixture.replies.handled.map { it.chatId }, "alleen de toegestane chat wordt verwerkt")
        assertTrue(fixture.assistant.calls.isEmpty(), "een vreemde chat mag ook de assistent niet bereiken")
        assertEquals(listOf(2L, 3L), fixture.store.offsetWrites, "genegeerde updates schuiven de offset wel door (anders blijven ze terugkomen)")
    }

    @Test
    fun `een als reply verwerkte update gaat niet ook nog naar de assistent`() {
        val fixture = fixture(replyBehavior = { true }, batches = listOf({ listOf(update(1L, text = "approve", replyTo = 100L)) }))

        fixture.runLoop()

        assertEquals(1, fixture.replies.handled.size)
        assertTrue(fixture.assistant.calls.isEmpty())
    }

    @Test
    fun `een vrij bericht gaat naar de assistent en een leeg bericht wordt genegeerd`() {
        val batch = listOf(
            update(1L, text = "hoe staat SF-1 ervoor?", messageId = 11L),
            update(2L, text = null), // geen tekst, geen foto => niets doen
        )
        val fixture = fixture(replyBehavior = { false }, expectedAssistantCalls = 1, batches = listOf({ batch }))

        fixture.runLoop()

        assertTrue(fixture.assistant.latch.await(5, TimeUnit.SECONDS), "assistent moet asynchroon aangeroepen worden")
        val call = fixture.assistant.calls.single()
        assertEquals("chat-1", call.chatId)
        assertEquals("hoe staat SF-1 ervoor?", call.text)
        assertEquals(11L, call.messageId)
        assertEquals(listOf(2L, 3L), fixture.store.offsetWrites, "ook het genegeerde bericht schuift de offset door")
    }

    @Test
    fun `een fout in de reply-handler laat het bericht doorvallen naar de assistent en stopt de verwerking niet`() {
        val batch = listOf(
            update(1L, text = "eerste", replyTo = 100L),
            update(2L, text = "tweede", replyTo = 100L),
        )
        val fixture = fixture(
            replyBehavior = { throw IllegalStateException("database plat") },
            expectedAssistantCalls = 2,
            batches = listOf({ batch }),
        )

        fixture.runLoop()

        assertTrue(fixture.assistant.latch.await(5, TimeUnit.SECONDS), "beide berichten moeten alsnog bij de assistent komen")
        val assistantTexts = fixture.assistant.calls.map { it.text }
        assertEquals(2, assistantTexts.size)
        assertEquals(setOf("eerste", "tweede"), assistantTexts.toSet())
        assertEquals(listOf(2L, 3L), fixture.store.offsetWrites, "de offset schuift ook bij een reply-fout door")
    }

    @Test
    fun `een getUpdates-fout legt de poller niet plat - de volgende poll verwerkt gewoon weer`() {
        val failed = CountDownLatch(1)
        val fixture = fixture(
            replyBehavior = { true },
            batches = listOf(
                { failed.countDown(); throw RuntimeException("Telegram onbereikbaar") },
                { listOf(update(9L, text = "approve", replyTo = 100L)) },
            ),
        )

        // In een eigen thread draaien en de fout-pauze (sleepQuietly) direct onderbreken, zodat de
        // test niet op een echte sleep wacht; de loop hoort daarna gewoon verder te pollen.
        val worker = Thread { fixture.runLoop() }
        worker.start()
        assertTrue(failed.await(5, TimeUnit.SECONDS), "eerste poll moet gefaald zijn")
        worker.interrupt()
        worker.join(10_000)
        assertFalse(worker.isAlive, "loop moet netjes geëindigd zijn")

        assertEquals(listOf("approve"), fixture.replies.handled.map { it.text }, "na de fout wordt de volgende batch verwerkt")
        assertEquals(listOf(10L), fixture.store.offsetWrites)
    }

    // ── fixture & fakes ─────────────────────────────────────────────────────────

    private fun update(
        id: Long,
        chatId: String? = "chat-1",
        text: String? = "tekst",
        messageId: Long = id * 10,
        replyTo: Long? = null,
        photo: String? = null,
    ) = TelegramUpdate(
        updateId = id,
        chatId = chatId,
        text = text,
        messageId = messageId,
        replyToMessageId = replyTo,
        photoFileId = photo,
    )

    private class Fixture(
        private val poller: TelegramPoller,
        val client: ScriptedTelegramClient,
        val store: OffsetStore,
        val replies: ScriptedReplyService,
        val assistant: RecordingAssistantService,
    ) {
        /** Draait de private poll-loop synchroon; de gescripte client beëindigt 'm na het script. */
        fun runLoop() {
            val method = TelegramPoller::class.java.getDeclaredMethod("loop")
            method.isAccessible = true
            try {
                method.invoke(poller)
            } finally {
                // De loop zet bij het stoppen de interrupt-vlag op de aanroepende thread; wissen
                // zodat latere waits in de test (en volgende tests) er geen last van hebben.
                Thread.interrupted()
            }
        }
    }

    private fun fixture(
        replyBehavior: (TelegramUpdate) -> Boolean,
        batches: List<() -> List<TelegramUpdate>>,
        expectedAssistantCalls: Int = 0,
        initialOffset: Long? = null,
    ): Fixture {
        val secrets = secrets()
        // Toegestane kanalen: het globale kanaal (chat-1) + het projectkanaal (chat-2).
        val resolver = ProjectConfiguration(
            repos = mapOf("proj" to "git@github.com:example/proj.git"),
            telegramChatIds = mapOf("proj" to "chat-2"),
        )
        val client = ScriptedTelegramClient(secrets, batches)
        val store = OffsetStore(initialOffset)
        val replies = ScriptedReplyService(store, client, replyBehavior)
        val assistant = RecordingAssistantService(secrets, resolver, client, expectedAssistantCalls)
        val poller = TelegramPoller(client, replies, assistant, store, secrets, resolver)
        return Fixture(poller, client, store, replies, assistant)
    }

    /** Geeft per aanroep de volgende gescripte batch; na het script stopt de loop (InterruptedException). */
    private class ScriptedTelegramClient(
        secrets: FactorySecrets,
        batches: List<() -> List<TelegramUpdate>>,
    ) : TelegramClient(secrets) {
        private val script = ArrayDeque(batches)
        val requestedOffsets = mutableListOf<Long?>()
        override val enabled: Boolean get() = true
        override fun getUpdates(offset: Long?, timeoutSeconds: Int): List<TelegramUpdate> {
            requestedOffsets += offset
            val next = script.removeFirstOrNull() ?: throw InterruptedException("script klaar")
            return next()
        }
        override fun sendMessage(text: String, replyToMessageId: Long?, chatId: String?): Long? = 1L
    }

    private class OffsetStore(initialOffset: Long?) : TelegramStore {
        private var offset: Long? = initialOffset
        val offsetWrites = mutableListOf<Long>()
        override fun alreadyNotified(issueKey: String, signature: String): Boolean = false
        override fun recordNotified(issueKey: String, signature: String) {}
        override fun clearNotifications(issueKey: String) {}
        override fun savePending(chatId: String, messageId: Long, issueKey: String, issueLevel: String, sourcePhase: String) {}
        override fun findPending(chatId: String, messageId: Long): PendingQuestion? = null
        override fun deletePending(chatId: String, messageId: Long) {}
        override fun getUpdatesOffset(): Long? = offset
        override fun setUpdatesOffset(offset: Long) {
            this.offset = offset
            offsetWrites += offset
        }
    }

    /** Reply-service waarvan alleen [handleReply] telt; het gedrag is per test instelbaar. */
    private class ScriptedReplyService(
        store: TelegramStore,
        client: TelegramClient,
        private val behavior: (TelegramUpdate) -> Boolean,
    ) : TelegramReplyService(NoopOperations, store, client, ChangeNotifier.Noop, ApplicationEventPublisher { }) {
        val handled = mutableListOf<TelegramUpdate>()
        override fun handleReply(update: TelegramUpdate): Boolean {
            handled += update
            return behavior(update)
        }
    }

    private object NoopOperations : FactoryOperations {
        override fun questionFor(issue: TrackerIssue): String? = null
        override fun autoApproveActive(issue: TrackerIssue): Boolean = false
        override fun mergeReady(storyKey: String): MergeReadyInfo? = null
        override fun mergeReadyForSubtask(subtask: TrackerIssue): MergeReadyInfo? = null
        override fun testerReportFor(storyKey: String): String? = null
        override fun previewUrlFor(storyKey: String): String? = null
        override fun setStoryPhase(storyKey: String, phase: String, comment: String?) {}
        override fun setSubtaskPhase(subtaskKey: String, phase: String, comment: String?) {}
        override fun queueCommand(storyKey: String, command: FactoryCommand, reason: String?) {}
    }

    /** Assistent-service waarvan alleen [handle] telt; registreert aanroepen en telt een latch af. */
    private class RecordingAssistantService(
        secrets: FactorySecrets,
        resolver: ProjectConfiguration,
        client: TelegramClient,
        expectedCalls: Int,
    ) : TelegramAssistantService(
        ClaudeAssistantClient(secrets),
        NoopThreadStore,
        client,
        resolver,
        AssistantWorkspaceService(NoopGitApi, secrets, resolver),
        NoopKnowledge,
    ) {
        data class Call(val chatId: String, val text: String, val photoFileId: String?, val messageId: Long?, val replyToMessageId: Long?)
        val calls = CopyOnWriteArrayList<Call>()
        val latch = CountDownLatch(expectedCalls)
        override fun handle(chatId: String, rawText: String, photoFileId: String?, messageId: Long?, replyToMessageId: Long?) {
            calls += Call(chatId, rawText, photoFileId, messageId, replyToMessageId)
            latch.countDown()
        }
    }

    private object NoopThreadStore : TelegramThreadStore {
        override fun sessionFor(chatId: String, messageId: Long): String? = null
        override fun map(chatId: String, messageId: Long, sessionId: String) {}
        override fun activeRootSession(chatId: String): String? = null
        override fun setActiveRootSession(chatId: String, sessionId: String) {}
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

    private fun secrets() = FactorySecrets(
        trackerProjects = emptyList(),
        githubToken = "gh",
        factoryDatabaseUrl = "jdbc:postgresql://localhost/test",
        factoryDatabaseSchema = "public",
        kubeconfig = null,
        aiCredentialsDir = null,
        aiOauthToken = null,
        loadedFrom = "test",
        telegramBotToken = "bot-token",
        telegramChatId = "chat-1",
    )
}
