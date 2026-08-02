package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.config.DeployConfig
import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.ApkReleaseInfo
import nl.vdzon.softwarefactory.core.contracts.ApkReleaseProbe
import nl.vdzon.softwarefactory.core.contracts.FactoryCommand
import nl.vdzon.softwarefactory.core.contracts.FactoryOperations
import nl.vdzon.softwarefactory.core.contracts.MergeReadyInfo
import nl.vdzon.softwarefactory.core.contracts.NotifyMode
import nl.vdzon.softwarefactory.core.contracts.SubtaskPhase
import nl.vdzon.softwarefactory.core.contracts.TrackerComment
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.contracts.TrackerIssueFields
import nl.vdzon.softwarefactory.telegram.services.TelegramResultNotifyPoller
import nl.vdzon.softwarefactory.telegram.clients.TelegramClient
import nl.vdzon.softwarefactory.telegram.repositories.PendingQuestion
import nl.vdzon.softwarefactory.telegram.repositories.TelegramStore
import nl.vdzon.softwarefactory.tracker.TrackerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * SF-1134: dekt de poller-beslislogica (skip-fast zonder kandidaten, idempotentie, opgeef-timeout,
 * rest-restart-/DEPLOY_FAILED-afhandeling) zonder echte HTTP-/GitHub-calls — analoog aan hoe
 * [nl.vdzon.softwarefactory.telegram.TelegramNotificationServiceTest] [TelegramClient] subclasst
 * i.p.v. mockt (kotlin-spring all-open maakt @Component-klassen open).
 */
class TelegramResultNotifyPollerTest {

