package nl.vdzon.softwarefactory.web

import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.FactoryCommand
import nl.vdzon.softwarefactory.core.contracts.TrackerAttachment
import nl.vdzon.softwarefactory.web.DashboardApiFixtures.harness
import nl.vdzon.softwarefactory.web.DashboardApiFixtures.issue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dekt de dashboard-API per endpoint tegen echte services op fakes: vertalen naar
 * `DashboardQueries`/`DashboardCommands`/`FactoryOperations`, nooit nieuwe businesslogica.
 * Wiring in [DashboardApiFixtures].
 */
class DashboardApiControllerTest {

    @Test
    fun `zonder sessietoken geeft elk dashboard-endpoint 401`() {
        val h = harness()

        assertEquals(401, h.get("/api/v1/stories", authenticated = false).response.status)
        assertEquals(401, h.get("/api/v1/status", authenticated = false).response.status)
        assertEquals(401, h.post("/api/v1/stories/SF-1/purge", authenticated = false).response.status)
    }

    @Test
    fun `een vervalst sessietoken geeft 401`() {
        val h = harness()

        val result = h.mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/stories")
                .header("Authorization", "Bearer ${h.token.dropLast(4)}deadbeef"),
        ).andReturn()

        assertEquals(401, result.response.status)
    }

    @Test
    fun `dashboard levert de dashboard-pagina als JSON`() {
        val h = harness()

        assertEquals(0, h.getJson("/api/v1/dashboard").path("issues").size())
    }

    @Test
    fun `stories levert de bestaande stories-pagina als JSON`() {
        val h = harness(issues = listOf(issue("SF-1"), issue("SF-2")))

        assertEquals(2, h.getJson("/api/v1/stories").path("issues").size())
    }

    @Test
    fun `story-detail toont product factory attachments met hun originele naam`() {
        val attachment = TrackerAttachment(
            id = "tracker-7",
            name = "product-factory-input__ux__voorbeeld.png",
            url = null,
            mimeType = "image/png",
            size = 123,
            created = 1L,
        )
        val h = harness(issues = listOf(issue("SF-1")), attachments = listOf(attachment))

        val shown = h.getJson("/api/v1/stories/SF-1").path("productFactoryAttachments").single()

        assertEquals("SF-1", shown.path("storyKey").asText())
        assertEquals("tracker-7", shown.path("id").asText())
        assertEquals("ux", shown.path("attachmentId").asText())
        assertEquals("voorbeeld.png", shown.path("fileName").asText())
    }

    @Test
    fun `story-create zonder notificationEvents gebruikt de concrete deployed-default`() {
        val h = harness()

        val created = h.postJson("/api/v1/stories", """{"projectKey":"SF","title":"Nieuwe story"}""")

        assertEquals("SF-1", created.path("key").asText())
        assertEquals(
            setOf("DEPLOYED", "QUESTION", "MANUAL_ACTION_REQUIRED", "ERROR"),
            h.tracker.lastCreateStoryNotificationEvents?.map { it.name }?.toSet(),
        )
    }

    @Test
    fun `story-create bewaart een expliciete lege eventset`() {
        val h = harness()

        h.postJson("/api/v1/stories", """{"projectKey":"SF","title":"Nieuwe story","notificationEvents":[]}""")

        assertEquals(emptySet(), h.tracker.lastCreateStoryNotificationEvents)
    }

    @Test
    fun `story-create wijst een onbekend notification-event af zonder story aan te maken`() {
        val h = harness()

        val result = h.post("/api/v1/stories", """{"projectKey":"SF","title":"Nieuwe story","notificationEvents":["EROR"]}""")

        assertEquals(400, result.response.status)
        assertEquals("INVALID_PARAMS", h.json(result).path("code").asText())
        assertEquals(null, h.tracker.lastCreateStoryNotificationEvents)
    }

    // SF-1959 — de hotfix-as is een aanmaakkeuze: zonder expliciete waarde nooit aan.
    @Test
    fun `story-create zonder hotfix maakt geen hotfix-story`() {
        val h = harness()

        h.postJson("/api/v1/stories", """{"projectKey":"SF","title":"Nieuwe story"}""")

        assertEquals(false, h.tracker.lastCreateStoryHotfix)
    }

    @Test
    fun `story-create met hotfix true geeft de vlag door aan de tracker`() {
        val h = harness()

        h.postJson("/api/v1/stories", """{"projectKey":"SF","title":"Nieuwe story","hotfix":true}""")

        assertEquals(true, h.tracker.lastCreateStoryHotfix)
    }

    @Test
    fun `stories aggregeert quota van subtaak naar zichtbare parentstatus`() {
        val retryAfter = java.time.OffsetDateTime.parse("2026-08-02T12:30:00Z")
        val story = issue("SF-1")
        val subtask = issue("SF-2").copy(
            parentKey = story.key,
            fields = issue("SF-2").fields.copy(type = "Task", retryAfter = retryAfter),
        )
        val h = harness(issues = listOf(story, subtask))

        val body = h.getJson("/api/v1/stories")

        assertEquals(retryAfter.toInstant(), java.time.OffsetDateTime.parse(body.path("quotaRetryAfterByStory").path(story.key).asText()).toInstant())
        assertTrue(body.path("issues").single().path("fields").path("retryAfter").isTextual == false)
    }

    @Test
    fun `my-actions count en lijst leveren de inbox`() {
        val h = harness(issues = emptyList())

        assertEquals(0, h.getJson("/api/v1/my-actions/count").path("count").asInt())
        assertEquals(0, h.getJson("/api/v1/my-actions").path("groups").size())
    }

    @Test
    fun `assistant-status levert enabled-busy-activeChatCount als JSON`() {
        val h = harness()

        val body = h.getJson("/api/v1/assistant/status")

        // De fake Runtime-assistent staat uit; er zijn geen actieve sessies.
        assertEquals(false, body.path("enabled").asBoolean())
        assertEquals(false, body.path("busy").asBoolean())
        assertEquals(0, body.path("activeChatCount").asInt())
    }

    @Test
    fun `agents en projects routeren naar de bestaande service-methodes`() {
        val h = harness()

        assertEquals(200, h.get("/api/v1/agents").response.status)
        assertEquals(200, h.get("/api/v1/projects").response.status)
        assertEquals(200, h.get("/api/v1/projects?refresh=true").response.status)
    }

    @Test
    fun `agent-events levert een lege regelslijst als er nog geen events zijn`() {
        val h = harness()

        val body = h.getJson("/api/v1/agents/123/events")

        assertEquals(123L, body.path("agentRunId").asLong())
        assertEquals(0, body.path("lines").size())
    }

    @Test
    fun `agent-events met een niet-numerieke agentRunId geeft 400`() {
        val h = harness()

        assertEquals(400, h.get("/api/v1/agents/geen-getal/events").response.status)
    }

    @Test
    fun `downloads en builds leveren lege lijsten zonder geconfigureerde repos (geen netwerkcall)`() {
        val h = harness()

        assertEquals(0, h.getJson("/api/v1/downloads").path("downloads").size())
        assertEquals(0, h.getJson("/api/v1/builds").path("repos").size())
    }

    @Test
    fun `branch-timeline zonder geconfigureerde repo levert lege rows met foutmelding`() {
        val h = harness()

        val body = h.getJson("/api/v1/projects/onbekend-project/branch-timeline")

        assertEquals(0, body.path("rows").size())
        assertTrue(body.path("errors").size() > 0)
    }

    @Test
    fun `screenshots filtert op de tester-screenshot-prefix`() {
        val attachments = listOf(
            TrackerAttachment(id = "1", name = "factory-tester-screenshot__home.png", url = null, mimeType = "image/png", size = 10, created = 1L),
            TrackerAttachment(id = "2", name = "irrelevant.txt", url = null, mimeType = "text/plain", size = 5, created = 2L),
        )
        val h = harness(attachments = attachments)

        val body = h.getJson("/api/v1/stories/SF-1/screenshots")

        assertEquals(1, body.path("screenshots").size())
        assertEquals("factory-tester-screenshot__home.png", body.path("screenshots").get(0).path("name").asText())
    }

    @Test
    fun `screenshot-image geeft de bytes met het content-type terug`() {
        val attachment = TrackerAttachment(id = "1", name = "factory-tester-screenshot__home.png", url = null, mimeType = "image/png", size = 3, created = 1L)
        val h = harness(attachments = listOf(attachment), attachmentBytes = mapOf("1" to byteArrayOf(1, 2, 3)))

        val result = h.get("/api/v1/stories/SF-1/screenshots/1/image")

        assertEquals(200, result.response.status)
        assertEquals("image/png", result.response.contentType)
        assertContentEquals(byteArrayOf(1, 2, 3), result.response.contentAsByteArray)
    }

    @Test
    fun `screenshot-image op een onbekend attachment geeft 404`() {
        val h = harness()

        val result = h.get("/api/v1/stories/SF-1/screenshots/missing/image")

        assertEquals(404, result.response.status)
        assertEquals("NOT_FOUND", h.json(result).path("code").asText())
    }

    @Test
    fun `product-factory-attachment content geeft alleen een product-factory-bestand terug`() {
        val attachment = TrackerAttachment(id = "pf-1", name = "product-factory-input__design__scherm.png", url = null, mimeType = "image/png", size = 3, created = 1L)
        val other = TrackerAttachment(id = "sc-1", name = "factory-tester-screenshot__home.png", url = null, mimeType = "image/png", size = 3, created = 1L)
        val h = harness(
            attachments = listOf(attachment, other),
            attachmentBytes = mapOf("pf-1" to byteArrayOf(1, 2, 3), "sc-1" to byteArrayOf(9)),
        )

        val ok = h.get("/api/v1/stories/SF-1/product-factory-attachments/pf-1/content")
        assertEquals(200, ok.response.status)
        assertContentEquals(byteArrayOf(1, 2, 3), ok.response.contentAsByteArray)

        assertEquals(404, h.get("/api/v1/stories/SF-1/product-factory-attachments/sc-1/content").response.status)
    }

    @Test
    fun `story-phase zet de fase en post het commentaar via de tracker`() {
        val h = harness()

        h.postJson("/api/v1/stories/SF-1/story-phase", """{"phase":"refining","comment":"start maar"}""")

        assertEquals("SF-1" to "start maar", h.tracker.lastComment)
        assertEquals("SF-1", h.tracker.lastFieldUpdate?.first)
    }

    @Test
    fun `story-phase met een onbekende fase geeft 500 met INTERNAL_ERROR`() {
        val h = harness()

        val result = h.post("/api/v1/stories/SF-1/story-phase", """{"phase":"nonsense"}""")

        assertEquals(500, result.response.status)
        assertEquals("INTERNAL_ERROR", h.json(result).path("code").asText())
    }

    @Test
    fun `subtask-phase routeert naar setSubtaskPhase`() {
        val h = harness()

        h.postJson("/api/v1/subtasks/SF-2/phase", """{"phase":"developing"}""")

        assertEquals("SF-2", h.tracker.lastFieldUpdate?.first)
    }

    @Test
    fun `questions-allowed en approval-mode zetten hun veld`() {
        val h = harness()

        h.postJson("/api/v1/stories/SF-1/questions-allowed", """{"enabled":true}""")
        assertEquals("SF-1", h.tracker.lastFieldUpdate?.first)

        h.postJson("/api/v1/stories/SF-1/approval-mode", """{"mode":"elke-stap"}""")
        assertEquals(2, h.tracker.fieldUpdates.size)
    }

    @Test
    fun `notification-events zet de concrete eventset`() {
        val h = harness(issues = listOf(issue("SF-1")))

        h.postJson("/api/v1/stories/SF-1/notification-events", """{"notificationEvents":["DEPLOYED","ERROR"]}""")

        assertEquals("SF-1", h.tracker.lastFieldUpdate?.first)
        assertEquals(
            setOf("DEPLOYED", "ERROR"),
            h.tracker.lastFieldUpdate?.second?.values?.get(TrackerField.NOTIFICATION_EVENTS)
                ?.let { it as Set<*> }?.map { it.toString() }?.toSet(),
        )
    }

    @Test
    fun `notification-events wijst een onbekend event af zonder bestaande set te overschrijven`() {
        val h = harness(issues = listOf(issue("SF-1")))

        val result = h.post("/api/v1/stories/SF-1/notification-events", """{"notificationEvents":["EROR"]}""")

        assertEquals(400, result.response.status)
        assertEquals("INVALID_PARAMS", h.json(result).path("code").asText())
        assertEquals(null, h.tracker.lastFieldUpdate)
    }

    @Test
    fun `notification-events wijst een subtaak af zonder eventset te schrijven`() {
        val subtask = issue("SF-2").copy(parentKey = "SF-1", fields = issue("SF-2").fields.copy(type = "Task"))
        val h = harness(issues = listOf(subtask))

        val result = h.post("/api/v1/stories/SF-2/notification-events", """{"notificationEvents":["ERROR"]}""")

        assertEquals(400, result.response.status)
        assertEquals(null, h.tracker.lastFieldUpdate)
    }

    @Test
    fun `edit werkt alleen de meegegeven velden bij`() {
        val h = harness()

        h.postJson("/api/v1/stories/SF-1/edit", """{"description":"nieuwe omschrijving","aiSupplier":"openai","aiModel":"gpt-5.6-sol"}""")

        assertEquals("SF-1" to "nieuwe omschrijving", h.tracker.lastDescription)
        assertEquals("SF-1", h.tracker.lastFieldUpdate?.first)
    }

    @Test
    fun `edit zonder optionele velden laat de tracker met rust`() {
        val h = harness()

        h.postJson("/api/v1/stories/SF-1/edit", "{}")

        assertEquals(null, h.tracker.lastDescription)
        assertEquals(null, h.tracker.lastFieldUpdate)
    }

    @Test
    fun `edit met een lege aiModel wist het eerder ingestelde model`() {
        val h = harness()

        h.postJson("/api/v1/stories/SF-1/edit", """{"aiModel":""}""")

        assertEquals("", h.tracker.lastFieldUpdate?.second?.values?.get(TrackerField.AI_MODEL))
    }

    @Test
    fun `command zet het commando met reden in de wachtrij bij de orchestrator`() {
        val h = harness()

        h.postJson("/api/v1/stories/SF-1/command/approve")
        assertEquals(Triple("SF-1", FactoryCommand.APPROVE, null), h.orchestrator.lastCommand)

        h.postJson("/api/v1/stories/SF-1/command/reject", """{"reason":"nog niet goed"}""")
        assertEquals(Triple("SF-1", FactoryCommand.REJECT, "nog niet goed"), h.orchestrator.lastCommand)
    }

    @Test
    fun `command met een onbekend commando geeft 400`() {
        val h = harness()

        val result = h.post("/api/v1/stories/SF-1/command/nonsense")

        assertEquals(400, result.response.status)
        assertEquals("INVALID_PARAMS", h.json(result).path("code").asText())
    }

    @Test
    fun `maintenance-cleanups levert de (soft-failende) cleanup-historie op, ook met projectfilter`() {
        val h = harness()

        assertEquals(0, h.getJson("/api/v1/maintenance/cleanups").path("runs").size())
        assertEquals(0, h.getJson("/api/v1/maintenance/cleanups?project=sf").path("runs").size())
    }

    @Test
    fun `maintenance-cleanup-detail met een niet-numerieke id geeft 400 en onbekend 404`() {
        val h = harness()

        assertEquals(400, h.get("/api/v1/maintenance/cleanups/geen-getal").response.status)

        val missing = h.get("/api/v1/maintenance/cleanups/404")
        assertEquals(404, missing.response.status)
        assertEquals("NOT_FOUND", h.json(missing).path("code").asText())
    }

    @Test
    fun `maintenance-cleanups meldt welke soorten op dit moment draaien`() {
        val h = harness()
        h.cleanupGuard.tryStart("agent-events")

        val body = h.getJson("/api/v1/maintenance/cleanups")

        assertEquals(1, body.path("runningKinds").size())
        assertEquals("agent-events", body.path("runningKinds").get(0).asText())
    }

    @Test
    fun `maintenance-run start de gevraagde soort en geeft de status terug`() {
        val h = harness()

        val body = h.postJson("/api/v1/maintenance/run", """{"kind":"agent-events"}""")

        assertEquals(listOf("agent-events"), h.cleanupRunNow.requestedKinds)
        assertEquals(true, body.path("started").asBoolean())
        assertEquals("started", body.path("status").asText())
        assertEquals("started", body.path("kinds").path("agent-events").asText())
    }

    @Test
    fun `maintenance-run geeft de alles-waarde ongewijzigd door en weigert een body zonder kind`() {
        val h = harness()

        h.postJson("/api/v1/maintenance/run", """{"kind":"all"}""")
        assertEquals(listOf("all"), h.cleanupRunNow.requestedKinds)

        assertEquals(400, h.post("/api/v1/maintenance/run", "{}").response.status)
    }

    @Test
    fun `een tracker-fout laat stories soft-failen`() {
        // DashboardQueryService.stories() vangt tracker-fouten zelf af (errors-lijst, lege issues);
        // dit dekt dat de API dat gedrag ongewijzigd doorgeeft.
        val h = harness(issues = null)

        val body = h.getJson("/api/v1/stories")

        assertEquals(0, body.path("issues").size())
        assertTrue(body.path("errors").size() > 0)
    }

    @Test
    fun `status meldt versie en starttijd en is altijd verbonden`() {
        val h = harness()

        val body = h.getJson("/api/v1/status")

        assertEquals(true, body.path("connected").asBoolean())
        assertTrue(body.path("since").asText().isNotBlank())
        assertTrue(body.path("factoryVersion").asText().isNotBlank())
    }

    @Test
    fun `events opent een SSE-stream die de heartbeat en changed-events in stand houdt`() {
        val h = harness()

        val result = h.mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/events")
                .header("Authorization", "Bearer ${h.token}"),
        ).andReturn()

        assertTrue(result.request.isAsyncStarted)
        assertEquals(1, h.statusController.openEventConnections())
        h.statusController.sendHeartbeat()
        h.eventBus.notifyChanged()
        assertEquals(1, h.statusController.openEventConnections())
        assertTrue(result.response.contentAsString.contains("event:changed"))
    }
}
