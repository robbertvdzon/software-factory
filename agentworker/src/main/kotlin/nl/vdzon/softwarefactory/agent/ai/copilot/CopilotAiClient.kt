package nl.vdzon.softwarefactory.agent.ai.copilot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.agent.AgentContext
import nl.vdzon.softwarefactory.agent.AgentEvent
import nl.vdzon.softwarefactory.agent.AgentOutcome
import nl.vdzon.softwarefactory.agent.AgentUsage
import nl.vdzon.softwarefactory.agent.AiClient
import nl.vdzon.softwarefactory.agent.ai.claude.ClaudeOutcomeParser
import nl.vdzon.softwarefactory.agent.ai.claude.ClaudePromptBuilder
import nl.vdzon.softwarefactory.support.SupportApi
import nl.vdzon.softwarefactory.youtrack.AgentRole
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

/**
 * AiClient-implementatie die de GitHub Copilot CLI (`copilot -p`) aanroept.
 *
 * Auth: headless via een token-env-var. De Copilot CLI checkt in volgorde
 * COPILOT_GITHUB_TOKEN, GH_TOKEN, GITHUB_TOKEN. We zetten COPILOT_GITHUB_TOKEN
 * uit SF_COPILOT_TOKEN (of als fallback SF_GITHUB_TOKEN). De requests tellen
 * tegen het Copilot-abonnement van dat token (premium requests), geen losse
 * API-billing. Let op: classic ghp_-PATs worden door Copilot niet ondersteund;
 * gebruik een fine-grained PAT met "Copilot Requests" permission of een gh/
 * Copilot OAuth-token.
 *
 * De prompt-contracten (rol-regels + verplichte phase-JSON) en de
 * outcome-parsing worden hergebruikt uit de Claude-implementatie
 * (ClaudePromptBuilder / ClaudeOutcomeParser) — die zijn supplier-agnostisch.
 */
interface CopilotCommandRunner {
    fun run(
        command: List<String>,
        cwd: Path,
        env: Map<String, String>,
        onLine: (String) -> Unit,
    ): Int
}

class LocalCopilotCommandRunner : CopilotCommandRunner {
    override fun run(
        command: List<String>,
        cwd: Path,
        env: Map<String, String>,
        onLine: (String) -> Unit,
    ): Int {
        val process = ProcessBuilder(command)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .also { builder -> builder.environment().putAll(env) }
            .start()

        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach(onLine)
        }
        return process.waitFor()
    }
}

