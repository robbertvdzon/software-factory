package nl.vdzon.softwarefactory.bridge

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.contract.BridgeRequest
import nl.vdzon.softwarefactory.contract.BridgeParams
import nl.vdzon.softwarefactory.core.TrackerAttachment
import nl.vdzon.softwarefactory.dashboard.services.FactoryDashboardService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dekt [BridgeRequestHandler] per operatie tegen de bestaande fakes (zie
 * docs/ontwerp-bridge-dashboard.md §10): vertalen naar [FactoryDashboardService], nooit nieuwe
 * businesslogica (behalve `downloads.list`). Wiring in [BridgeTestFixtures].
 */
class BridgeRequestHandlerTest {

    private val objectMapper = jacksonObjectMapper()

    private fun paramsOf(vararg entries: Pair<String, String>) =
        objectMapper.createObjectNode().apply { entries.forEach { (k, v) -> put(k, v) } }

    @Test
    fun `dashboard-get levert de dashboard-pagina als JSON-body`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val response = handler.handle(BridgeRequest(id = "r-0", operation = "dashboard.get"))

        assertEquals(true, response.ok)
        assertEquals(0, response.body?.path("issues")?.size())
    }

    @Test
    fun `stories-list levert de bestaande stories-pagina als JSON-body`() {
        val handler = BridgeTestFixtures.minimalRequestHandler(
            issues = listOf(BridgeTestFixtures.issue("SF-1"), BridgeTestFixtures.issue("SF-2")),
        )

        val response = handler.handle(BridgeRequest(id = "r-1", operation = "stories.list"))

        assertEquals(true, response.ok)
        assertEquals("r-1", response.id)
        assertEquals(2, response.body?.path("issues")?.size())
    }

    @Test
    fun `myActions-count levert het aantal wachtende taken als JSON-body`() {
        val handler = BridgeTestFixtures.minimalRequestHandler(issues = emptyList())

        val response = handler.handle(BridgeRequest(id = "r-2", operation = "myActions.count"))

        assertEquals(true, response.ok)
        assertEquals(0, response.body?.path("count")?.asInt())
    }

    @Test
    fun `myActions-list levert de inbox-groepen als JSON-body`() {
        val handler = BridgeTestFixtures.minimalRequestHandler(issues = emptyList())

        val response = handler.handle(BridgeRequest(id = "r-2b", operation = "myActions.list"))

        assertEquals(true, response.ok)
        assertEquals(0, response.body?.path("groups")?.size())
    }

    @Test
    fun `assistant-status levert enabled-busy-activeChatCount als JSON-body`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val response = handler.handle(BridgeRequest(id = "r-assistant", operation = "assistant.status"))

        assertEquals(true, response.ok)
        // Fake secrets hebben geen aiOauthToken -> enabled=false; geen actieve sessies -> busy=false.
        assertEquals(false, response.body?.path("enabled")?.asBoolean())
        assertEquals(false, response.body?.path("busy")?.asBoolean())
        assertEquals(0, response.body?.path("activeChatCount")?.asInt())
    }

    @Test
    fun `agents-list, merged-list en projects-list routeren naar de bestaande service-methodes`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        assertEquals(true, handler.handle(BridgeRequest(id = "a", operation = "agents.list")).ok)
        assertEquals(true, handler.handle(BridgeRequest(id = "b", operation = "merged.list")).ok)
        assertEquals(true, handler.handle(BridgeRequest(id = "c", operation = "projects.list")).ok)
    }

    @Test
    fun `force accepteert uitsluitend het gedeelde boolean contract en ontbrekend blijft false`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        assertEquals(true, handler.handle(BridgeRequest(id = "missing", operation = "projects.list")).ok)
        assertEquals(
            true,
            handler.handle(
                BridgeRequest(id = "false", operation = "projects.list", params = BridgeParams.boolean("force", false)),
            ).ok,
        )
        val stringResponse = handler.handle(
            BridgeRequest(id = "string", operation = "projects.list", params = paramsOf("force" to "true")),
        )
        assertEquals(false, stringResponse.ok)
        assertEquals("INVALID_PARAMS", stringResponse.error?.code)
    }

    @Test
    fun `nightly-get levert de nightly-pagina als JSON-body`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val nightly = handler.handle(BridgeRequest(id = "n", operation = "nightly.get"))

        assertEquals(true, nightly.ok)
    }

    @Test
    fun `settings-get zonder username geeft INVALID_PARAMS ipv een crash`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val response = handler.handle(BridgeRequest(id = "s2", operation = "settings.get"))

        assertEquals(false, response.ok)
        assertEquals("INVALID_PARAMS", response.error?.code)
    }

    @Test
    fun `downloads-list levert lege lijst zonder geconfigureerde repos (geen netwerkcall)`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val response = handler.handle(BridgeRequest(id = "d", operation = "downloads.list"))

        assertEquals(true, response.ok)
        assertEquals(0, response.body?.path("downloads")?.size())
    }

    @Test
    fun `builds-list levert lege lijst zonder geconfigureerde repos (geen netwerkcall)`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val response = handler.handle(BridgeRequest(id = "bl", operation = "builds.list"))

        assertEquals(true, response.ok)
        assertEquals(0, response.body?.path("repos")?.size())
    }

    @Test
    fun `builds-runs zonder owner-repo geeft INVALID_PARAMS ipv een netwerkcall`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val response = handler.handle(BridgeRequest(id = "br", operation = "builds.runs", params = paramsOf("owner" to "robbert")))

        assertEquals(false, response.ok)
        assertEquals("INVALID_PARAMS", response.error?.code)
    }

    @Test
    fun `story-screenshots filtert op de tester-screenshot-prefix`() {
        val attachments = listOf(
            TrackerAttachment(id = "1", name = "factory-tester-screenshot__home.png", url = null, mimeType = "image/png", size = 10, created = 1L),
            TrackerAttachment(id = "2", name = "irrelevant.txt", url = null, mimeType = "text/plain", size = 5, created = 2L),
        )
        val handler = BridgeTestFixtures.minimalRequestHandler(attachments = attachments)

        val response = handler.handle(
            BridgeRequest(id = "sc", operation = "story.screenshots", params = paramsOf("storyKey" to "SF-1")),
        )

        assertEquals(true, response.ok)
        assertEquals(1, response.body?.path("screenshots")?.size())
        assertEquals("factory-tester-screenshot__home.png", response.body?.path("screenshots")?.get(0)?.path("name")?.asText())
    }

    @Test
    fun `screenshot-get geeft de bytes als base64 terug`() {
        val attachment = TrackerAttachment(id = "1", name = "factory-tester-screenshot__home.png", url = null, mimeType = "image/png", size = 3, created = 1L)
        val handler = BridgeTestFixtures.minimalRequestHandler(
            attachments = listOf(attachment),
            attachmentBytes = mapOf("1" to byteArrayOf(1, 2, 3)),
        )

        val response = handler.handle(
            BridgeRequest(id = "sg", operation = "screenshot.get", params = paramsOf("storyKey" to "SF-1", "attachmentId" to "1")),
        )

        assertEquals(true, response.ok)
        assertEquals(java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), response.body?.path("base64")?.asText())
    }

    @Test
    fun `screenshot-get op een onbekend attachment geeft NOT_FOUND`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val response = handler.handle(
            BridgeRequest(id = "sg2", operation = "screenshot.get", params = paramsOf("storyKey" to "SF-1", "attachmentId" to "missing")),
        )

        assertEquals(false, response.ok)
        assertEquals("NOT_FOUND", response.error?.code)
    }

    @Test
    fun `story-setStoryPhase zet de fase en post het commentaar via de tracker`() {
        val fixture = BridgeTestFixtures.minimalRequestHandlerWithFakes()

        val response = fixture.handler.handle(
            BridgeRequest(
                id = "sp",
                operation = "story.setStoryPhase",
                params = paramsOf("storyKey" to "SF-1", "phase" to "refining", "comment" to "start maar"),
            ),
        )

        assertEquals(true, response.ok)
        assertEquals("SF-1" to "start maar", fixture.tracker.lastComment)
        assertEquals("SF-1", fixture.tracker.lastFieldUpdate?.first)
    }

    @Test
    fun `story-setStoryPhase met een onbekende fase geeft INTERNAL_ERROR`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val response = handler.handle(
            BridgeRequest(id = "sp2", operation = "story.setStoryPhase", params = paramsOf("storyKey" to "SF-1", "phase" to "nonsense")),
        )

        assertEquals(false, response.ok)
        assertEquals("INTERNAL_ERROR", response.error?.code)
    }

    @Test
    fun `subtask-setPhase routeert naar setSubtaskPhase`() {
        val fixture = BridgeTestFixtures.minimalRequestHandlerWithFakes()

        val response = fixture.handler.handle(
            BridgeRequest(id = "ssp", operation = "subtask.setPhase", params = paramsOf("subtaskKey" to "SF-2", "phase" to "developing")),
        )

        assertEquals(true, response.ok)
        assertEquals("SF-2", fixture.tracker.lastFieldUpdate?.first)
    }

    @Test
    fun `story-setAutoApprove zet het autoApprove-veld`() {
        val fixture = BridgeTestFixtures.minimalRequestHandlerWithFakes()

        val params = objectMapper.createObjectNode().put("storyKey", "SF-1").put("enabled", true)
        val response = fixture.handler.handle(BridgeRequest(id = "aa", operation = "story.setAutoApprove", params = params))

        assertEquals(true, response.ok)
        assertEquals("SF-1", fixture.tracker.lastFieldUpdate?.first)
    }

    @Test
    fun `story-command zet het commando in de wachtrij bij de orchestrator`() {
        val fixture = BridgeTestFixtures.minimalRequestHandlerWithFakes()

        val response = fixture.handler.handle(
            BridgeRequest(id = "cmd", operation = "story.command", params = paramsOf("storyKey" to "SF-1", "command" to "approve")),
        )

        assertEquals(true, response.ok)
        assertEquals("SF-1", fixture.orchestrator.lastCommand?.first)
        assertEquals(nl.vdzon.softwarefactory.core.FactoryCommand.APPROVE, fixture.orchestrator.lastCommand?.second)
    }

    @Test
    fun `story-command met een onbekend commando geeft INVALID_PARAMS`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val response = handler.handle(
            BridgeRequest(id = "cmd2", operation = "story.command", params = paramsOf("storyKey" to "SF-1", "command" to "nonsense")),
        )

        assertEquals(false, response.ok)
        assertEquals("INVALID_PARAMS", response.error?.code)
    }

    @Test
    fun `nightly-runNow en nightly-stop routeren naar de scheduler zonder de socket te breken`() {
        // Deze fixture heeft geen echte DataSource (StubJdbcTemplate); startManualRun/stopActiveRun
        // raken dus altijd de DB-laag en falen hier — dit dekt dat die fout netjes als
        // INTERNAL_ERROR terugkomt in plaats van de handler te laten crashen. Het happy-pad
        // (started/stopped=true) wordt gedekt door NightlySchedulerTest zelf.
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val runNow = handler.handle(BridgeRequest(id = "rn", operation = "nightly.runNow"))
        assertEquals(false, runNow.ok)
        assertEquals("INTERNAL_ERROR", runNow.error?.code)

        val stop = handler.handle(BridgeRequest(id = "st", operation = "nightly.stop"))
        assertEquals(false, stop.ok)
        assertEquals("INTERNAL_ERROR", stop.error?.code)
    }

    @Test
    fun `ontbrekende verplichte parameter geeft INVALID_PARAMS ipv INTERNAL_ERROR`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val response = handler.handle(BridgeRequest(id = "missing", operation = "story.purge"))

        assertEquals(false, response.ok)
        assertEquals("INVALID_PARAMS", response.error?.code)
    }

    @Test
    fun `onbekende operatie geeft een foutresponse met UNKNOWN_OPERATION`() {
        val handler = BridgeTestFixtures.minimalRequestHandler(issues = emptyList())

        val response = handler.handle(BridgeRequest(id = "r-3", operation = "does.not.exist"))

        assertEquals(false, response.ok)
        assertEquals("UNKNOWN_OPERATION", response.error?.code)
    }

    @Test
    fun `een tracker-fout laat stories-list soft-failen i-p-v de socket te breken`() {
        // FactoryDashboardService.stories() vangt tracker-fouten zelf af (errors-lijst, lege
        // issues) — dit dekt dat de bridge dat gedrag ongewijzigd doorgeeft, niet dat de bridge
        // zelf een exception onderschept (dat pad heeft geen van de fase-B-operaties nog).
        val handler = BridgeTestFixtures.minimalRequestHandler(issues = null)

        val response = handler.handle(BridgeRequest(id = "r-4", operation = "stories.list"))

        assertEquals(true, response.ok)
        assertEquals(0, response.body?.path("issues")?.size())
        assertTrue((response.body?.path("errors")?.size() ?: 0) > 0)
    }
}
