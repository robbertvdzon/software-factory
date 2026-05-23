package nl.vdzon.softwarefactory.jira

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import nl.vdzon.softwarefactory.config.FactorySecrets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.http.HttpClient

class AtlassianJiraClientTest {
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    @Test
    fun `finds AI issues maps fields updates comments and processed markers`() {
        FakeJiraServer().use { server ->
            val client = client(server)

            val issues = client.findAiIssues(projectKey = "KAN")

            assertEquals(1, issues.size)
            val issue = issues.single()
            assertEquals("KAN-69", issue.key)
            assertEquals("Create first app", issue.summary)
            assertEquals("AI", issue.status)
            assertEquals("git@github.com:robbertvdzon/sample-build-project.git", issue.fields.targetRepo)
            assertEquals("refined-finished", issue.fields.aiPhase)
            assertEquals(5, issue.fields.aiLevel)
            assertEquals(100000, issue.fields.aiTokenBudget)
            assertEquals(42, issue.fields.aiTokensUsed)
            assertFalse(issue.fields.paused)
            assertEquals("Please build it", issue.comments.single().body)
            assertFalse(issue.comments.single().isAgentComment)
            assertTrue(server.requests.any { it.method == "GET" && it.path == "/rest/api/3/search/jql" })

            client.updateIssueFields(
                issue.key,
                JiraFieldUpdate.of(
                    JiraKnownField.AI_PHASE to "developing",
                    JiraKnownField.PAUSED to true,
                    JiraKnownField.ERROR to null,
                    JiraKnownField.AI_TOKENS_USED to 123L,
                ),
            )

            val updateRequest = server.requests.last { it.method == "PUT" && it.path == "/rest/api/3/issue/KAN-69" }
            val updateFields = objectMapper.readTree(updateRequest.body).path("fields")
            assertEquals("developing", updateFields.path("customfield_10043").asText())
            assertEquals("true", updateFields.path("customfield_10079").asText())
            assertTrue(updateFields.path("customfield_10080").isNull)
            assertEquals(123L, updateFields.path("customfield_10042").asLong())

            val comment = client.postAgentComment(issue.key, AgentRole.DEVELOPER, "implementation complete")

            assertEquals("20000", comment.id)
            assertEquals("[DEVELOPER] implementation complete", comment.body)
            val commentRequest = server.requests.last { it.method == "POST" && it.path == "/rest/api/3/issue/KAN-69/comment" }
            assertTrue(commentRequest.body.contains("[DEVELOPER] implementation complete"))

            assertFalse(client.hasProcessedCommentMarker("10001", AgentRole.DEVELOPER))
            assertTrue(client.markCommentProcessed("10001", AgentRole.DEVELOPER))

            assertNotNull(
                server.requests.lastOrNull {
                    it.method == "PUT" &&
                        it.path == "/rest/api/3/comment/10001/properties/software-factory-processed-developer"
                },
            )
        }
    }

    @Test
    fun `fails when a required Jira custom field is missing`() {
        FakeJiraServer(fieldsJson = FakeJiraServer.requiredFieldsJsonWithoutError()).use { server ->
            val client = client(server)

            val exception = assertThrows(MissingJiraFieldException::class.java) {
                client.findAiIssues(projectKey = "KAN")
            }

            assertEquals("Missing required Jira custom field: Error", exception.message)
        }
    }

