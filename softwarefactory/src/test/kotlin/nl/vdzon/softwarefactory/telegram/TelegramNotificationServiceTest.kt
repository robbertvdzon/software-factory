package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.FactoryCommand
import nl.vdzon.softwarefactory.core.IssueProcessResult
import nl.vdzon.softwarefactory.core.OrchestratorPollResult
import nl.vdzon.softwarefactory.core.StoryPhase
import nl.vdzon.softwarefactory.core.SubtaskPhase
import nl.vdzon.softwarefactory.core.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerIssueFields
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.web.repositories.FactoryDashboardRepository
import nl.vdzon.softwarefactory.web.services.FactoryDashboardService
import nl.vdzon.softwarefactory.web.services.FactoryVersionService
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Unit-tests voor de auto-approve voortgangsmeldingen (SF-181). Dekt AC1-AC6: de nieuwe PROGRESS-
 * meldingen, de subtaak-'klaar' met overzicht + merge-actie, regressie bij auto-approve UIT en
 * idempotentie. De Telegram-client en dashboard-service worden afgevangen via testdoubles.
 */
class TelegramNotificationServiceTest {

    // ── tests ───────────────────────────────────────────────────────────────────

    @Test
    fun `AC1 - refining klaar stuurt een PROGRESS-melding met gepromote description`() {
        val story = story("SF-1", "Een mooie story", StoryPhase.PLANNING, autoApprove = true, description = "De gepromote beschrijving.")
        val fixture = fixture(issues = listOf(story))

        fixture.service.notifyPending()

        val message = fixture.client.single()
        assertTrue(message.contains("ℹ️ Refining klaar, begint met plannen"), message)
        assertTrue(message.contains("SF-1: Een mooie story"), message)
        assertTrue(message.contains("De gepromote beschrijving."), message)
        assertFalse(message.contains("✅ Klaar"), message)
    }

    @Test
    fun `AC1 - description wordt afgekapt op 1200 tekens`() {
        val long = "x".repeat(2000)
        val story = story("SF-1", "Story", StoryPhase.PLANNING, autoApprove = true, description = long)
        val fixture = fixture(issues = listOf(story))

        fixture.service.notifyPending()

        assertTrue(fixture.client.single().contains("x".repeat(1200)))
        assertFalse(fixture.client.single().contains("x".repeat(1201)))
    }

    @Test
    fun `AC1 - geen melding bij PLANNING zonder auto-approve`() {
        val story = story("SF-1", "Story", StoryPhase.PLANNING, autoApprove = false, description = "iets")
        val fixture = fixture(issues = listOf(story))

        fixture.service.notifyPending()

        assertTrue(fixture.client.messages.isEmpty(), "Zonder auto-approve geen PROGRESS-melding")
    }

    @Test
    fun `AC2 - planning klaar stuurt PROGRESS met subtaak-overzicht`() {
        val story = story("SF-1", "Story", StoryPhase.PLANNING_APPROVED, autoApprove = true)
        val sub1 = subtask("SF-2", "Bouwen", SubtaskPhase.REVIEW_APPROVED)
        val sub2 = subtask("SF-3", "Testen", SubtaskPhase.TESTING)
        val fixture = fixture(issues = listOf(story), subtasks = mapOf("SF-1" to listOf(sub1, sub2)))

        fixture.service.notifyPending()

        val message = fixture.client.single()
        assertTrue(message.contains("ℹ️ Planning klaar, begint met uitvoeren"), message)
        assertTrue(message.contains("SF-1 Story"), message)
        assertTrue(message.contains("[X] SF-2 Bouwen"), message)
        assertTrue(message.contains("[ ] SF-3 Testen"), message)
        assertFalse(message.contains("✅ Klaar"), message)
    }

    @Test
    fun `AC5 - planning klaar zonder auto-approve blijft een DONE-melding`() {
        val story = story("SF-1", "Story", StoryPhase.PLANNING_APPROVED, autoApprove = false)
        val fixture = fixture(issues = listOf(story))

        fixture.service.notifyPending()

        val message = fixture.client.single()
        assertTrue(message.contains("✅ Klaar"), message)
        assertFalse(message.contains("Planning klaar"), message)
    }

    @Test
    fun `AC3 - afgeronde subtaak stuurt klaar-melding met story-overzicht`() {
        val sub1 = subtask("SF-2", "Bouwen", SubtaskPhase.REVIEW_APPROVED, autoApprove = true)
        val sub2 = subtask("SF-3", "Testen", SubtaskPhase.TESTING)
        val story = story("SF-1", "Story", StoryPhase.IN_PROGRESS, autoApprove = true)
        val fixture = fixture(
            issues = listOf(sub1),
            parents = mapOf("SF-2" to "SF-1"),
            getIssues = mapOf("SF-1" to story),
            subtasks = mapOf("SF-1" to listOf(sub1, sub2)),
        )

        fixture.service.notifyPending()

        val message = fixture.client.single()
        assertTrue(message.contains("✅ Klaar"), message)
        assertTrue(message.contains("SF-1 Story"), message)
        assertTrue(message.contains("[X] SF-2 Bouwen"), message)
        assertTrue(message.contains("[ ] SF-3 Testen"), message)
        assertFalse(message.contains("Story helemaal afgerond"), message)
        assertTrue(fixture.store.pending.isEmpty(), "Geen merge-reply zonder afgeronde story")
    }

