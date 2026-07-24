package nl.vdzon.softwarefactory.telegram

import nl.vdzon.softwarefactory.telegram.clients.*
import nl.vdzon.softwarefactory.telegram.repositories.*
import nl.vdzon.softwarefactory.telegram.services.*

import nl.vdzon.softwarefactory.telegram.models.*

import nl.vdzon.softwarefactory.config.FactorySecrets
import nl.vdzon.softwarefactory.config.ProjectConfiguration
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.ApprovalMode
import nl.vdzon.softwarefactory.core.contracts.FactoryCommand
import nl.vdzon.softwarefactory.core.contracts.IssueProcessResult
import nl.vdzon.softwarefactory.core.contracts.MergeReadyInfo
import nl.vdzon.softwarefactory.core.contracts.NotifyMode
import nl.vdzon.softwarefactory.core.contracts.OrchestratorPollResult
import nl.vdzon.softwarefactory.core.contracts.StoryPhase
import nl.vdzon.softwarefactory.core.contracts.SubtaskPhase
import nl.vdzon.softwarefactory.core.contracts.TrackerAttachment
import nl.vdzon.softwarefactory.core.contracts.TrackerComment
import nl.vdzon.softwarefactory.core.contracts.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.contracts.TrackerIssueFields
import nl.vdzon.softwarefactory.orchestrator.OrchestratorApi
import nl.vdzon.softwarefactory.preview.PreviewApi
import nl.vdzon.softwarefactory.dashboard.repositories.FactoryDashboardRepository
import nl.vdzon.softwarefactory.dashboard.services.FactoryOperationsService
import nl.vdzon.softwarefactory.tracker.TrackerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Path

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
        val merge = MergeReadyInfo("SF-1", 42, "https://pr/42")
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

    // ── SF-1179: deploy-timeout vult TrackerField.ERROR, dus geen stille DEPLOY_FAILED ──

    @Test
    fun `SF-1179 - deploy-timeout met gevuld ERROR-veld triggert een ERROR-melding`() {
        val sub = subtask(
            "SF-2",
            "Deploy",
            SubtaskPhase.DEPLOY_FAILED,
            subtaskType = "deploy",
            error = "[ORCHESTRATOR] Deploy-timeout voor SF-2 na 20 minuten, geen bevestiging via ArgoCD/rest-restart.",
        )
        val parent = story("SF-1", "Story", StoryPhase.IN_PROGRESS, autoApprove = false)
        val fixture = fixture(
            issues = listOf(sub),
            parents = mapOf("SF-2" to "SF-1"),
            getIssues = mapOf("SF-1" to parent),
        )

        fixture.service.notifyPending()

        val message = fixture.client.single()
        assertTrue(message.contains("⚠️ Fout in de Software Factory"), message)
        assertTrue(message.contains("Deploy-timeout voor SF-2 na 20 minuten"), message)
    }

    // ── SF-335: silent stories krijgen geen enkel bericht ───────────────────────

    @Test
    fun `SF-335 - silent story krijgt geen enkel bericht (ook geen error)`() {
        val story = story("SF-1", "Silent story", StoryPhase.PLANNED, autoApprove = false, silent = true, error = "Iets ging mis")
        val fixture = fixture(issues = listOf(story))

        fixture.service.notifyPending()

        assertTrue(fixture.client.messages.isEmpty(), "Een silent story mag geen Telegram-bericht opleveren")
    }

    @Test
    fun `SF-335 - subtaak van een silent parent krijgt geen bericht`() {
        val sub = subtask("SF-2", "Bouwen", SubtaskPhase.DEVELOPED_WITH_QUESTIONS, autoApprove = false)
        val parent = story("SF-1", "Silent story", StoryPhase.IN_PROGRESS, autoApprove = false, silent = true)
        val fixture = fixture(
            issues = listOf(sub),
            parents = mapOf("SF-2" to "SF-1"),
            getIssues = mapOf("SF-1" to parent),
        )

        fixture.service.notifyPending()

        assertTrue(fixture.client.messages.isEmpty(), "Een subtaak met een silent parent mag geen bericht opleveren")
    }

    @Test
    fun `SF-335 - niet-silent story blijft gewoon melden`() {
        val story = story("SF-1", "Gewone story", StoryPhase.PLANNED, autoApprove = false, silent = false)
        val fixture = fixture(issues = listOf(story))

        fixture.service.notifyPending()

        assertEquals(1, fixture.client.messages.size, "Zonder silent blijft de bestaande APPROVAL-melding")
    }

    // ── SF-1261: meldingen=als-klaar / als-klaar-en-gedeployed ──────────────────

    @Test
    fun `SF-1261 - meldingen=als-klaar onderdrukt een tussentijdse subtaak-DONE`() {
        val sub1 = subtask("SF-2", "Bouwen", SubtaskPhase.REVIEW_APPROVED, autoApprove = true)
        val sub2 = subtask("SF-3", "Testen", SubtaskPhase.TESTING)
        val story = story(
            "SF-1", "Story", StoryPhase.IN_PROGRESS, autoApprove = true,
            notifyMode = NotifyMode.WHEN_DONE.trackerValue,
        )
        val fixture = fixture(
            issues = listOf(sub1),
            parents = mapOf("SF-2" to "SF-1"),
            getIssues = mapOf("SF-1" to story),
            subtasks = mapOf("SF-1" to listOf(sub1, sub2)),
        )

        fixture.service.notifyPending()

        assertTrue(fixture.client.messages.isEmpty(), "Niet alle subtaken terminaal: geen als-klaar-melding")
    }

    @Test
    fun `SF-1261 - meldingen=als-klaar stuurt precies de allerlaatste subtaak-DONE`() {
        val sub1 = subtask("SF-2", "Bouwen", SubtaskPhase.REVIEW_APPROVED, autoApprove = true)
        val sub2 = subtask("SF-3", "Testen", SubtaskPhase.TEST_APPROVED)
        val story = story(
            "SF-1", "Story", StoryPhase.IN_PROGRESS, autoApprove = true,
            notifyMode = NotifyMode.WHEN_DONE.trackerValue,
        )
        val fixture = fixture(
            issues = listOf(sub1),
            parents = mapOf("SF-2" to "SF-1"),
            getIssues = mapOf("SF-1" to story),
            subtasks = mapOf("SF-1" to listOf(sub1, sub2)),
        )

        fixture.service.notifyPending()

        val message = fixture.client.single()
        assertTrue(message.contains("Story helemaal afgerond! 🎉"), message)
    }

    @Test
    fun `SF-1261 - meldingen=als-klaar-en-gedeployed onderdrukt ook de allerlaatste subtaak-DONE`() {
        val sub1 = subtask("SF-2", "Bouwen", SubtaskPhase.REVIEW_APPROVED, autoApprove = true)
        val sub2 = subtask("SF-3", "Testen", SubtaskPhase.TEST_APPROVED)
        val story = story(
            "SF-1", "Story", StoryPhase.IN_PROGRESS, autoApprove = true,
            notifyMode = NotifyMode.WHEN_DONE_AND_DEPLOYED.trackerValue,
        )
        val fixture = fixture(
            issues = listOf(sub1),
            parents = mapOf("SF-2" to "SF-1"),
            getIssues = mapOf("SF-1" to story),
            subtasks = mapOf("SF-1" to listOf(sub1, sub2)),
        )

        fixture.service.notifyPending()

        assertTrue(
            fixture.client.messages.isEmpty(),
            "als-klaar-en-gedeployed: alleen TelegramResultNotifyPoller stuurt het eindresultaat",
        )
    }

    @Test
    fun `SF-1261 - meldingen=als-klaar-en-gedeployed laat een ERROR-melding wel door`() {
        val story = story(
            "SF-1", "Story", StoryPhase.PLANNED, autoApprove = false,
            notifyMode = NotifyMode.WHEN_DONE_AND_DEPLOYED.trackerValue, error = "Iets ging mis",
        )
        val fixture = fixture(issues = listOf(story))

        fixture.service.notifyPending()

        assertEquals(1, fixture.client.messages.size, "Een error moet altijd door, ongeacht de meldingen-as")
        assertTrue(fixture.client.messages.single().contains("Iets ging mis"))
    }

    // ── SF-207: testrapport, screenshots en preview-URL ─────────────────────────

    @Test
    fun `SF-207 - afgeronde test-subtaak voegt rapport, preview-link en screenshots toe`() {
        val testSub = subtask("SF-3", "Testen", SubtaskPhase.TEST_APPROVED, autoApprove = true, subtaskType = "test")
        val story = story("SF-1", "Story", StoryPhase.IN_PROGRESS, autoApprove = true)
        val shots = listOf(
            screenshotAttachment("a1", "factory-tester-screenshot__SF-1__01__home.png"),
            screenshotAttachment("a2", "factory-tester-screenshot__SF-1__02__detail.png"),
        )
        val fixture = fixture(
            issues = listOf(testSub),
            parents = mapOf("SF-3" to "SF-1"),
            getIssues = mapOf("SF-1" to story),
            subtasks = mapOf("SF-1" to listOf(testSub)),
            testerReports = mapOf("SF-1" to "Alle smoke-tests groen."),
            previewUrls = mapOf("SF-1" to "https://preview.example/pr-7"),
            attachments = mapOf("SF-1" to shots),
            attachmentBytes = mapOf("a1" to byteArrayOf(1, 2, 3), "a2" to byteArrayOf(4, 5, 6)),
        )

        fixture.service.notifyPending()

        val message = fixture.client.single()
        assertTrue(message.contains("📋 Testrapport"), message)
        assertTrue(message.contains("Alle smoke-tests groen."), message)
        assertTrue(message.contains("🔗 Preview: https://preview.example/pr-7"), message)
        assertEquals(2, fixture.client.photos.size, "beide screenshots als foto")
        assertTrue(fixture.client.photos.all { it.chatId == "chat-default" }, "zelfde kanaal als de tekst")
    }

    @Test
    fun `SF-207 - testrapport wordt afgekapt op een Telegram-veilige lengte`() {
        val testSub = subtask("SF-3", "Testen", SubtaskPhase.TEST_APPROVED, autoApprove = true, subtaskType = "test")
        val story = story("SF-1", "Story", StoryPhase.IN_PROGRESS, autoApprove = true)
        val fixture = fixture(
            issues = listOf(testSub),
            parents = mapOf("SF-3" to "SF-1"),
            getIssues = mapOf("SF-1" to story),
            subtasks = mapOf("SF-1" to listOf(testSub)),
            testerReports = mapOf("SF-1" to "y".repeat(2000)),
        )

        fixture.service.notifyPending()

        val message = fixture.client.single()
        assertTrue(message.contains("y".repeat(1200)), "rapport afgekapt op 1200")
        assertFalse(message.contains("y".repeat(1201)), "niet langer dan 1200")
    }

    @Test
    fun `SF-207 - niet-test-subtaak blijft ongewijzigd (geen rapport, preview of fotos)`() {
        val devSub = subtask("SF-2", "Bouwen", SubtaskPhase.REVIEW_APPROVED, autoApprove = true, subtaskType = "development")
        val testSub = subtask("SF-3", "Testen", SubtaskPhase.TESTING)
        val story = story("SF-1", "Story", StoryPhase.IN_PROGRESS, autoApprove = true)
        val fixture = fixture(
            issues = listOf(devSub),
            parents = mapOf("SF-2" to "SF-1"),
            getIssues = mapOf("SF-1" to story),
            subtasks = mapOf("SF-1" to listOf(devSub, testSub)),
            // Data is aanwezig maar mag NIET in een development-melding belanden.
            testerReports = mapOf("SF-1" to "geheim rapport"),
            previewUrls = mapOf("SF-1" to "https://preview.example/pr-7"),
            attachments = mapOf("SF-1" to listOf(screenshotAttachment("a1", "factory-tester-screenshot__x.png"))),
            attachmentBytes = mapOf("a1" to byteArrayOf(1)),
        )

        fixture.service.notifyPending()

        val message = fixture.client.single()
        assertFalse(message.contains("Testrapport"), message)
        assertFalse(message.contains("Preview"), message)
        assertTrue(fixture.client.photos.isEmpty(), "geen foto's bij een development-subtaak")
    }

    @Test
    fun `SF-207 - ontbrekend rapport, preview en screenshots degradeert netjes`() {
        val testSub = subtask("SF-3", "Testen", SubtaskPhase.TEST_APPROVED, autoApprove = true, subtaskType = "test")
        val story = story("SF-1", "Story", StoryPhase.IN_PROGRESS, autoApprove = true)
        val fixture = fixture(
            issues = listOf(testSub),
            parents = mapOf("SF-3" to "SF-1"),
            getIssues = mapOf("SF-1" to story),
            subtasks = mapOf("SF-1" to listOf(testSub)),
            // bewust geen rapport/preview/screenshots
        )

        fixture.service.notifyPending()

        val message = fixture.client.single()
        assertTrue(message.contains("✅ Klaar"), message)
        assertFalse(message.contains("Testrapport"), message)
        assertFalse(message.contains("Preview"), message)
        assertTrue(fixture.client.photos.isEmpty())
    }

    @Test
    fun `SF-207 - gefaalde sendPhoto blokkeert de tekst niet en triggert geen herverzending`() {
        val testSub = subtask("SF-3", "Testen", SubtaskPhase.TEST_APPROVED, autoApprove = true, subtaskType = "test")
        val story = story("SF-1", "Story", StoryPhase.IN_PROGRESS, autoApprove = true)
        val shots = listOf(screenshotAttachment("a1", "factory-tester-screenshot__one.png"))
        val fixture = fixture(
            issues = listOf(testSub),
            parents = mapOf("SF-3" to "SF-1"),
            getIssues = mapOf("SF-1" to story),
            subtasks = mapOf("SF-1" to listOf(testSub)),
            attachments = mapOf("SF-1" to shots),
            attachmentBytes = mapOf("a1" to byteArrayOf(9)),
            sendPhotoResult = false,
        )

        fixture.service.notifyPending()
        fixture.service.notifyPending()

        assertEquals(1, fixture.client.messages.size, "tekstmelding precies één keer")
        assertEquals(1, fixture.client.photos.size, "foto één keer geprobeerd, niet opnieuw bij de tweede poll")
    }

    @Test
    fun `SF-207 - boven het maximum komen de extra screenshots als links in de tekst`() {
        val testSub = subtask("SF-3", "Testen", SubtaskPhase.TEST_APPROVED, autoApprove = true, subtaskType = "test")
        val story = story("SF-1", "Story", StoryPhase.IN_PROGRESS, autoApprove = true)
        val shots = (1..12).map { i ->
            screenshotAttachment("a$i", "factory-tester-screenshot__%02d.png".format(i), url = "https://files.example/$i?sign=x")
        }
        val bytes = (1..12).associate { "a$it" to byteArrayOf(it.toByte()) }
        val fixture = fixture(
            issues = listOf(testSub),
            parents = mapOf("SF-3" to "SF-1"),
            getIssues = mapOf("SF-1" to story),
            subtasks = mapOf("SF-1" to listOf(testSub)),
            attachments = mapOf("SF-1" to shots),
            attachmentBytes = bytes,
        )

        fixture.service.notifyPending()

        val message = fixture.client.single()
        assertEquals(10, fixture.client.photos.size, "maximaal 10 als foto")
        assertTrue(message.contains("Meer screenshots"), message)
        assertTrue(message.contains("https://files.example/11?sign=x"), message)
        assertTrue(message.contains("https://files.example/12?sign=x"), message)
    }

    private fun screenshotAttachment(id: String, name: String, url: String? = null) =
        TrackerAttachment(
            id = id,
            name = name,
            url = url ?: "/api/files/$id?sign=x",
            mimeType = "image/png",
            size = 1,
            created = null,
        )

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
        mergeReady: Map<String, MergeReadyInfo> = emptyMap(),
        testerReports: Map<String, String> = emptyMap(),
        previewUrls: Map<String, String> = emptyMap(),
        attachments: Map<String, List<TrackerAttachment>> = emptyMap(),
        attachmentBytes: Map<String, ByteArray?> = emptyMap(),
        sendPhotoResult: Boolean = true,
    ): Fixture {
        val secrets = secrets()
        val tracker = FakeTracker(issues, parents, getIssues, subtasks, attachments, attachmentBytes)
        val dashboard = FakeDashboard(secrets, tracker, mergeReady, testerReports, previewUrls)
        val client = RecordingTelegramClient(secrets, sendPhotoResult)
        val store = FakeStore()
        val service = TelegramNotificationService(
            issueTrackerClient = tracker,
            dashboardService = dashboard,
            telegramClient = client,
            store = store,
            secrets = secrets,
            projectRepoResolver = ProjectConfiguration(emptyMap()),
        )
        return Fixture(service, client, store)
    }

    private class FakeTracker(
        private val issues: List<TrackerIssue>,
        private val parents: Map<String, String>,
        private val getIssues: Map<String, TrackerIssue>,
        private val subtasks: Map<String, List<TrackerIssue>>,
        private val attachments: Map<String, List<TrackerAttachment>> = emptyMap(),
        private val attachmentBytes: Map<String, ByteArray?> = emptyMap(),
    ) : TrackerApi {
        override fun findWorkIssues(maxResults: Int, includeFinished: Boolean): List<TrackerIssue> = issues
        override fun getIssue(issueKey: String): TrackerIssue =
            getIssues[issueKey] ?: error("geen issue voor $issueKey")
        override fun parentStoryKey(subtaskKey: String): String? = parents[subtaskKey]
        override fun subtasksOf(parentKey: String): List<TrackerIssue> = subtasks[parentKey] ?: emptyList()
        override fun listIssueAttachments(issueKey: String): List<TrackerAttachment> = attachments[issueKey] ?: emptyList()
        override fun downloadAttachmentBytes(attachment: TrackerAttachment): ByteArray? = attachmentBytes[attachment.id]
        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) =
            error("ongebruikt: updateIssueFields")
        override fun transitionIssue(issueKey: String, statusName: String) =
            error("ongebruikt: transitionIssue")
        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment =
            error("ongebruikt: postAgentComment")
    }

    /** Alleen [mergeReady]/rapport/preview worden overschreven; de rest leunt op de echte logica + [FakeTracker]. */
    private class FakeDashboard(
        secrets: FactorySecrets,
        tracker: TrackerApi,
        private val mergeReadyByKey: Map<String, MergeReadyInfo>,
        private val testerReportsByKey: Map<String, String> = emptyMap(),
        private val previewUrlsByKey: Map<String, String> = emptyMap(),
    ) : FactoryOperationsService(
        issueTrackerClient = tracker,
        orchestratorApi = StubOrchestrator,
        repository = FactoryDashboardRepository(JdbcTemplate(), secrets),
        previewApi = StubPreview,
    ) {
        override fun mergeReady(storyKey: String): MergeReadyInfo? = mergeReadyByKey[storyKey]
        override fun testerReportFor(storyKey: String): String? = testerReportsByKey[storyKey]
        override fun previewUrlFor(storyKey: String): String? = previewUrlsByKey[storyKey]
    }

    private object StubOrchestrator : OrchestratorApi {
        override fun pollOnce(): OrchestratorPollResult = error("ongebruikt")
        override fun processIssue(issue: TrackerIssue): IssueProcessResult = error("ongebruikt")
        override fun queueCommand(storyKey: String, command: FactoryCommand, reason: String?) = error("ongebruikt")
    }

    private object StubPreview : PreviewApi {
        override fun render(template: String?, prNumber: Int?): String? = null
        override fun cleanup(namespace: String): Boolean = true
    }

    private data class PhotoRecord(val chatId: String, val caption: String?)

    private class RecordingTelegramClient(
        secrets: FactorySecrets,
        private val sendPhotoResult: Boolean = true,
    ) : TelegramClient(secrets) {
        val messages = mutableListOf<String>()
        val photos = mutableListOf<PhotoRecord>()
        private var counter = 0L
        override val enabled: Boolean get() = true
        override val defaultChatId: String get() = "chat-default"
        override fun sendMessage(text: String, replyToMessageId: Long?, chatId: String?): Long {
            messages += text
            return ++counter
        }

        override fun sendPhoto(chatId: String, file: Path, caption: String?): Boolean {
            photos += PhotoRecord(chatId, caption)
            return sendPhotoResult
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
        silent: Boolean = false,
        error: String? = null,
        notifyMode: String? = null,
    ) = TrackerIssue(
        key = key,
        summary = summary,
        description = description,
        status = "open",
        fields = fields(
            autoApprove = autoApprove, storyPhase = phase.trackerValue, silent = silent, error = error,
            notifyMode = notifyMode,
        ),
        comments = emptyList(),
    )

    private fun subtask(
        key: String,
        summary: String,
        phase: SubtaskPhase,
        autoApprove: Boolean = false,
        subtaskType: String = "development",
        silent: Boolean = false,
        error: String? = null,
        notifyMode: String? = null,
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
            silent = silent,
            error = error,
            notifyMode = notifyMode,
        ),
        comments = emptyList(),
    )

    // SF-1261 — `autoApprove`/`silent` blijven de testhelper-parameternamen (minimale diff over de
    // vele call sites); ze vertalen nu naar de nieuwe assen: autoApprove -> approvalMode,
    // silent -> notifyMode=geen (dat is de as die TelegramNotificationService leest).
    // Een expliciete `notifyMode` overschrijft de uit `silent` afgeleide waarde (voor de
    // als-klaar/als-klaar-en-gedeployed-tests, die geen op-of-uit boolean zijn).
    private fun fields(
        autoApprove: Boolean = false,
        storyPhase: String? = null,
        subtaskPhase: String? = null,
        type: String? = null,
        subtaskType: String? = null,
        silent: Boolean = false,
        error: String? = null,
        notifyMode: String? = null,
    ) = TrackerIssueFields(
        targetRepo = null,
        repo = null,
        aiSupplier = null,
        approvalMode = if (autoApprove) ApprovalMode.AUTOMATIC.trackerValue else ApprovalMode.EVERY_STEP.trackerValue,
        aiPhase = null,
        aiLevel = null,
        aiTokenBudget = null,
        aiTokensUsed = null,
        agentStartedAt = null,
        paused = false,
        notifyMode = notifyMode ?: (if (silent) NotifyMode.NONE.trackerValue else NotifyMode.EVERY_STEP.trackerValue),
        error = error,
        type = type,
        subtaskType = subtaskType,
        storyPhase = storyPhase,
        subtaskPhase = subtaskPhase,
    )

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
}