    private fun client(server: FakeJiraServer): AtlassianJiraClient =
        AtlassianJiraClient(
            factorySecrets = FactorySecrets(
                jiraBaseUrl = server.baseUrl,
                jiraEmail = "robbert@example.com",
                jiraApiKey = "jira-token",
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

    private class FakeJiraServer(
        private val fieldsJson: String = requiredFieldsJson(),
    ) : AutoCloseable {
        private val server: HttpServer = HttpServer.create(InetSocketAddress(0), 0)
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
                query = exchange.requestURI.rawQuery ?: "",
                body = body,
            )
            requests += request

            when {
                request.method == "GET" && request.path == "/rest/api/3/field" ->
                    exchange.json(200, fieldsJson)

                request.method == "GET" && request.path == "/rest/api/3/search/jql" ->
                    exchange.json(200, searchJson())

                request.method == "PUT" && request.path == "/rest/api/3/issue/KAN-69" ->
                    exchange.text(204, "")

                request.method == "POST" && request.path == "/rest/api/3/issue/KAN-69/comment" ->
                    exchange.json(201, postedCommentJson())

                request.method == "GET" &&
                    request.path == "/rest/api/3/comment/10001/properties/software-factory-processed-developer" ->
                    exchange.json(404, """{"errorMessages":["not found"]}""")

                request.method == "PUT" &&
                    request.path == "/rest/api/3/comment/10001/properties/software-factory-processed-developer" ->
                    exchange.json(201, """{"role":"developer"}""")

                else -> exchange.json(404, """{"errorMessages":["unexpected ${request.method} ${request.path}"]}""")
            }
        }

        private fun HttpExchange.json(status: Int, body: String) {
            responseHeaders.add("Content-Type", "application/json")
            text(status, body)
        }

        private fun HttpExchange.text(status: Int, body: String) {
            val bytes = body.toByteArray()
            sendResponseHeaders(status, if (status == 204) -1 else bytes.size.toLong())
            if (status != 204) {
                responseBody.use { it.write(bytes) }
            } else {
                close()
            }
        }

        data class FakeRequest(
            val method: String,
            val path: String,
            val query: String,
            val body: String,
        )

        companion object {
            fun requiredFieldsJson(): String =
                """
                [
                  {"id":"customfield_10077","name":"Target Repo","schema":{"type":"string"}},
                  {"id":"customfield_10043","name":"AI Phase","schema":{"type":"string"}},
                  {"id":"customfield_10040","name":"AI Level","schema":{"type":"number"}},
                  {"id":"customfield_10041","name":"AI Token Budget","schema":{"type":"number"}},
                  {"id":"customfield_10042","name":"AI Tokens Used","schema":{"type":"number"}},
                  {"id":"customfield_10078","name":"AgentStartedAt","schema":{"type":"datetime"}},
                  {"id":"customfield_10079","name":"Paused","schema":{"type":"string"}},
                  {"id":"customfield_10080","name":"Error","schema":{"type":"string"}}
                ]
                """.trimIndent()

            fun requiredFieldsJsonWithoutError(): String =
                """
                [
                  {"id":"customfield_10077","name":"Target Repo","schema":{"type":"string"}},
                  {"id":"customfield_10043","name":"AI Phase","schema":{"type":"string"}},
                  {"id":"customfield_10040","name":"AI Level","schema":{"type":"number"}},
                  {"id":"customfield_10041","name":"AI Token Budget","schema":{"type":"number"}},
                  {"id":"customfield_10042","name":"AI Tokens Used","schema":{"type":"number"}},
                  {"id":"customfield_10078","name":"AgentStartedAt","schema":{"type":"datetime"}},
                  {"id":"customfield_10079","name":"Paused","schema":{"type":"string"}}
                ]
                """.trimIndent()

            fun searchJson(): String =
                """
                {
                  "isLast": true,
                  "issues": [
                    {
                      "key": "KAN-69",
                      "fields": {
                        "summary": "Create first app",
                        "status": {"name": "AI"},
                        "customfield_10077": "git@github.com:robbertvdzon/sample-build-project.git",
                        "customfield_10043": "refined-finished",
                        "customfield_10040": 5,
                        "customfield_10041": 100000,
                        "customfield_10042": 42,
                        "customfield_10078": "2026-05-23T10:00:00.000+0200",
                        "customfield_10079": "false",
                        "customfield_10080": null,
                        "comment": {
                          "comments": [
                            {
                              "id": "10001",
                              "author": {"accountId": "user-1", "displayName": "Robbert"},
                              "created": "2026-05-23T10:01:00.000+0200",
                              "body": {
                                "type": "doc",
                                "version": 1,
                                "content": [
                                  {
                                    "type": "paragraph",
                                    "content": [{"type": "text", "text": "Please build it"}]
                                  }
                                ]
                              }
                            }
                          ],
                          "total": 1
                        }
                      }
                    }
                  ]
                }
                """.trimIndent()

            fun postedCommentJson(): String =
                """
                {
                  "id": "20000",
                  "author": {"accountId": "factory", "displayName": "Factory"},
                  "created": "2026-05-23T10:02:00.000+0200",
                  "body": {
                    "type": "doc",
                    "version": 1,
                    "content": [
                      {
                        "type": "paragraph",
                        "content": [{"type": "text", "text": "[DEVELOPER] implementation complete"}]
                      }
                    ]
                  }
                }
                """.trimIndent()
        }
    }
}
