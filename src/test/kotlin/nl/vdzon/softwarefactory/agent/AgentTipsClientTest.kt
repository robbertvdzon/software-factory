package nl.vdzon.softwarefactory.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import nl.vdzon.softwarefactory.youtrack.AgentRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.http.HttpClient

class AgentTipsClientTest {
    @Test
    fun `fetches tips as markdown and posts updates`() {
        FakeKnowledgeServer().use { server ->
            val client = AgentTipsClient(HttpClient.newHttpClient(), jacksonObjectMapper())

            val markdown = client.fetchMarkdown(
                orchestratorUrl = server.baseUrl,
                targetRepo = "git@github.com:robbertvdzon/sample-build-project.git",
                role = AgentRole.TESTER,
            )
            client.postUpdates(
                orchestratorUrl = server.baseUrl,
                targetRepo = "git@github.com:robbertvdzon/sample-build-project.git",
                ticketKey = "KAN-11",
                role = AgentRole.TESTER,
                updates = listOf(AgentKnowledgeDraft("login", "admin", "Gebruik seeded test-user.")),
            )

            assertTrue(markdown.orEmpty().contains("## login / admin"))
            assertTrue(markdown.orEmpty().contains("Gebruik preview login."))
            assertTrue(server.requests.any { it.method == "GET" && it.query.contains("role=tester") })
            val post = server.requests.single { it.method == "POST" }
            assertEquals("/agent-knowledge/update", post.path)
            assertTrue(post.body.contains("KAN-11"))
            assertTrue(post.body.contains("Gebruik seeded test-user."))
        }
    }

    private class FakeKnowledgeServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress(0), 0)
        val requests = mutableListOf<Request>()
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
            val request = Request(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                query = exchange.requestURI.rawQuery.orEmpty(),
                body = exchange.requestBody.bufferedReader().readText(),
            )
            requests += request
            when {
                request.method == "GET" && request.path == "/agent-knowledge" ->
                    exchange.json(
                        200,
                        """
                        [
                          {
                            "targetRepo": "github.com/robbertvdzon/sample-build-project",
                            "role": "tester",
                            "category": "login",
                            "key": "admin",
                            "content": "Gebruik preview login.",
                            "updatedByStory": "KAN-1",
                            "updatedAt": "2026-05-24T12:00:00Z"
                          }
                        ]
                        """.trimIndent(),
                    )
                request.method == "POST" && request.path == "/agent-knowledge/update" ->
                    exchange.json(200, """{"ok":true}""")
                else -> exchange.json(404, """{"error":"unexpected"}""")
            }
        }

        private fun HttpExchange.json(status: Int, body: String) {
            responseHeaders.add("Content-Type", "application/json")
            val bytes = body.toByteArray()
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }

        data class Request(
            val method: String,
            val path: String,
            val query: String,
            val body: String,
        )
    }
}