    @Test
    fun `AC3 - alle subtaken terminaal voegt afrond-regel toe`() {
        val sub1 = subtask("SF-2", "Bouwen", SubtaskPhase.REVIEW_APPROVED, autoApprove = true)
        val sub2 = subtask("SF-3", "Testen", SubtaskPhase.TEST_APPROVED)
        val story = story("SF-1", "Story", StoryPhase.IN_PROGRESS, autoApprove = true)
        val fixture = fixture(
            issues = listOf(sub1),
            parents = mapOf("SF-2" to "SF-1"),
            getIssues = mapOf("SF-1" to story),
            subtasks = mapOf("SF-1" to listOf(sub1, sub2)),
            // geen mergeReady -> geen merge-aanbod
        )

        fixture.service.notifyPending()

        val message = fixture.client.single()
        assertTrue(message.contains("Story helemaal afgerond! 🎉"), message)
        assertFalse(message.contains("Reply \"merge\""), message)
    }

    @Test
    fun `AC4 - laatste subtaak met PR biedt een merge-actie aan`() {
        val sub1 = subtask("SF-2", "Bouwen", SubtaskPhase.REVIEW_APPROVED, autoApprove = true)
        val sub2 = subtask("SF-3", "Testen", SubtaskPhase.TEST_APPROVED)
        val story = story("SF-1", "Story", StoryPhase.IN_PROGRESS, autoApprove = true)
        val merge = FactoryDashboardService.MergeReadyInfo("SF-1", 42, "https://pr/42")
        val fixture = fixture(
            issues = listOf(sub1),
            parents = mapOf("SF-2" to "SF-1"),
            getIssues = mapOf("SF-1" to story),
            subtasks = mapOf("SF-1" to listOf(sub1, sub2)),
            mergeReady = mapOf("SF-1" to merge),
        )

        fixture.service.notifyPending()

        val message = fixture.client.single()
        assertTrue(message.contains("Story helemaal afgerond! 🎉"), message)
        assertTrue(message.contains("↩️ Reply \"merge\" om de PR naar main te mergen (squash)."), message)
        val pending = fixture.store.pending.single()
        assertEquals("SF-1", pending.issueKey)
        assertEquals("STORY", pending.level)
        assertEquals(MERGE_READY_PHASE, pending.sourcePhase)
    }

    @Test
    fun `AC5 - afgeronde subtaak zonder auto-approve gebruikt de bestaande logica`() {
        val sub = subtask("SF-2", "Bouwen", SubtaskPhase.REVIEW_APPROVED, autoApprove = false)
        val fixture = fixture(
            issues = listOf(sub),
            parents = mapOf("SF-2" to "SF-1"),
            // geen mergeReady -> tryNotifyMergeReady geeft false -> reguliere DONE-melding
        )

        fixture.service.notifyPending()

        val message = fixture.client.single()
        assertTrue(message.contains("✅ Klaar"), message)
        assertFalse(message.contains("[X]"), message)
        assertTrue(fixture.store.pending.isEmpty())
    }

    @Test
    fun `AC6 - dezelfde toestand levert hooguit een melding (idempotent)`() {
        val story = story("SF-1", "Story", StoryPhase.PLANNING, autoApprove = true, description = "beschrijving")
        val fixture = fixture(issues = listOf(story))

        fixture.service.notifyPending()
        fixture.service.notifyPending()

        assertEquals(1, fixture.client.messages.size, "Tweede poll mag niet opnieuw melden")
    }

    // ── fixture & doubles ───────────────────────────────────────────────────────

    private class Fixture(
        val service: TelegramNotificationService,
        val client: RecordingTelegramClient,
        val store: FakeStore,
    )

    private fun fixture(
        issues: List<TrackerIssue>,
        parents: Map<String, String> = emptyMap(),
        getIssues: Map<String, TrackerIssue> = emptyMap(),
        subtasks: Map<String, List<TrackerIssue>> = emptyMap(),
        mergeReady: Map<String, FactoryDashboardService.MergeReadyInfo> = emptyMap(),
    ): Fixture {
        val secrets = secrets()
        val tracker = FakeTracker(issues, parents, getIssues, subtasks)
        val dashboard = FakeDashboard(secrets, tracker, mergeReady)
        val client = RecordingTelegramClient(secrets)
        val store = FakeStore()
        val service = TelegramNotificationService(
            issueTrackerClient = tracker,
            dashboardService = dashboard,
            telegramClient = client,
            store = store,
            secrets = secrets,
            projectRepoResolver = ProjectRepoResolver(emptyMap()),
        )
        return Fixture(service, client, store)
    }

