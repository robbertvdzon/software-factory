package nl.vdzon.softwarefactory.agent

import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.softwarefactory.config.SecretsEnvLoader
import nl.vdzon.softwarefactory.docs.loadFactoryDocs
import nl.vdzon.softwarefactory.youtrack.AgentRole
import nl.vdzon.softwarefactory.youtrack.TrackerFieldUpdate
import nl.vdzon.softwarefactory.youtrack.TrackerField
import nl.vdzon.softwarefactory.youtrack.YouTrackClient
import nl.vdzon.softwarefactory.agent.flows.TesterPreviewContext
import nl.vdzon.softwarefactory.agent.flows.TesterPreviewFlow
import nl.vdzon.softwarefactory.support.SecretRedactor
import nl.vdzon.softwarefactory.runtime.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.runtime.AgentRunEventPayload
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

fun main() {
    val env = System.getenv()
    val ticketKey = requireEnv(env, "SF_TICKET_KEY")
    val role = parseRole(requireEnv(env, "SF_AGENT_TYPE"))
    val baseTaskMarkdown = Path.of("/work/task.md").takeIf { it.toFile().exists() }?.readText().orEmpty()
    val completionEvents = mutableListOf<AgentRunEventPayload>()

    val repositorySession = runCatching {
        TargetRepositoryPreparer().prepare(env, ticketKey, role)
    }.getOrElse { exception ->
        finish(
            env = env,
            ticketKey = ticketKey,
            role = role,
            outcome = setupErrorOutcome(role, exception),
            completionEvents = completionEvents,
        )
    }

    val repoRoot = repositorySession?.repoRoot ?: Path.of(env["SF_REPO_ROOT"] ?: "/work/repo")
    val tipsClient = AgentTipsClient()
    val tipsMarkdown = repositorySession?.let { session ->
        tipsClient.fetchMarkdown(env["SF_ORCHESTRATOR_URL"], session.repoUrl, role)
            ?.also { repoRoot.resolve(".agent-tips.md").writeText(it) }
    }
    val previewContext = if (role == AgentRole.TESTER && repositorySession != null) {
        runCatching {
            TesterPreviewFlow().prepare(env, repositorySession)
        }.getOrElse { exception ->
            finish(
                env = env,
                ticketKey = ticketKey,
                role = role,
                outcome = setupErrorOutcome(role, exception),
                completionEvents = completionEvents,
            )
        }
    } else {
        null
    }
    val taskMarkdown = enrichedTaskMarkdown(
        baseTaskMarkdown = baseTaskMarkdown,
        role = role,
        repoRoot = repoRoot,
        previewContext = previewContext,
        developerLoopbackReason = env["SF_DEVELOPER_LOOPBACK_REASON"]?.takeIf { it.isNotBlank() },
        tipsMarkdown = tipsMarkdown,
    )
    val context = AgentContext(
        ticketKey = ticketKey,
        role = role,
        taskMarkdown = taskMarkdown,
        forcedOutcome = env["SF_DUMMY_FORCE_OUTCOME"]?.takeIf { it.isNotBlank() },
        repoRoot = repoRoot,
        supplier = env["SF_AI_SUPPLIER"]?.takeIf { it.isNotBlank() },
        model = env["SF_AI_MODEL"]?.takeIf { it.isNotBlank() },
        effort = env["SF_AI_EFFORT"]?.takeIf { it.isNotBlank() },
    )

    val aiClient = AiClientFactory.create(env)
    var outcome = aiClient.run(context)
    if (role == AgentRole.DEVELOPER && outcome.exitCode == 0 && repositorySession != null) {
        runCatching {
            if (aiClient.supplier == "mock") {
                DeveloperRepositoryFlow().completeDummyDeveloperRun(
                    session = repositorySession,
                    ticketKey = ticketKey,
                    storyText = baseTaskMarkdown,
                    githubToken = env["SF_GITHUB_TOKEN"],
                )
            } else {
                DeveloperRepositoryFlow().completeDeveloperRun(
                    session = repositorySession,
                    ticketKey = ticketKey,
                    storyText = baseTaskMarkdown,
                    githubToken = env["SF_GITHUB_TOKEN"],
                    summary = outcome.comment,
                )
            }
        }.onSuccess { result ->
            completionEvents += result.completionEvent
            outcome = outcome.copy(
                comment = "${outcome.comment}; branch ${result.branchName}, PR #${result.prNumber}",
            )
        }.onFailure { exception ->
            outcome = setupErrorOutcome(role, exception)
        }
    }

    finish(env, ticketKey, role, outcome, completionEvents)
}

