package nl.vdzon.softwarefactory.agent

import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.softwarefactory.config.SecretsEnvLoader
import nl.vdzon.softwarefactory.jira.AgentRole
import nl.vdzon.softwarefactory.jira.AtlassianJiraClient
import nl.vdzon.softwarefactory.jira.JiraFieldUpdate
import nl.vdzon.softwarefactory.jira.JiraKnownField
import nl.vdzon.softwarefactory.runtime.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.AgentRunEventPayload
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.system.exitProcess

fun main() {
    val env = System.getenv()
    val ticketKey = requireEnv(env, "SF_TICKET_KEY")
    val role = parseRole(requireEnv(env, "SF_AGENT_TYPE"))
    val taskMarkdown = Path.of("/work/task.md").takeIf { it.toFile().exists() }?.readText().orEmpty()
    val context = AgentContext(
        ticketKey = ticketKey,
        role = role,
        taskMarkdown = taskMarkdown,
        forcedOutcome = env["SF_DUMMY_FORCE_OUTCOME"]?.takeIf { it.isNotBlank() },
    )

    val outcome = DummyAiClient().run(context)
    if (env["SF_DUMMY_SKIP_SLEEP"]?.toBooleanStrictOrNull() != true) {
        Thread.sleep(outcome.usage.durationMs.toLong())
    }

    val secrets = SecretsEnvLoader().load()
    val jiraClient = AtlassianJiraClient(secrets)
    if (outcome.exitCode == 0 && outcome.phase != null) {
        jiraClient.updateIssueFields(ticketKey, JiraFieldUpdate.of(JiraKnownField.AI_PHASE to outcome.phase))
        jiraClient.postAgentComment(ticketKey, role, outcome.comment)
    } else {
        jiraClient.updateIssueFields(ticketKey, JiraFieldUpdate.of(JiraKnownField.ERROR to "${role.commentPrefix} ${outcome.comment}"))
    }

    reportCompletion(env, ticketKey, role, outcome)
    exitProcess(outcome.exitCode)
}

private fun reportCompletion(env: Map<String, String>, ticketKey: String, role: AgentRole, outcome: AgentOutcome) {
    val orchestratorUrl = env["SF_ORCHESTRATOR_URL"]?.trimEnd('/') ?: return
    val usage = outcome.usage
    val request = AgentRunCompleteRequest(
        storyKey = ticketKey,
        role = role.markerKeyPart,
        containerName = env["SF_CONTAINER_NAME"] ?: env["HOSTNAME"] ?: "unknown-container",
        outcome = outcome.outcome,
        summaryText = outcome.comment,
        inputTokens = usage.inputTokens,
        outputTokens = usage.outputTokens,
        cacheReadInputTokens = usage.cacheReadInputTokens,
        cacheCreationInputTokens = usage.cacheCreationInputTokens,
        numTurns = usage.numTurns,
        durationMs = usage.durationMs,
        costUsdEst = usage.costUsdEst,
        events = listOf(AgentRunEventPayload("dummy-outcome", outcome.comment)),
    )
    val objectMapper = ObjectMapper()
    val client = HttpClient.newHttpClient()
    val body = objectMapper.writeValueAsString(request)
    var lastFailure: String? = null

    repeat(6) { attempt ->
        runCatching {
            client.send(
                HttpRequest.newBuilder(URI.create("$orchestratorUrl/agent-run/complete"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        }.onSuccess { response ->
            if (response.statusCode() in 200..299) {
                return
            }
            lastFailure = "HTTP ${response.statusCode()}"
        }.onFailure { exception ->
            lastFailure = exception.message ?: exception::class.java.simpleName
        }

        if (attempt < 5) {
            Thread.sleep(1000L)
        }
    }

    error("Completion report failed after retries: $lastFailure")
}

private fun requireEnv(env: Map<String, String>, key: String): String =
    requireNotNull(env[key]?.takeIf { it.isNotBlank() }) { "Missing required env var $key" }

private fun parseRole(value: String): AgentRole =
    AgentRole.entries.firstOrNull { it.markerKeyPart == value || it.name.equals(value, ignoreCase = true) }
        ?: error("Unknown agent role: $value")
