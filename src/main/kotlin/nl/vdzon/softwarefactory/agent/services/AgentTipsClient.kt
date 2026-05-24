package nl.vdzon.softwarefactory.agent.services

import nl.vdzon.softwarefactory.agent.ai.AgentKnowledgeDraft
import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.softwarefactory.youtrack.AgentRole
import nl.vdzon.softwarefactory.knowledge.AgentKnowledgeUpdateRequest
import nl.vdzon.softwarefactory.support.services.SecretRedactor
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

class AgentTipsClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
) {
    fun fetchMarkdown(orchestratorUrl: String?, targetRepo: String, role: AgentRole): String? {
        val baseUrl = orchestratorUrl?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        val uri = "$baseUrl/agent-knowledge?target_repo=${targetRepo.urlEncoded()}&role=${role.markerKeyPart.urlEncoded()}"
        return runCatching {
            val response = httpClient.send(
                HttpRequest.newBuilder(URI.create(uri)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (response.statusCode() !in 200..299) {
                return null
            }
            val entries = objectMapper.readTree(response.body())
            buildString {
                appendLine("# Agent Tips")
                appendLine()
                if (!entries.elements().hasNext()) {
                    appendLine("Geen tips gevonden voor deze repo en rol.")
                    return@buildString
                }
                entries.forEach { entry ->
                    appendLine("## ${entry.path("category").asText("general")} / ${entry.path("key").asText("")}")
                    appendLine()
                    appendLine(entry.path("content").asText("").trimEnd())
                    appendLine()
                }
            }
        }.getOrElse { exception ->
            System.err.println(SecretRedactor.redact("Tips ophalen faalde: ${exception.message}"))
            null
        }
    }

    fun postUpdates(
        orchestratorUrl: String?,
        targetRepo: String?,
        ticketKey: String,
        role: AgentRole,
        updates: List<AgentKnowledgeDraft>,
    ) {
        if (updates.isEmpty()) {
            return
        }
        val baseUrl = orchestratorUrl?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return
        val repo = targetRepo?.takeIf { it.isNotBlank() } ?: return
        updates.forEach { update ->
            val request = AgentKnowledgeUpdateRequest(
                targetRepo = repo,
                role = role.markerKeyPart,
                category = update.category,
                key = update.key,
                content = update.content,
                updatedByStory = ticketKey,
            )
            runCatching {
                httpClient.send(
                    HttpRequest.newBuilder(URI.create("$baseUrl/agent-knowledge/update"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                        .build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
            }.onFailure { exception ->
                System.err.println(SecretRedactor.redact("Tips opslaan faalde: ${exception.message}"))
            }
        }
    }

    private fun String.urlEncoded(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8)
}
