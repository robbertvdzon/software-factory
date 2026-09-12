package nl.vdzon.softwarefactory.web

import nl.vdzon.softwarefactory.core.contracts.TrackerIssue
import nl.vdzon.softwarefactory.core.contracts.TrackerIssueFields
import nl.vdzon.softwarefactory.dashboard.models.StoriesPageData
import nl.vdzon.softwarefactory.web.controllers.DashboardApiErrorHandler
import nl.vdzon.softwarefactory.web.controllers.ProductFactoryIntegrationController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ProductFactoryIntegrationControllerTest {
    private class Ports(existingIssues: List<TrackerIssue> = emptyList()) {
        val queries = object : StubDashboardQueries() {
            override fun stories() = StoriesPageData(issues = existingIssues, runsByStory = emptyMap(), errors = emptyList())
        }
        val commands = StubDashboardCommands()
        val operations = StubFactoryOperations()
        val mvc = MockMvcBuilders.standaloneSetup(
            ProductFactoryIntegrationController(queries, commands, operations, StubVersion, DashboardApiFixtures.fakeSecrets()),
        ).setControllerAdvice(DashboardApiErrorHandler()).build()
    }

    private fun issue(key: String, description: String, storyPhase: String?) = TrackerIssue(
        key = key, summary = "x", status = "open", description = description, comments = emptyList(),
        fields = TrackerIssueFields(targetRepo = null, aiPhase = null, aiTokenBudget = null, aiTokensUsed = null, agentStartedAt = null, paused = false, error = null, storyPhase = storyPhase),
    )

    @Test
    fun `integratieroute vereist het aparte machine-token`() {
        val ports = Ports()
        ports.mvc.perform(get("/api/integrations/v1/status")).andExpect(status().isUnauthorized)
        ports.mvc.perform(get("/api/integrations/v1/status").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized)
        ports.mvc.perform(get("/api/integrations/v1/status").header("Authorization", "Bearer integration-secret"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.connected").value(true))
            .andExpect(jsonPath("$.factoryVersion").value("abc1234"))
    }

    @Test
    fun `nieuwe story krijgt context marker en wordt start-next gezet`() {
        val ports = Ports()
        ports.commands.nextStoryKey = "SF-2100"

        ports.mvc.perform(
            post("/api/integrations/v1/stories")
                .header("Authorization", "Bearer integration-secret")
                .header("Idempotency-Key", "hkh-autopilot:candidate:42")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request()),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.storyKey").value("SF-2100"))
            .andExpect(jsonPath("$.created").value(true))

        assertEquals(listOf("createStory", "queueStory(SF-2100)"), ports.commands.calls)
        val create = ports.commands.createdStories.single()
        assert(create.description!!.contains("Product-Factory-Idempotency-Key: hkh-autopilot:candidate:42"))
        assertEquals("hkh-autopilot", create.repo)
        assertEquals(false, create.start)
    }

    @Test
    fun `notificationEvents, questionsAllowed en approvalMode uit het verzoek overschrijven de defaults`() {
        val ports = Ports()
        ports.mvc.perform(
            post("/api/integrations/v1/stories")
                .header("Authorization", "Bearer integration-secret")
                .header("Idempotency-Key", "product-factory:candidate:1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{
                        "productSlug":"product-factory","title":"x","description":"x","repo":"product-factory",
                        "workspaceRunId":"shadow-product-factory-0012","workspaceCommitSha":"abcdef1234567",
                        "artifactPath":"research/shadow-iteration-0012.md","deliveryMode":"start-next",
                        "notificationEvents":["ERROR"],"questionsAllowed":true,"approvalMode":"automatisch"
                    }""",
                ),
        ).andExpect(status().isCreated)

        val create = ports.commands.createdStories.single()
        assertEquals(setOf("ERROR"), create.notificationEvents.map { it.name }.toSet())
        assertEquals(true, create.questionsAllowed)
        assertEquals("automatisch", create.approvalMode)
    }

    @Test
    fun `ontbrekende notificatie-opties vallen terug op de platformbrede defaults`() {
        val ports = Ports()
        ports.mvc.perform(
            post("/api/integrations/v1/stories")
                .header("Authorization", "Bearer integration-secret")
                .header("Idempotency-Key", "hkh-autopilot:candidate:43")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request()),
        ).andExpect(status().isCreated)

        val create = ports.commands.createdStories.single()
        assertEquals(setOf("DEPLOYED", "QUESTION", "MANUAL_ACTION_REQUIRED", "ERROR"), create.notificationEvents.map { it.name }.toSet())
        assertEquals(true, create.questionsAllowed)
        assertEquals("automatisch", create.approvalMode)
    }

    @Test
    fun `retry met dezelfde sleutel maakt geen dubbele story`() {
        val ports = Ports(listOf(issue("SF-2099", "Product-Factory-Idempotency-Key: hkh-autopilot:candidate:42", storyPhase = "start-next")))

        ports.mvc.perform(
            post("/api/integrations/v1/stories")
                .header("Authorization", "Bearer integration-secret")
                .header("Idempotency-Key", "hkh-autopilot:candidate:42")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request()),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.storyKey").value("SF-2099"))
            .andExpect(jsonPath("$.created").value(false))

        assertEquals(emptyList<String>(), ports.commands.calls)
    }

    @Test
    fun `retry op een bestaande story zonder fase queuet die alsnog`() {
        val ports = Ports(listOf(issue("SF-2099", "Product-Factory-Idempotency-Key: hkh-autopilot:candidate:42", storyPhase = null)))

        ports.mvc.perform(
            post("/api/integrations/v1/stories")
                .header("Authorization", "Bearer integration-secret")
                .header("Idempotency-Key", "hkh-autopilot:candidate:42")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request()),
        ).andExpect(status().isOk)

        assertEquals(listOf("queueStory(SF-2099)"), ports.commands.calls)
    }

    @Test
    fun `antwoordcontract staat alleen bekende overgang toe`() {
        val ports = Ports()
        ports.mvc.perform(
            post("/api/integrations/v1/stories/SF-1/answers")
                .header("Authorization", "Bearer integration-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"targetType":"story","targetKey":"SF-1","phase":"questions-answered","answer":"Gebruik de bestaande productvisie."}"""),
        ).andExpect(status().isOk)
        assertEquals("setStoryPhase(SF-1,questions-answered,Gebruik de bestaande productvisie.)", ports.operations.calls.last())

        ports.mvc.perform(
            post("/api/integrations/v1/stories/SF-1/answers")
                .header("Authorization", "Bearer integration-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"subtask\",\"targetKey\":\"SF-2\",\"phase\":\"manual-action-done\",\"answer\":\"Alternatief gekozen.\"}"),
        ).andExpect(status().isOk)
        assertEquals("setSubtaskPhase(SF-2,manual-action-done,Alternatief gekozen.)", ports.operations.calls.last())
    }

    @Test
    fun `onbekende deliveryMode, lege titel en ongeldige sleutels geven 400 zonder de factory te raken`() {
        val ports = Ports()
        fun attempt(body: String, key: String? = "hkh-autopilot:candidate:44") = ports.mvc.perform(
            post("/api/integrations/v1/stories")
                .header("Authorization", "Bearer integration-secret")
                .apply { if (key != null) header("Idempotency-Key", key) }
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
        ).andExpect(status().isBadRequest)

        attempt(request(deliveryMode = "bogus"))
        attempt(request(title = ""))
        attempt(request(), key = null)
        attempt(request(), key = "short")
        attempt(request(productSlug = "Niet Geldig"))
        attempt(request(workspaceCommitSha = "zzz"))
        assertEquals(emptyList<String>(), ports.commands.calls)
    }

    @Test
    fun `ongeldig antwoordverzoek geeft 400 en raakt de factory niet`() {
        val ports = Ports()
        val cases = listOf(
            """{"targetType":"story","targetKey":"SF-1","phase":"questions-answered","answer":"   "}""",
            """{"targetType":"epic","targetKey":"SF-1","phase":"questions-answered","answer":"ok"}""",
            """{"targetType":"story","targetKey":"SF-9","phase":"questions-answered","answer":"ok"}""",
            """{"targetType":"story","targetKey":"SF-1","phase":"onbekend","answer":"ok"}""",
            """{"targetType":"subtask","targetKey":"SF-2","phase":"onbekend","answer":"ok"}""",
        )
        cases.forEach { body ->
            ports.mvc.perform(
                post("/api/integrations/v1/stories/SF-1/answers")
                    .header("Authorization", "Bearer integration-secret")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest)
        }
        assertEquals(emptyList<String>(), ports.operations.calls)
    }

    private fun request(
        productSlug: String = "hkh-autopilot",
        title: String = "Historische kaart",
        workspaceCommitSha: String = "abcdef1234567",
        deliveryMode: String = "start-next",
    ) = """{
        "productSlug":"$productSlug","title":"$title","description":"Maak de kaart.",
        "repo":"hkh-autopilot","workspaceRunId":"shadow-hkh-autopilot-0004",
        "workspaceCommitSha":"$workspaceCommitSha","artifactPath":"research/shadow-iteration-0004.md",
        "deliveryMode":"$deliveryMode"
    }"""
}
