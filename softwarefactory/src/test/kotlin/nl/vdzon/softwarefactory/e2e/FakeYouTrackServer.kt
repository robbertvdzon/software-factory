package nl.vdzon.softwarefactory.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Stateful mini-YouTrack over echte HTTP (com.sun.net.httpserver). Ondersteunt
 * exact de endpoints die de echte
 * [nl.vdzon.softwarefactory.youtrack.clients.YouTrackClient] aanroept en houdt de
 * mutaties bij in [FakeYouTrackState], zodat round-trips (create -> update ->
 * read) echt werken. Bedoeld voor de end-to-end integratietest (zie SF-1).
 */
class FakeYouTrackServer(
    val state: FakeYouTrackState = FakeYouTrackState(),
) : AutoCloseable {
    private val mapper = state.mapper
    private val server: HttpServer = HttpServer.create(InetSocketAddress(0), 0)
    val requests: MutableList<FakeRequest> = mutableListOf()

    val baseUrl: String
        get() = "http://localhost:${server.address.port}"

    init {
        server.createContext("/") { exchange -> safeHandle(exchange) }
        server.start()
    }

    override fun close() {
        server.stop(0)
    }

    data class FakeRequest(val method: String, val path: String, val query: String, val body: String)

    private fun safeHandle(exchange: HttpExchange) {
        try {
            handle(exchange)
        } catch (ex: Exception) {
            exchange.json(500, mapper.createObjectNode().put("error", ex.message ?: ex.toString()).toString())
        }
    }

    private fun handle(exchange: HttpExchange) {
        val method = exchange.requestMethod
        val path = exchange.requestURI.path
        val rawQuery = exchange.requestURI.rawQuery.orEmpty()
        val body = exchange.requestBody.bufferedReader().use { it.readText() }
        synchronized(requests) { requests += FakeRequest(method, path, rawQuery, body) }
        val json: JsonNode? = body.takeIf { it.isNotBlank() }?.let { runCatching { mapper.readTree(it) }.getOrNull() }

        when {
            // ---- admin / schema ----
            method == "GET" && path == "/api/admin/projects" ->
                exchange.json(200, state.projectsNode().toString())

            method == "GET" && path == "/api/admin/customFieldSettings/customFields" ->
                exchange.json(200, state.globalFieldsNode().toString())

            method == "GET" && path == "/api/admin/projects/${state.projectId}/customFields" ->
                exchange.json(200, state.projectFieldsNode().toString())

            // Niet nodig met volledig geseede schema, maar veilig om te stubben.
            method == "POST" && path.startsWith("/api/admin/") ->
                exchange.json(200, "{\"id\":\"stub\"}")

            // ---- issues ----
            method == "GET" && path == "/api/issues" ->
                exchange.json(200, searchIssues(decodeQuery(rawQuery)).toString())

            method == "POST" && path == "/api/issues" ->
                exchange.json(200, createIssue(json))

            method == "GET" && issueKey(path) != null ->
                respondIssue(exchange, issueKey(path)!!)

            method == "POST" && issueKey(path) != null ->
                exchange.json(200, updateIssue(issueKey(path)!!, json))

            method == "DELETE" && issueKey(path) != null -> {
                synchronized(state) { /* delete is best-effort voor de test */ }
                exchange.text(204, "")
            }

            // ---- comments ----
            method == "POST" && commentsKey(path) != null -> {
                val comment = state.addComment(commentsKey(path)!!, json?.path("text")?.asText("").orEmpty(), authorLogin = "factory", authorFullName = "Factory")
                exchange.json(200, state.commentNode(comment).toString())
            }

            // ---- reactions (processed-marker) ----
            method == "GET" && reaction(path) != null ->
                exchange.json(200, reactionsArray(reaction(path)!!).toString())

            method == "POST" && reaction(path) != null -> {
                addReaction(reaction(path)!!, json?.path("reaction")?.asText("eyes") ?: "eyes")
                exchange.json(200, "{\"id\":\"reaction\",\"reaction\":\"eyes\"}")
            }

            // ---- attachments (minimaal) ----
            method == "GET" && path.endsWith("/attachments") -> exchange.json(200, "[]")
            method == "POST" && path.endsWith("/attachments") ->
                exchange.json(200, "[{\"id\":\"att-new\",\"name\":\"upload\",\"size\":0}]")

            // ---- commands (tags, parent-link, stage) ----
            method == "POST" && path == "/api/commands" -> {
                applyCommand(json)
                exchange.json(200, "{\"id\":\"command\"}")
            }

            else -> exchange.json(404, "{\"error\":\"unexpected $method $path\"}")
        }
    }

    private fun issueKey(path: String): String? =
        Regex("^/api/issues/([^/]+)$").find(path)?.groupValues?.get(1)?.let { decode(it) }

    private fun commentsKey(path: String): String? =
        Regex("^/api/issues/([^/]+)/comments$").find(path)?.groupValues?.get(1)?.let { decode(it) }

    /** Geeft (issueKey, commentId) terug voor .../comments/{id}/reactions. */
    private fun reaction(path: String): Pair<String, String>? =
        Regex("^/api/issues/([^/]+)/comments/([^/]+)/reactions$").find(path)?.let {
            decode(it.groupValues[1]) to decode(it.groupValues[2])
        }

    private fun respondIssue(exchange: HttpExchange, key: String) {
        val issue = state.issue(key)
        if (issue == null) exchange.json(404, "{\"error\":\"unknown issue $key\"}")
        else exchange.json(200, state.issueNode(issue).toString())
    }

    private fun searchIssues(query: String): ArrayNode {
        val tag = Regex("""tag:\s*\{([^}]+)\}""").find(query)?.groupValues?.get(1)
        val result = mapper.createArrayNode()
        state.allIssues()
            .filter { query.contains("project: ${state.projectKey}") || query.contains("project:${state.projectKey}") }
            .filter { tag == null || it.tags.contains(tag) }
            .forEach { result.add(state.issueNode(it)) }
        return result
    }

    private fun createIssue(json: JsonNode?): String {
        val summary = json?.path("summary")?.asText("").orEmpty()
        val description = json?.path("description")?.asText(null)?.takeIf { it.isNotBlank() }
        val issue = state.createIssue(summary, description)
        return "{\"idReadable\":\"${issue.key}\"}"
    }

    private fun updateIssue(key: String, json: JsonNode?): String {
        val issue = state.issue(key) ?: return "{\"error\":\"unknown issue $key\"}"
        synchronized(state) {
            json?.path("summary")?.takeIf { it.isTextual }?.let { issue.summary = it.asText() }
            json?.path("customFields")?.takeIf { it.isArray }?.forEach { cf ->
                val name = cf.path("name").asText()
                if (name.isNotBlank()) {
                    val value = cf.get("value")
                    val normalized = if (value == null || value.isNull) null else value
                    // Historie bijhouden: de AwaitDsl kan zo op "fase ooit bereikt" wachten,
                    // ook als auto-approve de fase binnen één poll-venster alweer doorschuift.
                    issue.recordFieldWrite(name, normalized)
                    issue.customFields[name] = normalized
                }
            }
        }
        return "{\"idReadable\":\"$key\",\"summary\":\"${issue.summary}\"}"
    }

    private fun reactionsArray(target: Pair<String, String>): ArrayNode {
        val (issueKey, commentId) = target
        val comment = state.issue(issueKey)?.comments?.firstOrNull { it.id == commentId }
        return if (comment == null) mapper.createArrayNode() else state.commentNode(comment).withArray("reactions")
    }

    private fun addReaction(target: Pair<String, String>, reaction: String) {
        val (issueKey, commentId) = target
        state.issue(issueKey)?.comments?.firstOrNull { it.id == commentId }?.reactions?.add(reaction)
    }

    private fun applyCommand(json: JsonNode?) {
        val query = json?.path("query")?.asText("").orEmpty()
        val targetKeys = json?.path("issues")?.mapNotNull { it.path("idReadable").asText("").takeIf { k -> k.isNotBlank() } }
            ?: emptyList()
        when {
            query.startsWith("add tag ") -> {
                val tag = query.removePrefix("add tag ").trim()
                targetKeys.forEach { state.issue(it)?.let { issue -> synchronized(state) { if (!issue.tags.contains(tag)) issue.tags.add(tag) } } }
            }
            query.startsWith("remove tag ") -> {
                val tag = query.removePrefix("remove tag ").trim()
                targetKeys.forEach { synchronized(state) { state.issue(it)?.tags?.remove(tag) } }
            }
            query.startsWith("parent for ") -> {
                val childKey = query.removePrefix("parent for ").trim()
                targetKeys.forEach { parentKey -> state.linkParent(parentKey, childKey) }
            }
            // Board-lane-overgangen ("State Done" etc., zie YouTrackClient.transitionIssue) worden
            // als `State`-custom-field bijgehouden, zodat de e2e-test kan asserten dat een story
            // na de laatste subtaak echt in de Done-lane belandt.
            query.startsWith("State ") -> {
                val lane = query.removePrefix("State ").trim()
                targetKeys.forEach { state.issue(it)?.let { _ -> state.setEnumField(it, "State", lane) } }
            }
            // Overige commands: best-effort no-op.
        }
    }

    private fun decodeQuery(rawQuery: String): String =
        rawQuery.split("&").firstOrNull { it.startsWith("query=") }
            ?.substringAfter("=")?.let { decode(it) }.orEmpty()

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun HttpExchange.json(status: Int, body: String) {
        responseHeaders.add("Content-Type", "application/json")
        text(status, body)
    }

    private fun HttpExchange.text(status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(status, if (status == 204) -1 else bytes.size.toLong())
        if (status == 204) close() else responseBody.use { it.write(bytes) }
    }
}
