package nl.vdzon.softwarefactory.youtrack

import nl.vdzon.softwarefactory.youtrack.*
import nl.vdzon.softwarefactory.youtrack.*
import nl.vdzon.softwarefactory.youtrack.*
import nl.vdzon.softwarefactory.youtrack.*

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import nl.vdzon.softwarefactory.config.FactorySecrets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets

class YouTrackClientTest {
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    @Test
    fun `bootstraps project schema and finds develop issues with AI supplier`() {
        FakeYouTrackServer(missingAiSupplierField = true).use { server ->
            val client = client(server)

            val projects = client.ensureConfiguredProjects()
            val issues = client.findWorkIssues()

            assertEquals(listOf("SP"), projects.map { it.key })
            assertTrue(server.requests.any { it.method == "POST" && it.path == "/api/admin/customFieldSettings/customFields" })
            assertTrue(server.requests.any { it.method == "POST" && it.path == "/api/admin/projects/0-0/customFields" })
            assertTrue(server.requests.any { it.method == "POST" && it.path.endsWith("/bundle/values") && it.body.contains("mock") })
            assertTrue(server.requests.any { it.method == "POST" && it.path.endsWith("/bundle/values") && it.body.contains("claude") })

            val issue = issues.single()
            assertEquals("SP-1", issue.key)
            assertEquals("Create first app", issue.summary)
            assertEquals("Build the first generated app.", issue.description)
            assertEquals("Develop", issue.status)
            assertEquals("git@github.com:robbertvdzon/sample-build-project.git", issue.fields.targetRepo)
            assertEquals("claude", issue.fields.aiSupplier)
            assertEquals("refined-finished", issue.fields.aiPhase)
            assertEquals(5, issue.fields.aiLevel)
            assertEquals(100000L, issue.fields.aiTokenBudget)
            assertEquals(42L, issue.fields.aiTokensUsed)
            assertFalse(issue.fields.paused)
            assertEquals("Please build it", issue.comments.single().body)
            assertFalse(issue.comments.single().isAgentComment)
        }
    }

    @Test
    fun `updates fields comments markers transitions summary and deletes agent comments`() {
        FakeYouTrackServer().use { server ->
            val client = client(server)

            client.updateIssueFields(
                "SP-1",
                TrackerFieldUpdate.of(
                    TrackerField.AI_SUPPLIER to "openai",
                    TrackerField.AI_PHASE to "developing",
                    TrackerField.PAUSED to true,
                    TrackerField.ERROR to "Needs manual triage",
                    TrackerField.AI_TOKENS_USED to 123L,
                ),
            )
            client.transitionIssue("SP-1", "Done")
            val comment = client.postAgentComment("SP-1", AgentRole.DEVELOPER, "implementation complete")
            val markedBefore = client.hasProcessedCommentMarker("SP-1", "c-1", AgentRole.DEVELOPER)
            val marked = client.markCommentProcessed("SP-1", "c-1", AgentRole.DEVELOPER)
            client.updateIssueSummary("SP-1", "(CANCELLED) Create first app")
            val deleted = client.deleteAgentComments("SP-1")

            val updateRequest = server.requests.first { it.method == "POST" && it.path == "/api/issues/SP-1" }
            val customFields = objectMapper.readTree(updateRequest.body).path("customFields")
            assertEquals("AI-supplier", customFields[0].path("name").asText())
            assertEquals("openai", customFields[0].path("value").path("name").asText())
            assertEquals("AI Phase", customFields[1].path("name").asText())
            assertEquals("developing", customFields[1].path("value").path("name").asText())
            assertEquals("Paused", customFields[2].path("name").asText())
            assertEquals("true", customFields[2].path("value").path("name").asText())
            assertEquals("TextIssueCustomField", customFields[3].path("\$type").asText())
            assertEquals("TextFieldValue", customFields[3].path("value").path("\$type").asText())
            assertEquals("Needs manual triage", customFields[3].path("value").path("text").asText())
            assertEquals(123L, customFields[4].path("value").asLong())

            val transitionRequest = server.requests.single { it.method == "POST" && it.path == "/api/commands" }
            assertTrue(transitionRequest.body.contains("Stage Done"))
            assertTrue(transitionRequest.body.contains("SP-1"))
            assertEquals("c-new", comment.id)
            assertEquals("[DEVELOPER] implementation complete", comment.body)
            assertFalse(markedBefore)
            assertTrue(marked)
            assertNotNull(server.requests.lastOrNull { it.method == "POST" && it.path == "/api/issues/SP-1/comments/c-1/reactions" })
            assertTrue(server.requests.any { it.method == "POST" && it.path == "/api/issues/SP-1" && it.body.contains("(CANCELLED)") })
            assertEquals(1, deleted)
            assertNotNull(server.requests.lastOrNull { it.method == "DELETE" && it.path == "/api/issues/SP-1/comments/c-2" })
        }
    }