private fun finish(
    env: Map<String, String>,
    ticketKey: String,
    role: AgentRole,
    outcome: AgentOutcome,
    completionEvents: List<AgentRunEventPayload>,
): Nothing {
    val supplier = AiClientFactory.normalizedSupplier(env["SF_AI_SUPPLIER"])
    val usesMockDelay = supplier.isBlank() || supplier == "mock" || supplier == "dummy" || supplier == "none"
    if (usesMockDelay && env["SF_DUMMY_SKIP_SLEEP"]?.toBooleanStrictOrNull() != true) {
        Thread.sleep(outcome.usage.durationMs.toLong())
    }

    val secrets = SecretsEnvLoader().load()
    val issueTrackerClient = YouTrackClient(secrets)
    if (outcome.exitCode == 0 && outcome.phase != null) {
        issueTrackerClient.updateIssueFields(ticketKey, TrackerFieldUpdate.of(TrackerField.AI_PHASE to outcome.phase))
        issueTrackerClient.postAgentComment(ticketKey, role, outcome.comment)
    } else {
        issueTrackerClient.updateIssueFields(ticketKey, TrackerFieldUpdate.of(TrackerField.ERROR to "${role.commentPrefix} ${outcome.comment}"))
    }

    AgentTipsClient().postUpdates(
        orchestratorUrl = env["SF_ORCHESTRATOR_URL"],
        targetRepo = env["SF_REPO_URL"],
        ticketKey = ticketKey,
        role = role,
        updates = outcome.knowledgeUpdates,
    )
    reportCompletion(env, ticketKey, role, outcome, completionEvents)
    exitProcess(outcome.exitCode)
}

private fun setupErrorOutcome(role: AgentRole, exception: Throwable): AgentOutcome =
    "${role.markerKeyPart} setup faalde: ${exception.message ?: exception::class.java.simpleName}"
        .let { message ->
            System.err.println(SecretRedactor.redact(message))
            AgentOutcome(
                phase = null,
                comment = SecretRedactor.redact(message),
                outcome = "error",
                exitCode = 1,
            )
        }

private fun reportCompletion(
    env: Map<String, String>,
    ticketKey: String,
    role: AgentRole,
    outcome: AgentOutcome,
    completionEvents: List<AgentRunEventPayload>,
) {
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
        events = listOf(AgentRunEventPayload("${AiClientFactory.eventSupplier(env["SF_AI_SUPPLIER"])}-outcome", outcome.comment)) +
            outcome.events +
            completionEvents,
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

private fun enrichedTaskMarkdown(
    baseTaskMarkdown: String,
    role: AgentRole,
    repoRoot: Path,
    previewContext: TesterPreviewContext?,
    developerLoopbackReason: String?,
    tipsMarkdown: String?,
): String {
    if (!repoRoot.exists() || !repoRoot.isDirectory()) {
        return baseTaskMarkdown
    }
    return buildString {
        appendLine(baseTaskMarkdown.trimEnd())
        appendLine()
        appendLine(loadFactoryDocs(role, repoRoot).promptMarkdown())
        if (!tipsMarkdown.isNullOrBlank()) {
            appendLine()
            appendLine("## Agent Tips")
            appendLine()
            appendLine(tipsMarkdown.trimEnd())
        }
        if (previewContext != null) {
            appendLine()
            appendLine(previewContext.toMarkdown())
        }
        if (!developerLoopbackReason.isNullOrBlank()) {
            appendLine()
            appendLine("## Developer Loopback")
            appendLine()
            appendLine(developerLoopbackReason)
        }
    }
}

private fun parseRole(value: String): AgentRole =
    AgentRole.entries.firstOrNull { it.markerKeyPart == value || it.name.equals(value, ignoreCase = true) }
        ?: error("Unknown agent role: $value")