    private class FakeTracker(
        private val issues: List<TrackerIssue>,
        private val parents: Map<String, String>,
        private val getIssues: Map<String, TrackerIssue>,
        private val subtasks: Map<String, List<TrackerIssue>>,
    ) : YouTrackApi {
        override fun findWorkIssues(maxResults: Int): List<TrackerIssue> = issues
        override fun getIssue(issueKey: String): TrackerIssue =
            getIssues[issueKey] ?: error("geen issue voor $issueKey")
        override fun parentStoryKey(subtaskKey: String): String? = parents[subtaskKey]
        override fun subtasksOf(parentKey: String): List<TrackerIssue> = subtasks[parentKey] ?: emptyList()
        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) =
            error("ongebruikt: updateIssueFields")
        override fun transitionIssue(issueKey: String, statusName: String) =
            error("ongebruikt: transitionIssue")
        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment =
            error("ongebruikt: postAgentComment")
    }

    /** Alleen [mergeReady] wordt overschreven; auto-approve leunt op de echte logica + [FakeTracker]. */
    private class FakeDashboard(
        secrets: FactorySecrets,
        tracker: YouTrackApi,
        private val mergeReadyByKey: Map<String, FactoryDashboardService.MergeReadyInfo>,
    ) : FactoryDashboardService(
        issueTrackerClient = tracker,
        orchestratorApi = StubOrchestrator,
        repository = FactoryDashboardRepository(JdbcTemplate(), secrets),
        factorySecrets = secrets,
        previewApi = StubPreview,
        projectRepoResolver = ProjectRepoResolver(emptyMap()),
        versionService = FactoryVersionService(),
    ) {
        override fun mergeReady(storyKey: String): MergeReadyInfo? = mergeReadyByKey[storyKey]
    }

    private object StubOrchestrator : OrchestratorApi {
        override fun pollOnce(projectKey: String): OrchestratorPollResult = error("ongebruikt")
        override fun processIssue(issue: TrackerIssue): IssueProcessResult = error("ongebruikt")
        override fun queueCommand(storyKey: String, command: FactoryCommand) = error("ongebruikt")
    }

    private object StubPreview : PreviewApi {
        override fun render(template: String?, prNumber: Int?): String? = null
        override fun cleanup(namespace: String): Boolean = true
    }

    private class RecordingTelegramClient(secrets: FactorySecrets) : TelegramClient(secrets) {
        val messages = mutableListOf<String>()
        private var counter = 0L
        override val enabled: Boolean get() = true
        override val defaultChatId: String get() = "chat-default"
        override fun sendMessage(text: String, replyToMessageId: Long?, chatId: String?): Long {
            messages += text
            return ++counter
        }

        fun single(): String {
            assertEquals(1, messages.size, "verwacht precies 1 bericht, was ${messages.size}")
            return messages.first()
        }
    }

    private data class PendingRecord(val issueKey: String, val level: String, val sourcePhase: String)

    private class FakeStore : TelegramStore {
        private val notified = mutableSetOf<String>()
        val pending = mutableListOf<PendingRecord>()
        override fun alreadyNotified(issueKey: String, signature: String): Boolean = "$issueKey|$signature" in notified
        override fun recordNotified(issueKey: String, signature: String) { notified += "$issueKey|$signature" }
        override fun clearNotifications(issueKey: String) { notified.removeIf { it.startsWith("$issueKey|") } }
        override fun savePending(chatId: String, messageId: Long, issueKey: String, issueLevel: String, sourcePhase: String) {
            pending += PendingRecord(issueKey, issueLevel, sourcePhase)
        }
        override fun findPending(chatId: String, messageId: Long): PendingQuestion? = null
        override fun deletePending(chatId: String, messageId: Long) {}
        override fun getUpdatesOffset(): Long? = null
        override fun setUpdatesOffset(offset: Long) {}
    }

    // ── builders ──────────────────────────────────────────────────────────────

    private fun story(
        key: String,
        summary: String,
        phase: StoryPhase,
        autoApprove: Boolean,
        description: String? = null,
    ) = TrackerIssue(
        key = key,
        summary = summary,
        description = description,
        status = "open",
        fields = fields(autoApprove = autoApprove, storyPhase = phase.trackerValue),
        comments = emptyList(),
    )

    private fun subtask(
        key: String,
        summary: String,
        phase: SubtaskPhase,
        autoApprove: Boolean = false,
        subtaskType: String = "development",
    ) = TrackerIssue(
        key = key,
        summary = summary,
        description = null,
        status = "open",
        fields = fields(
            autoApprove = autoApprove,
            subtaskPhase = phase.trackerValue,
            type = "Task",
            subtaskType = subtaskType,
        ),
        comments = emptyList(),
    )

    private fun fields(
        autoApprove: Boolean = false,
        storyPhase: String? = null,
        subtaskPhase: String? = null,
        type: String? = null,
        subtaskType: String? = null,
    ) = TrackerIssueFields(
        targetRepo = null,
        repo = null,
        aiSupplier = null,
        autoApprove = autoApprove,
        aiPhase = null,
        aiLevel = null,
        aiTokenBudget = null,
        aiTokensUsed = null,
        agentStartedAt = null,
        paused = false,
        error = null,
        type = type,
        subtaskType = subtaskType,
        storyPhase = storyPhase,
        subtaskPhase = subtaskPhase,
    )

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