    private fun client(server: FakeYouTrackServer): YouTrackClient =
        YouTrackClient(
            factorySecrets = FactorySecrets(
                youTrackBaseUrl = server.baseUrl,
                youTrackToken = "youtrack-token",
                youTrackProjects = emptyList(),
                githubToken = "github-token",
                factoryDatabaseUrl = "postgresql://example/db",
                factoryDatabaseSchema = "software_factory",
                kubeconfig = null,
                aiCredentialsDir = null,
                aiOauthToken = null,
                loadedFrom = "test",
            ),
            objectMapper = objectMapper,
            httpClient = HttpClient.newHttpClient(),
        )

    private class FakeYouTrackServer(
        private val missingAiSupplierField: Boolean = false,
    ) : AutoCloseable {
        private val server: HttpServer = HttpServer.create(InetSocketAddress(0), 0)
        private var aiSupplierAttached = !missingAiSupplierField
        val requests: MutableList<FakeRequest> = mutableListOf()
        val baseUrl: String
            get() = "http://localhost:${server.address.port}"

        init {
            server.createContext("/") { exchange -> handle(exchange) }
            server.start()
        }

        override fun close() {
            server.stop(0)
        }

        private fun handle(exchange: HttpExchange) {
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val request = FakeRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                query = exchange.requestURI.rawQuery.orEmpty(),
                body = body,
            )
            requests += request

            when {
                request.method == "GET" && request.path == "/api/admin/projects" ->
                    exchange.json(200, projectsJson())

                request.method == "GET" && request.path == "/api/admin/customFieldSettings/customFields" ->
                    exchange.json(200, globalFieldsJson())

                request.method == "POST" && request.path == "/api/admin/customFieldSettings/customFields" ->
                    exchange.json(200, """{"id":"cf-ai-supplier","name":"AI-supplier","fieldType":{"id":"enum[1]"}}""")

                request.method == "GET" && request.path == "/api/admin/projects/0-0/customFields" ->
                    exchange.json(200, projectFieldsJson())

                request.method == "POST" && request.path == "/api/admin/projects/0-0/customFields" -> {
                    aiSupplierAttached = true
                    exchange.json(
                        200,
                        """
                        {
                          "id": "pf-ai-supplier",
                          "${'$'}type": "EnumProjectCustomField",
                          "field": {"name": "AI-supplier"},
                          "bundle": {"id": "bundle-ai-supplier", "values": []}
                        }
                        """.trimIndent(),
                    )
                }

                request.method == "POST" && request.path.endsWith("/bundle/values") ->
                    exchange.json(200, """{"id":"new-value","name":"ok"}""")

                request.method == "GET" && request.path == "/api/issues" ->
                    exchange.json(200, searchIssuesJson())

                request.method == "GET" && request.path == "/api/issues/SP-1" ->
                    exchange.json(200, issueJson(includeAgentComment = true))

                request.method == "POST" && request.path == "/api/issues/SP-1" ->
                    exchange.json(200, """{"idReadable":"SP-1","summary":"Create first app","customFields":[]}""")

                request.method == "POST" && request.path == "/api/commands" ->
                    exchange.json(200, """{"id":"command"}""")

                request.method == "POST" && request.path == "/api/issues/SP-1/comments" ->
                    exchange.json(200, postedCommentJson())

                request.method == "GET" && request.path == "/api/issues/SP-1/comments/c-1/reactions" ->
                    exchange.json(200, "[]")

                request.method == "POST" && request.path == "/api/issues/SP-1/comments/c-1/reactions" ->
                    exchange.json(200, """{"id":"reaction-1","reaction":"eyes"}""")

                request.method == "DELETE" && request.path == "/api/issues/SP-1/comments/c-2" ->
                    exchange.text(204, "")

                else -> exchange.json(404, """{"error":"unexpected ${request.method} ${request.path} ${request.query}"}""")
            }
        }

        private fun HttpExchange.json(status: Int, body: String) {
            responseHeaders.add("Content-Type", "application/json")
            text(status, body)
        }

        private fun HttpExchange.text(status: Int, body: String) {
            val bytes = body.toByteArray()
            sendResponseHeaders(status, if (status == 204) -1 else bytes.size.toLong())
            if (status == 204) {
                close()
            } else {
                responseBody.use { it.write(bytes) }
            }
        }

        private fun projectsJson(): String =
            """
            [
              {
                "id": "0-0",
                "name": "Sample project",
                "shortName": "SP",
                "description": "factory.githubRepo = git@github.com:robbertvdzon/sample-build-project.git",
                "archived": false
              },
              {
                "id": "0-1",
                "name": "No factory repo",
                "shortName": "NF",
                "description": "",
                "archived": false
              }
            ]
            """.trimIndent()

