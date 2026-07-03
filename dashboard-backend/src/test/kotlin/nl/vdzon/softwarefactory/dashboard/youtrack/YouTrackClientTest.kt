package nl.vdzon.softwarefactory.dashboard.youtrack

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import nl.vdzon.softwarefactory.config.ProjectRepoResolver
import nl.vdzon.softwarefactory.dashboard.config.DashboardSecrets
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test van de gemoderniseerde query-/parse-logica tegen een fake YouTrack-HTTP-server
 * (zelfde aanpak als softwarefactory's YouTrackClientTest, maar kleiner): het gaat om
 * wélke query het dashboard stuurt en hoe het huidige veldmodel gemapt wordt.
 */
class YouTrackClientTest {
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    @Test
    fun `queries stories with a Story Phase and maps the current field model`() {
        FakeYouTrackServer().use { server ->
            val client = client(server)

            val issues = client.findWorkIssues()

            // De query selecteert op het huidige procesmodel: fase gezet + nog niet Done.
            val issueRequest = server.requests.first { it.method == "GET" && it.path == "/api/issues" }
            assertContains(issueRequest.query, "project: SP")
            assertContains(issueRequest.query, "has: {Story Phase}")
            assertContains(issueRequest.query, "State: -Done")
            // ... en NIET meer op het verdwenen Stage-veld.
            assertFalse(issueRequest.query.contains("Stage"))

            // SP-3 (AI-supplier none) is weggefilterd.
            assertEquals(listOf("SP-1", "SP-2", "SP-4"), issues.map { it.key })

            val story = issues.first { it.key == "SP-1" }
            assertEquals("Story one", story.summary)
            // status = de waarde van het `Story Phase`-veld.
            assertEquals("in-progress", story.status)
            // Repo-veld met projectnaam → geresolvede repo-URL uit projects.yaml.
            assertEquals("https://github.com/robbert/personal-feed.git", story.targetRepo)
            assertEquals("claude", story.aiSupplier)
            assertEquals("developing", story.aiPhase)
            assertEquals(5, story.aiLevel)
            assertEquals(100000L, story.aiTokenBudget)
            assertEquals(42L, story.aiTokensUsed)
            assertFalse(story.paused)
            assertEquals("kapot", story.error)

            // Repo-veld met een directe URL (geen projectnaam) → ongewijzigd doorgegeven.
            assertEquals("https://github.com/acme/direct.git", issues.first { it.key == "SP-2" }.targetRepo)
        }
    }

    @Test
    fun `resolves an unknown Repo value to null instead of parsing descriptions`() {
        FakeYouTrackServer().use { server ->
            val client = client(server, resolver = ProjectRepoResolver(emptyMap()))

            val issues = client.findWorkIssues()

            // Zonder projects.yaml-match blijft een projectnaam gewoon de veldwaarde (URL-gedrag);
            // een issue zonder Repo-veld heeft geen targetRepo — er wordt niets meer uit
            // projectbeschrijvingen ("factory.repo=") geparst.
            assertEquals("personal-feed", issues.first { it.key == "SP-1" }.targetRepo)
            assertNull(issues.first { it.key == "SP-4" }.targetRepo)
        }
    }

    @Test
    fun `caches the work issues within the ttl`() {
        FakeYouTrackServer().use { server ->
            var now = 1_000_000L
            val client = client(server, clock = { now })

            client.findWorkIssues()
            val callsAfterFirst = server.requests.count { it.path == "/api/issues" }
            client.findWorkIssues()
            // Binnen de TTL: geen nieuwe YouTrack-calls, de gecachte lijst wordt hergebruikt.
            assertEquals(callsAfterFirst, server.requests.count { it.path == "/api/issues" })

            now += 60_000
            client.findWorkIssues()
            // Na de TTL wordt er wél opnieuw opgehaald.
            assertTrue(server.requests.count { it.path == "/api/issues" } > callsAfterFirst)
        }
    }

    @Test
    fun `createIssue sets Repo field and Story Phase start instead of description text`() {
        FakeYouTrackServer().use { server ->
            val client = client(server)

            client.createIssue(
                projectKey = "SP",
                targetRepo = "personal-feed",
                aiSupplier = "claude",
                aiModel = "claude-opus-4-8",
                budget = 50_000L,
                title = "Nieuwe story",
                description = "Doe iets nuttigs",
            )

            // De repo hoort niet meer als "Repo: ..."-tekst in de description.
            val createRequest = server.requests.first { it.method == "POST" && it.path == "/api/issues" }
            val createBody = objectMapper.readTree(createRequest.body)
            assertEquals("Doe iets nuttigs", createBody.path("description").asText())
            assertFalse(createBody.path("description").asText().contains("Repo:"))

            val updateRequest = server.requests.first { it.method == "POST" && it.path == "/api/issues/SP-9" }
            val fields = objectMapper.readTree(updateRequest.body).path("customFields")
            fun field(name: String) = fields.first { it.path("name").asText() == name }
            // Zelfde velden als de factory bij story-aanmaak: Type, Repo (multi-enum) en fase start.
            assertEquals("User Story", field("Type").path("value").path("name").asText())
            assertEquals("MultiEnumIssueCustomField", field("Repo").path("\$type").asText())
            assertEquals("personal-feed", field("Repo").path("value").first().path("name").asText())
            assertEquals("start", field("Story Phase").path("value").path("name").asText())
            assertEquals("claude", field("AI-supplier").path("value").path("name").asText())
            assertEquals("claude-opus-4-8", field("AI Model").path("value").path("name").asText())
            assertEquals(50_000L, field("AI Token Budget").path("value").asLong())
            // Het oude model zette Stage: Develop — dat veld bestaat niet meer.
            assertTrue(fields.none { it.path("name").asText() == "Stage" })
        }
    }

    private fun client(
        server: FakeYouTrackServer,
        resolver: ProjectRepoResolver = ProjectRepoResolver(
            mapOf("personal-feed" to "https://github.com/robbert/personal-feed.git"),
        ),
        clock: () -> Long = System::currentTimeMillis,
    ): YouTrackClient =
        YouTrackClient(
            secrets = DashboardSecrets(
                youTrackBaseUrl = server.baseUrl,
                youTrackToken = "token",
                youTrackProjects = listOf("SP"),
                githubToken = "gh",
                databaseUrl = "postgresql://user:pass@localhost:5432/db",
                databaseSchema = "software_factory",
                dashboardUsername = "admin",
                dashboardPassword = "secret",
                rememberSecret = "remember",
            ),
            projectRepoResolver = resolver,
            clock = clock,
        )
}

private data class RecordedRequest(
    val method: String,
    val path: String,
    val query: String,
    val body: String,
)

private class FakeYouTrackServer : Closeable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(0), 0)
    val requests = mutableListOf<RecordedRequest>()
    val baseUrl: String get() = "http://localhost:${server.address.port}"

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val path = exchange.requestURI.path
        val query = URLDecoder.decode(exchange.requestURI.rawQuery.orEmpty(), StandardCharsets.UTF_8)
        synchronized(requests) {
            requests.add(RecordedRequest(exchange.requestMethod, path, query, body))
        }
        val response = respond(exchange.requestMethod, path)
        val bytes = response.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun respond(method: String, path: String): String = when {
        path == "/api/admin/projects" ->
            """[{"id":"0-0","shortName":"SP","name":"Sample Project","archived":false}]"""
        method == "GET" && path == "/api/issues" -> issuesJson
        method == "POST" && path == "/api/issues" -> """{"idReadable":"SP-9"}"""
        method == "GET" && path == "/api/issues/SP-9" -> createdIssueJson
        else -> "{}"
    }

    override fun close() {
        server.stop(0)
    }

    companion object {
        private val issuesJson = """
            [
              {
                "idReadable": "SP-1",
                "summary": "Story one",
                "description": "Bouw feature X",
                "customFields": [
                  {"name": "Story Phase", "value": {"name": "in-progress"}},
                  {"name": "Repo", "value": [{"name": "personal-feed"}]},
                  {"name": "AI-supplier", "value": {"name": "claude"}},
                  {"name": "AI Phase", "value": {"name": "developing"}},
                  {"name": "AI Level", "value": 5},
                  {"name": "AI Token Budget", "value": 100000},
                  {"name": "AI Tokens Used", "value": 42},
                  {"name": "Paused", "value": {"name": "false"}},
                  {"name": "Error", "value": {"text": "kapot"}}
                ]
              },
              {
                "idReadable": "SP-2",
                "summary": "Story with direct repo url",
                "customFields": [
                  {"name": "Story Phase", "value": {"name": "start"}},
                  {"name": "Repo", "value": [{"name": "https://github.com/acme/direct.git"}]},
                  {"name": "AI-supplier", "value": {"name": "claude"}}
                ]
              },
              {
                "idReadable": "SP-3",
                "summary": "Story without AI supplier",
                "customFields": [
                  {"name": "Story Phase", "value": {"name": "start"}},
                  {"name": "AI-supplier", "value": {"name": "none"}}
                ]
              },
              {
                "idReadable": "SP-4",
                "summary": "Story without repo",
                "customFields": [
                  {"name": "Story Phase", "value": {"name": "refining"}},
                  {"name": "AI-supplier", "value": {"name": "claude"}}
                ]
              }
            ]
        """.trimIndent()

        private val createdIssueJson = """
            {
              "idReadable": "SP-9",
              "summary": "Nieuwe story",
              "description": "Doe iets nuttigs",
              "customFields": [
                {"name": "Story Phase", "value": {"name": "start"}},
                {"name": "Repo", "value": [{"name": "personal-feed"}]},
                {"name": "AI-supplier", "value": {"name": "claude"}}
              ]
            }
        """.trimIndent()
    }
}
