package nl.vdzon.softwarefactory.agentworker.cli

import nl.vdzon.softwarefactory.agent.AgentContext
import nl.vdzon.softwarefactory.agent.AgentEvent
import nl.vdzon.softwarefactory.agent.AgentOutcome
import nl.vdzon.softwarefactory.agent.AiClientFactory
import nl.vdzon.softwarefactory.agentworker.AgentWorkerEvent
import nl.vdzon.softwarefactory.agentworker.AgentWorkerKnowledgeUpdate
import nl.vdzon.softwarefactory.agentworker.AgentWorkerResult
import nl.vdzon.softwarefactory.agentworker.AgentWorkerSubtaskSpec
import nl.vdzon.softwarefactory.agentworker.flows.DeveloperRepositoryFlow
import nl.vdzon.softwarefactory.agentworker.flows.TargetRepositoryPreparer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.docs.DocsApi
import nl.vdzon.softwarefactory.youtrack.AgentRole
import nl.vdzon.softwarefactory.agentworker.flows.TesterPreviewContext
import nl.vdzon.softwarefactory.agentworker.flows.TesterPreviewFlow
import nl.vdzon.softwarefactory.agentworker.flows.RepositoryCommitGuard
import nl.vdzon.softwarefactory.support.SupportApi
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
    val completionEvents = mutableListOf<AgentEvent>()
    val resultFile = env["SF_AGENT_RESULT_FILE"] ?: "/work/agent-result.json"
    println("Agent worker started: story=$ticketKey role=${role.markerKeyPart} resultFile=$resultFile")

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
    val tipsMarkdown = Path.of(env["SF_AGENT_TIPS_FILE"] ?: "/work/agent-tips.md")
        .takeIf { it.exists() }
        ?.readText()
        ?.takeIf { it.isNotBlank() }
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
    val taskMarkdown = runCatching {
        enrichedTaskMarkdown(
            baseTaskMarkdown = baseTaskMarkdown,
            role = role,
            repoRoot = repoRoot,
            previewContext = previewContext,
            developerLoopbackReason = env["SF_DEVELOPER_LOOPBACK_REASON"]?.takeIf { it.isNotBlank() },
            tipsMarkdown = tipsMarkdown,
        )
    }.getOrElse { exception ->
        finish(
            env = env,
            ticketKey = ticketKey,
            role = role,
            outcome = setupErrorOutcome(role, exception, stage = "prompt build"),
            completionEvents = completionEvents,
        )
    }
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

    val repositoryCommitGuard = RepositoryCommitGuard()
    val headBeforeAgent = repositorySession?.let { repositoryCommitGuard.captureHead(it.repoRoot) }
    val aiClient = AiClientFactory.create(env)
    var outcome = runCatching {
        aiClient.run(context)
    }.getOrElse { exception ->
        setupErrorOutcome(role, exception, stage = "AI run")
    }
    if (role == AgentRole.DEVELOPER && outcome.exitCode == 0 && repositorySession != null) {
        runCatching {
            if (aiClient.supplier == "mock") {
                DeveloperRepositoryFlow().completeDummyDeveloperRun(
                    session = repositorySession,
                    ticketKey = ticketKey,
                    storyText = baseTaskMarkdown,
                )
            } else {
                null
            }
        }.onSuccess { result ->
            if (result != null) {
                outcome = outcome.copy(
                    comment = "${outcome.comment}; branch ${result.branchName}",
                )
            }
        }.onFailure { exception ->
            outcome = setupErrorOutcome(role, exception)
        }
    }
    repositorySession
        ?.let { repositoryCommitGuard.detectCommit(it.repoRoot, headBeforeAgent) }
        ?.let { message ->
            outcome = setupErrorOutcome(role, IllegalStateException(message), stage = "git guard")
        }

    finish(env, ticketKey, role, outcome, completionEvents)
}

private fun finish(
    env: Map<String, String>,
    ticketKey: String,
    role: AgentRole,
    outcome: AgentOutcome,
    completionEvents: List<AgentEvent>,
): Nothing {
    val supplier = AiClientFactory.normalizedSupplier(env["SF_AI_SUPPLIER"])
    val usesMockDelay = supplier.isBlank() || supplier == "mock" || supplier == "dummy" || supplier == "none"
    if (usesMockDelay && env["SF_DUMMY_SKIP_SLEEP"]?.toBooleanStrictOrNull() != true) {
        Thread.sleep(5000)
    }

    writeResult(env, ticketKey, role, outcome, completionEvents)
    exitProcess(outcome.exitCode)
}

private fun setupErrorOutcome(role: AgentRole, exception: Throwable, stage: String = "setup"): AgentOutcome =
    "${role.markerKeyPart} $stage faalde: ${exception.message ?: exception::class.java.simpleName}"
        .let { message ->
            System.err.println(SupportApi.default().redact(message))
            AgentOutcome(
                phase = null,
                comment = SupportApi.default().redact(message),
                outcome = "error",
                exitCode = 1,
            )
        }

private fun writeResult(
    env: Map<String, String>,
    ticketKey: String,
    role: AgentRole,
    outcome: AgentOutcome,
    completionEvents: List<AgentEvent>,
) {
    val usage = outcome.usage
    val result = AgentWorkerResult(
        storyKey = ticketKey,
        role = role.markerKeyPart,
        containerName = env["SF_CONTAINER_NAME"] ?: env["HOSTNAME"] ?: "unknown-container",
        phase = outcome.phase,
        outcome = outcome.outcome,
        summaryText = outcome.comment,
        exitCode = outcome.exitCode,
        inputTokens = usage.inputTokens,
        outputTokens = usage.outputTokens,
        cacheReadInputTokens = usage.cacheReadInputTokens,
        cacheCreationInputTokens = usage.cacheCreationInputTokens,
        numTurns = usage.numTurns,
        durationMs = usage.durationMs,
        costUsdEst = usage.costUsdEst,
        events = (
            listOf(AgentEvent("${AiClientFactory.eventSupplier(env["SF_AI_SUPPLIER"])}-outcome", outcome.comment)) +
                outcome.events +
                completionEvents
            ).map { AgentWorkerEvent(it.kind, it.payload) },
        knowledgeUpdates = outcome.knowledgeUpdates.map { AgentWorkerKnowledgeUpdate(it.category, it.key, it.content) },
        subtasks = outcome.subtasks.map { AgentWorkerSubtaskSpec(it.type, it.title, it.description, it.model, it.effort) },
    )
    val resultFile = Path.of(env["SF_AGENT_RESULT_FILE"] ?: "/work/agent-result.json")
    println("Agent worker writing result file: path=$resultFile outcome=${outcome.outcome} exitCode=${outcome.exitCode}")
    resultFile.writeText(jacksonObjectMapper().writeValueAsString(result))
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
        appendLine(DocsApi.default().loadFactoryDocs(role, repoRoot).promptMarkdown())
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