    private val storyKey = "SF-1"
    private val subtaskKey = "SF-2"
    private val now = OffsetDateTime.parse("2026-01-01T12:00:00Z")
    private val clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC)

    // SF-1261 — `telegramResultNotify` blijft de testhelper-parameternaam; vertaalt nu naar
    // notify_mode=als-klaar-en-gedeployed (de nieuwe activatievoorwaarde van deze poller).
    private fun story(
        telegramResultNotify: Boolean,
        repo: String = "softwarefactory",
        description: String? = null,
    ) = TrackerIssue(
        key = storyKey,
        summary = "Een story",
        description = description,
        status = "Open",
        fields = TrackerIssueFields(
            targetRepo = null,
            repo = repo,
            aiSupplier = "claude",
            aiPhase = null,
            aiLevel = null,
            aiTokenBudget = null,
            aiTokensUsed = null,
            agentStartedAt = null,
            paused = false,
            notifyMode = if (telegramResultNotify) NotifyMode.WHEN_DONE_AND_DEPLOYED.trackerValue else NotifyMode.EVERY_STEP.trackerValue,
            error = null,
            type = "User Story",
        ),
        comments = emptyList(),
    )

    private fun deploySubtask(phase: SubtaskPhase?, agentStartedAt: OffsetDateTime?) = TrackerIssue(
        key = subtaskKey,
        summary = "Deploy",
        status = "Open",
        fields = TrackerIssueFields(
            targetRepo = null,
            aiPhase = null,
            aiLevel = null,
            aiTokenBudget = null,
            aiTokensUsed = null,
            agentStartedAt = agentStartedAt,
            paused = false,
            error = null,
            type = "Task",
            subtaskType = "deploy",
            subtaskPhase = phase?.trackerValue,
        ),
        comments = emptyList(),
        parentKey = storyKey,
    )

    private fun poller(
        issues: List<TrackerIssue>,
        subtasks: List<TrackerIssue>,
        deployConfig: DeployConfig,
        store: FakeStore = FakeStore(),
        client: RecordingTelegramClient = RecordingTelegramClient(secrets()),
        apkReleaseProbe: ApkReleaseProbe = ApkReleaseProbe { _, _, _ -> null },
        factoryOperations: FactoryOperations = FakeOperations(),
    ): Triple<TelegramResultNotifyPoller, RecordingTelegramClient, FakeStore> {
        val tracker = FakeTracker(issues, subtasks)
        val resolver = ProjectConfiguration(
            mapOf("softwarefactory" to "https://github.com/robbert/sf.git"),
            deployConfigs = mapOf("softwarefactory" to deployConfig),
        )
        val poller = TelegramResultNotifyPoller(
            issueTrackerClient = tracker,
            deploySettings = resolver,
            repositoryCatalog = resolver,
            telegramSettings = resolver,
            apkReleaseProbe = apkReleaseProbe,
            factoryOperations = factoryOperations,
            telegramClient = client,
            store = store,
            clock = clock,
        )
        return Triple(poller, client, store)
    }

    @Test
    fun `geen kandidaten -- poll doet geen subtasksOf-call (skip-fast)`() {
        val tracker = ThrowingSubtasksTracker(listOf(story(telegramResultNotify = false)))
        val resolver = ProjectConfiguration(emptyMap())
        val poller = TelegramResultNotifyPoller(
            issueTrackerClient = tracker,
            deploySettings = resolver,
            repositoryCatalog = resolver,
            telegramSettings = resolver,
            apkReleaseProbe = ApkReleaseProbe { _, _, _ -> error("apkReleaseProbe mag hier niet aangeroepen worden") },
            factoryOperations = FakeOperations(),
            telegramClient = RecordingTelegramClient(secrets()),
            store = FakeStore(),
            clock = clock,
        )

        poller.poll()
        // Geen exceptie -> subtasksOf() is niet aangeroepen voor de niet-geflagde story.
    }

    @Test
    fun `poll vraagt findWorkIssues met includeFinished=true (een net op Done gezette story mag niet gemist worden)`() {
        // SubtaskExecutionCoordinator.advanceSubtaskChain zet de story vrijwel direct (event-gedreven)
        // op status Done zodra de DEPLOY-subtaak terminaal wordt; zonder includeFinished=true sluit
        // PostgresTrackerClient.findAiIssues zo'n story-rij uit en vuurt de melding nooit af.
        val tracker = FakeTracker(
            issues = listOf(story(telegramResultNotify = true)),
            subtasks = listOf(deploySubtask(SubtaskPhase.DEPLOY_APPROVED, agentStartedAt = now.minusMinutes(5))),
        )
        val resolver = ProjectConfiguration(
            mapOf("softwarefactory" to "https://github.com/robbert/sf.git"),
            deployConfigs = mapOf(
                "softwarefactory" to DeployConfig.RestRestart(
                    restartUrl = "http://example/restart",
                    versionUrl = "http://example/version",
                    tokenEnvVar = "TOKEN",
                    pollIntervalSeconds = 1,
                    timeoutMinutes = 1,
                ),
            ),
        )
        val poller = TelegramResultNotifyPoller(
            issueTrackerClient = tracker,
            deploySettings = resolver,
            repositoryCatalog = resolver,
            telegramSettings = resolver,
            apkReleaseProbe = ApkReleaseProbe { _, _, _ -> null },
            factoryOperations = FakeOperations(),
            telegramClient = RecordingTelegramClient(secrets()),
            store = FakeStore(),
            clock = clock,
        )

        poller.poll()

        assertEquals(true, tracker.lastIncludeFinished)
    }

    @Test
    fun `rest-restart deploy-approved stuurt precies een keer een melding`() {
        val (poller, client, store) = poller(
            issues = listOf(story(telegramResultNotify = true)),
            subtasks = listOf(deploySubtask(SubtaskPhase.DEPLOY_APPROVED, agentStartedAt = now.minusMinutes(5))),
            deployConfig = DeployConfig.RestRestart(
                restartUrl = "http://example/restart",
                versionUrl = "http://example/version",
                tokenEnvVar = "TOKEN",
                pollIntervalSeconds = 1,
                timeoutMinutes = 1,
            ),
        )

        poller.poll()
        poller.poll()

        assertEquals(1, client.messages.size)
        assertTrue(client.messages.single().contains(storyKey))
        assertTrue(store.alreadyNotified(storyKey, "result-notify"))
    }

    @Test
    fun `openshift-watch zonder liveUrl bevestigt direct na deploy-approved`() {
        val (poller, client, _) = poller(
            issues = listOf(story(telegramResultNotify = true)),
            subtasks = listOf(deploySubtask(SubtaskPhase.DEPLOY_APPROVED, agentStartedAt = now.minusMinutes(5))),
            deployConfig = DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "dep", timeoutMinutes = 20),
        )

        poller.poll()

        assertEquals(1, client.messages.size)
    }

    @Test
    fun `deploy nog niet gestart -- geen melding`() {
        val (poller, client, _) = poller(
            issues = listOf(story(telegramResultNotify = true)),
            subtasks = listOf(deploySubtask(null, agentStartedAt = null)),
            deployConfig = DeployConfig.RestRestart(
                restartUrl = "http://example/restart",
                versionUrl = "http://example/version",
                tokenEnvVar = "TOKEN",
                pollIntervalSeconds = 1,
                timeoutMinutes = 1,
            ),
        )

        poller.poll()

        assertEquals(0, client.messages.size)
    }

    @Test
    fun `deploy nog bezig (niet-terminaal) -- geen melding`() {
        val (poller, client, _) = poller(
            issues = listOf(story(telegramResultNotify = true)),
            subtasks = listOf(deploySubtask(SubtaskPhase.DEPLOYING, agentStartedAt = now.minusMinutes(1))),
            deployConfig = DeployConfig.RestRestart(
                restartUrl = "http://example/restart",
                versionUrl = "http://example/version",
                tokenEnvVar = "TOKEN",
                pollIntervalSeconds = 1,
                timeoutMinutes = 1,
            ),
        )

        poller.poll()

        assertEquals(0, client.messages.size)
    }

    @Test
    fun `deploy-failed -- geen melding maar wel als afgehandeld gemarkeerd`() {
        val (poller, client, store) = poller(
            issues = listOf(story(telegramResultNotify = true)),
            subtasks = listOf(deploySubtask(SubtaskPhase.DEPLOY_FAILED, agentStartedAt = now.minusMinutes(5))),
            deployConfig = DeployConfig.RestRestart(
                restartUrl = "http://example/restart",
                versionUrl = "http://example/version",
                tokenEnvVar = "TOKEN",
                pollIntervalSeconds = 1,
                timeoutMinutes = 1,
            ),
        )

        poller.poll()

        assertEquals(0, client.messages.size)
        assertTrue(store.alreadyNotified(storyKey, "result-notify"))
    }

    @Test
    fun `opgeef-timeout na 4 uur -- geen melding, wel gemarkeerd, geen extra GitHub-call nodig`() {
        val (poller, client, store) = poller(
            issues = listOf(story(telegramResultNotify = true)),
            // APK-project (Skip): DeploySubtaskHandler zet deploy-approved instant; de referentietijd
            // (agentStartedAt) ligt hier > 4 uur in het verleden, dus de poller geeft op zonder de
            // ApkReleaseProbe (GitHub) ooit aan te roepen.
            subtasks = listOf(deploySubtask(SubtaskPhase.DEPLOY_APPROVED, agentStartedAt = now.minusHours(5))),
            deployConfig = DeployConfig.Skip(),
            apkReleaseProbe = ApkReleaseProbe { _, _, _ -> error("apkReleaseProbe mag hier niet aangeroepen worden") },
        )

        poller.poll()

        assertEquals(0, client.messages.size)
        assertFalse(store.alreadyNotified(storyKey, "wrong-signature"))
        assertTrue(store.alreadyNotified(storyKey, "result-notify"))
    }

    @Test
    fun `apk-project meldt zodra een nieuwe release na de deploy-referentietijd verschijnt`() {
        val release = ApkReleaseInfo(downloadUrl = "https://example/app.apk", createdAt = now.minusMinutes(1))
        val (poller, client, _) = poller(
            issues = listOf(story(telegramResultNotify = true)),
            subtasks = listOf(deploySubtask(SubtaskPhase.DEPLOY_APPROVED, agentStartedAt = now.minusMinutes(10))),
            deployConfig = DeployConfig.Skip(),
            apkReleaseProbe = ApkReleaseProbe { _, _, _ -> release },
        )

        poller.poll()

        assertEquals(1, client.messages.size)
        assertTrue(client.messages.single().contains(release.downloadUrl))
    }

    @Test
    fun `apk-project zonder nieuwe release -- geen melding`() {
        val (poller, client, _) = poller(
            issues = listOf(story(telegramResultNotify = true)),
            subtasks = listOf(deploySubtask(SubtaskPhase.DEPLOY_APPROVED, agentStartedAt = now.minusMinutes(10))),
            deployConfig = DeployConfig.Skip(),
            apkReleaseProbe = ApkReleaseProbe { _, _, _ -> null },
        )

        poller.poll()

        assertEquals(0, client.messages.size)
    }

    @Test
    fun `story zonder vlag krijgt geen melding`() {
        val (poller, client, _) = poller(
            issues = listOf(story(telegramResultNotify = false)),
            subtasks = listOf(deploySubtask(SubtaskPhase.DEPLOY_APPROVED, agentStartedAt = now.minusMinutes(5))),
            deployConfig = DeployConfig.RestRestart(
                restartUrl = "http://example/restart",
                versionUrl = "http://example/version",
                tokenEnvVar = "TOKEN",
                pollIntervalSeconds = 1,
                timeoutMinutes = 1,
            ),
        )

        poller.poll()

        assertEquals(0, client.messages.size)
    }

    // ── SF-1830: kop + functionele samenvatting ─────────────────────────────

    @Test
    fun `SF-1830 - melding gebruikt het PO-blok van de summarizer`() {
        val (poller, client, _) = poller(
            issues = listOf(story(telegramResultNotify = true, description = "## Samenvatting\nUit de description.")),
            subtasks = listOf(deploySubtask(SubtaskPhase.DEPLOY_APPROVED, agentStartedAt = now.minusMinutes(5))),
            deployConfig = DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "dep", timeoutMinutes = 20),
            factoryOperations = FakeOperations(deploySummary = "Je kunt nu zien wanneer je story live staat."),
        )

        poller.poll()

        assertEquals(
            "🚀 Story SF-1 is deployed!\n\nJe kunt nu zien wanneer je story live staat.",
            client.messages.single(),
        )
    }

    @Test
    fun `SF-1830 - zonder PO-blok valt de melding terug op de Samenvatting uit de description`() {
        val description = """
            ## Samenvatting
            Je krijgt voortaan een leesbaar bericht.

            ## Scope
            Technisch geneuzel dat niet mee mag.
        """.trimIndent()
        val (poller, client, _) = poller(
            issues = listOf(story(telegramResultNotify = true, description = description)),
            subtasks = listOf(deploySubtask(SubtaskPhase.DEPLOY_APPROVED, agentStartedAt = now.minusMinutes(5))),
            deployConfig = DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "dep", timeoutMinutes = 20),
            factoryOperations = FakeOperations(deploySummary = null),
        )

        poller.poll()

        assertEquals(
            "🚀 Story SF-1 is deployed!\n\nJe krijgt voortaan een leesbaar bericht.",
            client.messages.single(),
        )
    }

    @Test
    fun `SF-1830 - zonder beide bronnen blijft alleen de kop en de URL over`() {
        val release = ApkReleaseInfo(downloadUrl = "https://example/app.apk", createdAt = now.minusMinutes(1))
        val (poller, client, _) = poller(
            issues = listOf(story(telegramResultNotify = true, description = "Geen samenvattingssectie.")),
            subtasks = listOf(deploySubtask(SubtaskPhase.DEPLOY_APPROVED, agentStartedAt = now.minusMinutes(10))),
            deployConfig = DeployConfig.Skip(),
            apkReleaseProbe = ApkReleaseProbe { _, _, _ -> release },
            factoryOperations = FakeOperations(deploySummary = null),
        )

        poller.poll()

        assertEquals("🚀 Story SF-1 is deployed!\n\nhttps://example/app.apk", client.messages.single())
    }

    @Test
    fun `SF-1830 - een gooiende samenvattingsbron blokkeert de melding niet`() {
        val (poller, client, store) = poller(
            issues = listOf(story(telegramResultNotify = true, description = "## Samenvatting\nUit de description.")),
            subtasks = listOf(deploySubtask(SubtaskPhase.DEPLOY_APPROVED, agentStartedAt = now.minusMinutes(5))),
            deployConfig = DeployConfig.OpenshiftWatch(namespace = "ns", deployment = "dep", timeoutMinutes = 20),
            factoryOperations = FakeOperations(deploySummaryFails = true),
        )

        poller.poll()

        assertEquals(
            "🚀 Story SF-1 is deployed!\n\nUit de description.",
            client.messages.single(),
        )
        assertTrue(store.alreadyNotified(storyKey, "result-notify"))
    }

    // ── fixtures & doubles ──────────────────────────────────────────────────

    private fun secrets() = FactorySecrets(
        trackerProjects = emptyList(),
        githubToken = "gh",
        factoryDatabaseUrl = "jdbc:postgresql://localhost/test",
        factoryDatabaseSchema = "public",
        kubeconfig = null,
        aiCredentialsDir = null,
        aiOauthToken = null,
        loadedFrom = "test",
    )

    private class FakeTracker(
        private val issues: List<TrackerIssue>,
        private val subtasks: List<TrackerIssue>,
    ) : TrackerApi {
        var lastIncludeFinished: Boolean? = null
            private set

        override fun findWorkIssues(maxResults: Int, includeFinished: Boolean): List<TrackerIssue> {
            lastIncludeFinished = includeFinished
            return issues
        }
        override fun getIssue(issueKey: String): TrackerIssue = issues.first { it.key == issueKey }
        override fun subtasksOf(parentKey: String): List<TrackerIssue> = subtasks.filter { it.parentKey == parentKey }
        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) = error("ongebruikt")
        override fun transitionIssue(issueKey: String, statusName: String) = error("ongebruikt")
        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment = error("ongebruikt")
    }

    /** subtasksOf() gooit expres — bewijst dat de poller 'm niet aanroept als er geen kandidaten zijn. */
    private class ThrowingSubtasksTracker(private val issues: List<TrackerIssue>) : TrackerApi {
        override fun findWorkIssues(maxResults: Int, includeFinished: Boolean): List<TrackerIssue> = issues
        override fun getIssue(issueKey: String): TrackerIssue = error("ongebruikt")
        override fun subtasksOf(parentKey: String): List<TrackerIssue> = error("subtasksOf mag hier niet aangeroepen worden")
        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) = error("ongebruikt")
        override fun transitionIssue(issueKey: String, statusName: String) = error("ongebruikt")
        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment = error("ongebruikt")
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
    }

    /** SF-1830: levert (of weigert) het functionele PO-blok van de summarizer. */
    private class FakeOperations(
        private val deploySummary: String? = null,
        private val deploySummaryFails: Boolean = false,
    ) : FactoryOperations {
        override fun questionFor(issue: TrackerIssue): String? = null
        override fun autoApproveActive(issue: TrackerIssue): Boolean = false
        override fun mergeReady(storyKey: String): MergeReadyInfo? = null
        override fun mergeReadyForSubtask(subtask: TrackerIssue): MergeReadyInfo? = null
        override fun testerReportFor(storyKey: String): String? = null
        override fun deploySummaryFor(storyKey: String): String? =
            if (deploySummaryFails) error("DB weg") else deploySummary
        override fun previewUrlFor(storyKey: String): String? = null
        override fun setStoryPhase(storyKey: String, phase: String, comment: String?) = error("ongebruikt")
        override fun setSubtaskPhase(subtaskKey: String, phase: String, comment: String?) = error("ongebruikt")
        override fun queueCommand(storyKey: String, command: FactoryCommand, reason: String?) = error("ongebruikt")
    }

    private class FakeStore : TelegramStore {
        private val notified = mutableSetOf<String>()
        override fun alreadyNotified(issueKey: String, signature: String): Boolean = "$issueKey|$signature" in notified
        override fun recordNotified(issueKey: String, signature: String) { notified += "$issueKey|$signature" }
        override fun clearNotifications(issueKey: String) { notified.removeIf { it.startsWith("$issueKey|") } }
        override fun savePending(chatId: String, messageId: Long, issueKey: String, issueLevel: String, sourcePhase: String) {}
        override fun findPending(chatId: String, messageId: Long): PendingQuestion? = null
        override fun deletePending(chatId: String, messageId: Long) {}
        override fun getUpdatesOffset(): Long? = null
        override fun setUpdatesOffset(offset: Long) {}
    }
}