        private fun globalFieldsJson(): String {
            val aiSupplier = if (missingAiSupplierField) "" else """{"id":"cf-ai-supplier","name":"AI-supplier","fieldType":{"id":"enum[1]"}}, """
            return """
            [
              $aiSupplier
              {"id":"cf-phase","name":"AI Phase","fieldType":{"id":"enum[1]"}},
              {"id":"cf-level","name":"AI Level","fieldType":{"id":"integer"}},
              {"id":"cf-budget","name":"AI Token Budget","fieldType":{"id":"integer"}},
              {"id":"cf-used","name":"AI Tokens Used","fieldType":{"id":"integer"}},
              {"id":"cf-started","name":"AgentStartedAt","fieldType":{"id":"date and time"}},
              {"id":"cf-paused","name":"Paused","fieldType":{"id":"enum[1]"}},
              {"id":"cf-error","name":"Error","fieldType":{"id":"text"}}
            ]
            """.trimIndent()
        }

        private fun projectFieldsJson(): String {
            val aiSupplier = if (aiSupplierAttached) {
                projectField("pf-ai-supplier", "AI-supplier", "EnumProjectCustomField", "none", "mock", "claude", "openai", "microsoft") + ","
            } else {
                ""
            }
            return """
            [
              ${projectField("pf-stage", "Stage", "StateProjectCustomField", "Backlog", "Develop", "Review", "Done")},
              $aiSupplier
              ${projectField("pf-phase", "AI Phase", "EnumProjectCustomField", "refined-finished", "developing", "developed", "tested-successfully")},
              ${projectField("pf-level", "AI Level", "SimpleProjectCustomField")},
              ${projectField("pf-budget", "AI Token Budget", "SimpleProjectCustomField")},
              ${projectField("pf-used", "AI Tokens Used", "SimpleProjectCustomField")},
              ${projectField("pf-started", "AgentStartedAt", "SimpleProjectCustomField")},
              ${projectField("pf-paused", "Paused", "EnumProjectCustomField", "false", "true")},
              ${projectField("pf-error", "Error", "TextProjectCustomField")}
            ]
            """.trimIndent()
        }

        private fun projectField(id: String, name: String, type: String, vararg values: String): String {
            val valuesJson = values.joinToString(",") { """{"id":"value-$it","name":"$it"}""" }
            return """
            {
              "id": "$id",
              "${'$'}type": "$type",
              "field": {"id": "cf-$id", "name": "$name", "fieldType": {"id": "unused"}},
              "bundle": {"id": "bundle-$id", "values": [$valuesJson]}
            }
            """.trimIndent()
        }

        private fun searchIssuesJson(): String =
            if (decodedQuery().contains("Stage: Develop")) {
                "[${issueJson(includeAgentComment = false)}]"
            } else {
                "[]"
            }

        private fun decodedQuery(): String =
            requests.lastOrNull { it.path == "/api/issues" }?.query
                ?.split("&")
                ?.firstOrNull { it.startsWith("query=") }
                ?.substringAfter("=")
                ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
                .orEmpty()

        private fun issueJson(includeAgentComment: Boolean): String {
            val agentComment = if (includeAgentComment) {
                """
                ,
                {
                  "id": "c-2",
                  "text": "[DEVELOPER] implementation complete",
                  "author": {"login": "factory", "fullName": "Factory"},
                  "created": 1771754520000
                }
                """.trimIndent()
            } else {
                ""
            }
            return """
            {
              "id": "2-1",
              "idReadable": "SP-1",
              "summary": "Create first app",
              "description": "Build the first generated app.",
              "project": {
                "id": "0-0",
                "name": "Sample project",
                "shortName": "SP",
                "description": "factory.githubRepo = git@github.com:robbertvdzon/sample-build-project.git"
              },
              "customFields": [
                {"name": "Stage", "value": {"name": "Develop"}},
                {"name": "AI-supplier", "value": {"name": "claude"}},
                {"name": "AI Phase", "value": {"name": "refined-finished"}},
                {"name": "AI Level", "value": 5},
                {"name": "AI Token Budget", "value": 100000},
                {"name": "AI Tokens Used", "value": 42},
                {"name": "AgentStartedAt", "value": 1771754400000},
                {"name": "Paused", "value": {"name": "false"}},
                {"name": "Error", "value": null}
              ],
              "comments": [
                {
                  "id": "c-1",
                  "text": "Please build it",
                  "author": {"login": "robbert", "fullName": "Robbert"},
                  "created": 1771754460000
                }
                $agentComment
              ]
            }
            """.trimIndent()
        }

        private fun postedCommentJson(): String =
            """
            {
              "id": "c-new",
              "text": "[DEVELOPER] implementation complete",
              "author": {"login": "factory", "fullName": "Factory"},
              "created": 1771754520000
            }
            """.trimIndent()

        data class FakeRequest(
            val method: String,
            val path: String,
            val query: String,
            val body: String,
        )
    }
}
