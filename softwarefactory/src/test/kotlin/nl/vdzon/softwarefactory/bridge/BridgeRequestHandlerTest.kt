package nl.vdzon.softwarefactory.bridge

import nl.vdzon.softwarefactory.bridge.services.BridgeRequestHandler
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.contract.BridgeRequest
import nl.vdzon.softwarefactory.contract.BridgeParams
import nl.vdzon.softwarefactory.core.contracts.TrackerAttachment
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.dashboard.services.DashboardQueryService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dekt [BridgeRequestHandler] per operatie tegen de bestaande fakes (zie
 * docs/ontwerp-bridge-dashboard.md §10): vertalen naar [DashboardQueryService], nooit nieuwe
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
    fun `story-create zonder notificationEvents gebruikt de concrete deployed-default`() {
        val fixture = BridgeTestFixtures.minimalRequestHandlerWithFakes()
        val response = fixture.handler.handle(
            BridgeRequest(
                id = "create-default-notify",
                operation = "story.create",
                params = paramsOf("projectKey" to "SF", "title" to "Nieuwe story"),
            ),
        )

        assertEquals(true, response.ok)
        assertEquals(
            setOf("DEPLOYED", "QUESTION", "MANUAL_ACTION_REQUIRED", "ERROR"),
            fixture.tracker.lastCreateStoryNotificationEvents?.map { it.name }?.toSet(),
        )
    }

    @Test
    fun `story-create bewaart een expliciete lege eventset`() {
        val fixture = BridgeTestFixtures.minimalRequestHandlerWithFakes()
        val response = fixture.handler.handle(
            BridgeRequest(
                id = "create-explicit-notify",
                operation = "story.create",
                params = paramsOf("projectKey" to "SF", "title" to "Nieuwe story").apply {
                    putArray("notificationEvents")
                },
            ),
        )

        assertEquals(true, response.ok)
        assertEquals(emptySet(), fixture.tracker.lastCreateStoryNotificationEvents)
    }

    @Test
    fun `story-create wijst een onbekend notification-event af zonder story aan te maken`() {
        val fixture = BridgeTestFixtures.minimalRequestHandlerWithFakes()
        val response = fixture.handler.handle(
            BridgeRequest(
                id = "create-invalid-notify",
                operation = "story.create",
                params = paramsOf("projectKey" to "SF", "title" to "Nieuwe story").apply {
                    putArray("notificationEvents").add("EROR")
                },
            ),
        )

        assertEquals(false, response.ok)
        assertEquals("INVALID_PARAMS", response.error?.code)
        assertEquals(null, fixture.tracker.lastCreateStoryNotificationEvents)
    }

    // SF-1959 — de hotfix-as is een aanmaakkeuze: zonder expliciete waarde nooit aan.
    @Test
    fun `story-create zonder hotfix maakt geen hotfix-story`() {
        val fixture = BridgeTestFixtures.minimalRequestHandlerWithFakes()

        val response = fixture.handler.handle(
            BridgeRequest(
                id = "create-default-hotfix",
                operation = "story.create",
                params = paramsOf("projectKey" to "SF", "title" to "Nieuwe story"),
            ),
        )

        assertEquals(true, response.ok)
        assertEquals(false, fixture.tracker.lastCreateStoryHotfix)
    }

    @Test
    fun `story-create met hotfix true geeft de vlag door aan de tracker`() {
        val fixture = BridgeTestFixtures.minimalRequestHandlerWithFakes()
        val params = paramsOf("projectKey" to "SF", "title" to "Nieuwe story").put("hotfix", true)

        val response = fixture.handler.handle(
            BridgeRequest(id = "create-hotfix", operation = "story.create", params = params),
        )

        assertEquals(true, response.ok)
        assertEquals(true, fixture.tracker.lastCreateStoryHotfix)
    }

    @Test
    fun `stories-list aggregeert quota van subtaak naar zichtbare parentstatus`() {
        val retryAfter = java.time.OffsetDateTime.parse("2026-08-02T12:30:00Z")
        val story = BridgeTestFixtures.issue("SF-1")
        val subtask = BridgeTestFixtures.issue("SF-2").copy(
            parentKey = story.key,
            fields = BridgeTestFixtures.issue("SF-2").fields.copy(type = "Task", retryAfter = retryAfter),
        )
        val handler = BridgeTestFixtures.minimalRequestHandler(issues = listOf(story, subtask))

        val response = handler.handle(BridgeRequest(id = "r-quota", operation = "stories.list"))

        assertEquals(true, response.ok)
        assertEquals(retryAfter.toString(), response.body?.path("quotaRetryAfterByStory")?.path(story.key)?.asText())
        assertTrue(response.body?.path("issues")?.single()?.path("fields")?.path("retryAfter")?.isTextual == false)
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
    fun `agents-list en projects-list routeren naar de bestaande service-methodes`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        assertEquals(true, handler.handle(BridgeRequest(id = "a", operation = "agents.list")).ok)
        assertEquals(true, handler.handle(BridgeRequest(id = "c", operation = "projects.list")).ok)
    }

    @Test
    fun `agent-log levert een lege regelslijst als er nog geen events zijn`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val response = handler.handle(
            BridgeRequest(id = "log-1", operation = "agent.log", params = paramsOf("agentRunId" to "123")),
        )

        assertEquals(true, response.ok)
        assertEquals(123L, response.body?.path("agentRunId")?.asLong())
        assertEquals(0, response.body?.path("lines")?.size())
    }

    @Test
    fun `agent-log met een niet-numerieke agentRunId geeft INVALID_PARAMS`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val response = handler.handle(
            BridgeRequest(id = "log-2", operation = "agent.log", params = paramsOf("agentRunId" to "geen-getal")),
        )

        assertEquals(false, response.ok)
        assertEquals("INVALID_PARAMS", response.error?.code)
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
    fun `projects-branchTimeline zonder 'name'-param geeft INVALID_PARAMS ipv een netwerkcall`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val response = handler.handle(BridgeRequest(id = "bt-1", operation = "projects.branchTimeline"))

        assertEquals(false, response.ok)
        assertEquals("INVALID_PARAMS", response.error?.code)
    }

    @Test
    fun `projects-branchTimeline zonder geconfigureerde repo levert lege rows met foutmelding`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val response = handler.handle(
            BridgeRequest(id = "bt-2", operation = "projects.branchTimeline", params = paramsOf("name" to "onbekend-project")),
        )

        assertEquals(true, response.ok)
        assertEquals(0, response.body?.path("rows")?.size())
        assertTrue((response.body?.path("errors")?.size() ?: 0) > 0)
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
    fun `story-setQuestionsAllowed zet het questionsAllowed-veld`() {
        val fixture = BridgeTestFixtures.minimalRequestHandlerWithFakes()

        val params = objectMapper.createObjectNode().put("storyKey", "SF-1").put("enabled", true)
        val response = fixture.handler.handle(BridgeRequest(id = "qa", operation = "story.setQuestionsAllowed", params = params))

        assertEquals(true, response.ok)
        assertEquals("SF-1", fixture.tracker.lastFieldUpdate?.first)
    }

    @Test
    fun `story-setApprovalMode zet het approvalMode-veld`() {
        val fixture = BridgeTestFixtures.minimalRequestHandlerWithFakes()

        val params = objectMapper.createObjectNode().put("storyKey", "SF-1").put("mode", "elke-stap")
        val response = fixture.handler.handle(BridgeRequest(id = "am", operation = "story.setApprovalMode", params = params))

        assertEquals(true, response.ok)
        assertEquals("SF-1", fixture.tracker.lastFieldUpdate?.first)
    }

    @Test
    fun `story-setNotificationEvents zet de concrete eventset`() {
        val fixture = BridgeTestFixtures.minimalRequestHandlerWithFakes()

        val params = objectMapper.createObjectNode().put("storyKey", "SF-1").apply {
            putArray("notificationEvents").add("DEPLOYED").add("ERROR")
        }
        val response = fixture.handler.handle(
            BridgeRequest(id = "nm", operation = "story.setNotificationEvents", params = params),
        )

        assertEquals(true, response.ok)
        assertEquals("SF-1", fixture.tracker.lastFieldUpdate?.first)
        assertEquals(setOf("DEPLOYED", "ERROR"), fixture.tracker.lastFieldUpdate?.second?.values
            ?.get(TrackerField.NOTIFICATION_EVENTS)
            ?.let { it as Set<*> }
            ?.map { it.toString() }
            ?.toSet())
    }

    @Test
    fun `story-setNotificationEvents wijst een onbekend event af zonder bestaande set te overschrijven`() {
        val fixture = BridgeTestFixtures.minimalRequestHandlerWithFakes()
        val params = objectMapper.createObjectNode().put("storyKey", "SF-1").apply {
            putArray("notificationEvents").add("EROR")
        }

        val response = fixture.handler.handle(
            BridgeRequest(id = "nm-invalid", operation = "story.setNotificationEvents", params = params),
        )

        assertEquals(false, response.ok)
        assertEquals("INVALID_PARAMS", response.error?.code)
        assertEquals(null, fixture.tracker.lastFieldUpdate)
    }

    @Test
    fun `story-edit werkt alleen de meegegeven velden bij`() {
        val fixture = BridgeTestFixtures.minimalRequestHandlerWithFakes()

        val response = fixture.handler.handle(
            BridgeRequest(
                id = "edit",
                operation = "story.edit",
                params = paramsOf("storyKey" to "SF-1", "description" to "nieuwe omschrijving", "aiSupplier" to "openai", "aiModel" to "gpt-5.6-sol"),
            ),
        )

        assertEquals(true, response.ok)
        assertEquals("SF-1" to "nieuwe omschrijving", fixture.tracker.lastDescription)
        assertEquals("SF-1", fixture.tracker.lastFieldUpdate?.first)
    }

    @Test
    fun `story-edit zonder optionele velden laat de tracker met rust`() {
        val fixture = BridgeTestFixtures.minimalRequestHandlerWithFakes()

        val response = fixture.handler.handle(
            BridgeRequest(id = "edit2", operation = "story.edit", params = paramsOf("storyKey" to "SF-1")),
        )

        assertEquals(true, response.ok)
        assertEquals(null, fixture.tracker.lastDescription)
        assertEquals(null, fixture.tracker.lastFieldUpdate)
    }

    @Test
    fun `story-edit met een lege aiModel wist het eerder ingestelde model`() {
        val fixture = BridgeTestFixtures.minimalRequestHandlerWithFakes()

        val response = fixture.handler.handle(
            BridgeRequest(
                id = "edit3",
                operation = "story.edit",
                params = paramsOf("storyKey" to "SF-1", "aiModel" to ""),
            ),
        )

        assertEquals(true, response.ok)
        assertEquals("SF-1", fixture.tracker.lastFieldUpdate?.first)
        assertEquals("", fixture.tracker.lastFieldUpdate?.second?.values?.get(nl.vdzon.softwarefactory.core.TrackerField.AI_MODEL))
    }

    @Test
    fun `story-command zet het commando in de wachtrij bij de orchestrator`() {
        val fixture = BridgeTestFixtures.minimalRequestHandlerWithFakes()

        val response = fixture.handler.handle(
            BridgeRequest(id = "cmd", operation = "story.command", params = paramsOf("storyKey" to "SF-1", "command" to "approve")),
        )

        assertEquals(true, response.ok)
        assertEquals("SF-1", fixture.orchestrator.lastCommand?.first)
        assertEquals(nl.vdzon.softwarefactory.core.contracts.FactoryCommand.APPROVE, fixture.orchestrator.lastCommand?.second)
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
    fun `ontbrekende verplichte parameter geeft INVALID_PARAMS ipv INTERNAL_ERROR`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val response = handler.handle(BridgeRequest(id = "missing", operation = "story.purge"))

        assertEquals(false, response.ok)
        assertEquals("INVALID_PARAMS", response.error?.code)
    }

    @Test
    fun `maintenance-cleanupsList levert de (soft-failende) cleanup-historie op`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val response = handler.handle(BridgeRequest(id = "mc-1", operation = "maintenance.cleanupsList"))

        assertEquals(true, response.ok)
        assertEquals(0, response.body?.path("runs")?.size())
    }

    @Test
    fun `maintenance-cleanupsList accepteert een optioneel projectfilter`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val response = handler.handle(
            BridgeRequest(id = "mc-2", operation = "maintenance.cleanupsList", params = paramsOf("project" to "sf")),
        )

        assertEquals(true, response.ok)
        assertEquals(0, response.body?.path("runs")?.size())
    }

    @Test
    fun `maintenance-cleanupDetail zonder id geeft INVALID_PARAMS`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val response = handler.handle(BridgeRequest(id = "mc-3", operation = "maintenance.cleanupDetail"))

        assertEquals(false, response.ok)
        assertEquals("INVALID_PARAMS", response.error?.code)
    }

    @Test
    fun `maintenance-cleanupDetail met een niet-numerieke id geeft INVALID_PARAMS`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val response = handler.handle(
            BridgeRequest(id = "mc-4", operation = "maintenance.cleanupDetail", params = paramsOf("id" to "geen-getal")),
        )

        assertEquals(false, response.ok)
        assertEquals("INVALID_PARAMS", response.error?.code)
    }

    @Test
    fun `maintenance-cleanupDetail voor een onbekende run geeft NOT_FOUND (geen crash)`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val response = handler.handle(
            BridgeRequest(id = "mc-5", operation = "maintenance.cleanupDetail", params = paramsOf("id" to "404")),
        )

        assertEquals(false, response.ok)
        assertEquals("NOT_FOUND", response.error?.code)
    }

    @Test
    fun `maintenance-cleanupsList meldt welke soorten op dit moment draaien`() {
        val fixture = BridgeTestFixtures.minimalRequestHandlerWithFakes()
        fixture.cleanupGuard.tryStart("agent-events")

        val response = fixture.handler.handle(BridgeRequest(id = "mc-6", operation = "maintenance.cleanupsList"))

        assertEquals(true, response.ok)
        assertEquals(1, response.body?.path("runningKinds")?.size())
        assertEquals("agent-events", response.body?.path("runningKinds")?.get(0)?.asText())
    }

    @Test
    fun `maintenance-runNow start de gevraagde soort en geeft de status terug`() {
        val fixture = BridgeTestFixtures.minimalRequestHandlerWithFakes()

        val response = fixture.handler.handle(
            BridgeRequest(id = "mr-1", operation = "maintenance.runNow", params = paramsOf("kind" to "agent-events")),
        )

        assertEquals(true, response.ok)
        assertEquals(listOf("agent-events"), fixture.cleanupRunNow.requestedKinds)
        assertEquals(true, response.body?.path("started")?.asBoolean())
        assertEquals("started", response.body?.path("status")?.asText())
        assertEquals("started", response.body?.path("kinds")?.path("agent-events")?.asText())
    }

    @Test
    fun `maintenance-runNow geeft de alles-waarde ongewijzigd door`() {
        val fixture = BridgeTestFixtures.minimalRequestHandlerWithFakes()

        val response = fixture.handler.handle(
            BridgeRequest(id = "mr-2", operation = "maintenance.runNow", params = paramsOf("kind" to "all")),
        )

        assertEquals(true, response.ok)
        assertEquals(listOf("all"), fixture.cleanupRunNow.requestedKinds)
    }

    @Test
    fun `maintenance-runNow zonder kind geeft INVALID_PARAMS`() {
        val handler = BridgeTestFixtures.minimalRequestHandler()

        val response = handler.handle(BridgeRequest(id = "mr-3", operation = "maintenance.runNow"))

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
        // DashboardQueryService.stories() vangt tracker-fouten zelf af (errors-lijst, lege
        // issues) — dit dekt dat de bridge dat gedrag ongewijzigd doorgeeft, niet dat de bridge
        // zelf een exception onderschept (dat pad heeft geen van de fase-B-operaties nog).
        val handler = BridgeTestFixtures.minimalRequestHandler(issues = null)

        val response = handler.handle(BridgeRequest(id = "r-4", operation = "stories.list"))

        assertEquals(true, response.ok)
        assertEquals(0, response.body?.path("issues")?.size())
        assertTrue((response.body?.path("errors")?.size() ?: 0) > 0)
    }
}