class CopilotAiClient(
    private val env: Map<String, String>,
    private val runner: CopilotCommandRunner = LocalCopilotCommandRunner(),
    private val credentialHomes: List<Path>? = null,
) : AiClient {
    override val supplier: String = "copilot"

    override fun run(context: AgentContext): AgentOutcome {
        val repoRoot = context.repoRoot ?: Path.of(env["SF_REPO_ROOT"] ?: ".").toAbsolutePath().normalize()
        val credentialsError = validateCredentials(env)
        if (credentialsError != null) {
            return AgentOutcome(
                phase = null,
                comment = credentialsError,
                outcome = "error-copilot-credentials",
                exitCode = 1,
            )
        }

        val taskFile = repoRoot.resolve(".task.md")
        writeTaskFile(repoRoot, taskFile, context.taskMarkdown)

        val lines = mutableListOf<String>()
        val startedAt = System.currentTimeMillis()
        return try {
            val exitCode = runner.run(
                command = command(context),
                cwd = repoRoot,
                env = copilotProcessEnvironment(env),
            ) { line ->
                val redacted = SupportApi.default().redact(line)
                lines += redacted
                println(redacted)
            }
            val durationMs = (System.currentTimeMillis() - startedAt).toInt()
            val report = CopilotStreamParser.parse(lines)
            val usage = if (report.usage.durationMs > 0) report.usage else report.usage.copy(durationMs = durationMs)

            if (exitCode != 0) {
                return AgentOutcome(
                    phase = null,
                    comment = "Copilot CLI faalde met exit-code $exitCode. ${report.summaryText.ifBlank { "Bekijk de agent-events voor details." }}",
                    outcome = "error-copilot-cli",
                    exitCode = 1,
                    usage = usage,
                    events = report.events,
                )
            }
            if (report.summaryText.isBlank()) {
                return AgentOutcome(
                    phase = null,
                    comment = "Copilot CLI gaf geen finale message terug.",
                    outcome = "error-copilot-no-result",
                    exitCode = 1,
                    usage = usage,
                    events = report.events,
                )
            }
            outcomeForRole(context.role, report.summaryText, usage, report.events)
        } finally {
            taskFile.deleteIfExists()
        }
    }

    fun command(context: AgentContext): List<String> =
        buildList {
            add("copilot")
            add("--prompt")
            add(prompt(context))
            add("--allow-all-tools")
            add("--allow-all-paths")
            add("--no-ask-user")
            add("--no-remote")
            add("--no-auto-update")
            add("--output-format")
            add("json")
            add("--log-level")
            add("none")
            context.model?.takeIf { it.isNotBlank() }?.let {
                add("--model")
                add(it)
            }
        }

    private fun prompt(context: AgentContext): String =
        buildString {
            appendLine(ClaudePromptBuilder.systemPrompt(context.role, context.effort))
            appendLine()
            appendLine(ClaudePromptBuilder.userPrompt(context.role))
        }.trim()

    private fun outcomeForRole(
        role: AgentRole,
        summaryText: String,
        usage: AgentUsage,
        events: List<AgentEvent>,
    ): AgentOutcome {
        val knowledgeUpdates = ClaudeOutcomeParser.extractKnowledgeUpdates(summaryText)
        if (role == AgentRole.DEVELOPER) {
            return AgentOutcome(
                phase = "developed",
                comment = summaryText,
                outcome = "developed",
                usage = usage,
                knowledgeUpdates = knowledgeUpdates,
                events = events,
            )
        }

        val decision = ClaudeOutcomeParser.parse(role, summaryText)
            ?: return AgentOutcome(
                phase = null,
                comment = "Copilot output kon niet naar een geldig ${role.markerKeyPart}-besluit worden geparsed. Output:\n\n${summaryText.take(2000)}",
                outcome = "error-copilot-outcome-parse",
                exitCode = 1,
                usage = usage,
                knowledgeUpdates = knowledgeUpdates,
                events = events,
            )

        return AgentOutcome(
            phase = decision.phase,
            comment = summaryText,
            outcome = decision.phase,
            usage = usage,
            knowledgeUpdates = knowledgeUpdates,
            events = events,
        )
    }

    private fun writeTaskFile(repoRoot: Path, taskFile: Path, taskMarkdown: String) {
        taskFile.writeText(taskMarkdown.trimEnd() + "\n")
        val exclude = repoRoot.resolve(".git").resolve("info").resolve("exclude")
        runCatching {
            if (Files.exists(exclude)) {
                val current = Files.readString(exclude)
                if (!current.lines().contains(".task.md")) {
                    Files.writeString(exclude, current.trimEnd() + "\n.task.md\n")
                }
            }
        }
    }

    private fun validateCredentials(env: Map<String, String>): String? {
        if (resolveToken(env) != null) {
            return null
        }
        val candidateHomes = credentialHomes
            ?: listOfNotNull(env["HOME"], "/home/runner", System.getProperty("user.home"))
                .distinct()
                .map { Path.of(it) }
        if (candidateHomes.any { hasCopilotCredentials(it.resolve(".copilot")) }) {
            return null
        }
        return "Copilot supplier gekozen, maar geen Copilot-login gevonden. Mount SF_COPILOT_CREDENTIALS_DIR naar /home/runner/.copilot of zet SF_COPILOT_TOKEN/COPILOT_GITHUB_TOKEN."
    }

    private fun hasCopilotCredentials(path: Path): Boolean {
        if (!Files.exists(path)) {
            return false
        }
        return Files.exists(path.resolve("apps.json")) ||
            Files.exists(path.resolve("hosts.json")) ||
            Files.exists(path.resolve("config.json")) ||
            Files.exists(path.resolve("credentials.json"))
    }

    private fun resolveToken(env: Map<String, String>): String? =
        env["SF_COPILOT_TOKEN"]?.takeIf { it.isNotBlank() }
            ?: env["COPILOT_GITHUB_TOKEN"]?.takeIf { it.isNotBlank() }
            ?: env["GH_TOKEN"]?.takeIf { it.isNotBlank() }
            ?: env["GITHUB_TOKEN"]?.takeIf { it.isNotBlank() }
            ?: env["SF_GITHUB_TOKEN"]?.takeIf { it.isNotBlank() }

    private fun copilotProcessEnvironment(env: Map<String, String>): Map<String, String> =
        buildMap {
            put("HOME", env["HOME"] ?: "/home/runner")
            put("COPILOT_AUTO_UPDATE", "false")
            resolveToken(env)?.let { put("COPILOT_GITHUB_TOKEN", it) }
        }
}

data class CopilotRunReport(
    val summaryText: String,
    val usage: AgentUsage,
    val events: List<AgentEvent>,
)

/**
 * Parser voor de JSONL-stream van `copilot --output-format json`.
 * - finale message: laatste `assistant.message` met niet-lege `data.content`
 * - output-tokens: som van `data.outputTokens` over assistant.message events
 * - duur: `result.usage.sessionDurationMs`
 * Copilot rapporteert geen input-token-telling; die blijft 0. De
 * `premiumRequests` (Copilot's abonnement-eenheid) bewaren we als event.
 */
object CopilotStreamParser {
    private val objectMapper = jacksonObjectMapper()

    fun parse(lines: List<String>): CopilotRunReport {
        val events = mutableListOf<AgentEvent>()
        var summaryText = ""
        var outputTokens = 0
        var turns = 0
        var durationMs = 0

        lines.filter { it.isNotBlank() }.forEach { line ->
            val node = runCatching { objectMapper.readTree(line) }.getOrNull()
            if (node == null) {
                events += AgentEvent("copilot-raw", objectMapper.writeValueAsString(mapOf("text" to line.take(4000))))
                return@forEach
            }

            val type = node.path("type").asText("unknown")
            events += AgentEvent("copilot-$type", objectMapper.writeValueAsString(node))

            when (type) {
                "assistant.message" -> {
                    val data = node.path("data")
                    val content = data.path("content").asText("").trim()
                    if (content.isNotEmpty()) {
                        summaryText = content
                    }
                    outputTokens += data.path("outputTokens").asInt(0)
                }
                "assistant.turn_end" -> turns += 1
                "result" -> {
                    durationMs = node.path("usage").path("sessionDurationMs").asInt(durationMs)
                }
            }
        }

        val usage = AgentUsage(
            inputTokens = 0,
            outputTokens = outputTokens,
            cacheReadInputTokens = 0,
            cacheCreationInputTokens = 0,
            numTurns = if (turns > 0) turns else 1,
            durationMs = durationMs,
            costUsdEst = 0.0,
        )
        return CopilotRunReport(summaryText, usage, events)
    }
}
