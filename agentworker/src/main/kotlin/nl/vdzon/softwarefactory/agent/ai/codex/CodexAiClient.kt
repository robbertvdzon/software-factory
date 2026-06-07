package nl.vdzon.softwarefactory.agent.ai.codex

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
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * AiClient-implementatie die de OpenAI Codex CLI (`codex exec`) aanroept.
 *
 * Auth: gebruikt bewust de ChatGPT-abonnement-login uit ~/.codex/auth.json
 * (via `codex login` op de host, gemount in de container). We zetten
 * expliciet GEEN OPENAI_API_KEY/CODEX_API_KEY in de subprocess-env, zodat
 * Codex nooit per-token API-billing pakt.
 *
 * De prompt-contracten (rol-regels + verplichte phase-JSON) en de
 * outcome-parsing zijn supplier-agnostisch en worden hergebruikt uit de
 * Claude-implementatie (ClaudePromptBuilder / ClaudeOutcomeParser).
 */
interface CodexCommandRunner {
    fun run(
        command: List<String>,
        cwd: Path,
        env: Map<String, String>,
        onLine: (String) -> Unit,
    ): Int
}

class LocalCodexCommandRunner : CodexCommandRunner {
    override fun run(
        command: List<String>,
        cwd: Path,
        env: Map<String, String>,
        onLine: (String) -> Unit,
    ): Int {
        val process = ProcessBuilder(command)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .also { builder ->
                builder.environment().putAll(env)
                // Forceer de abonnement-login: nooit een API-key laten meeliften.
                builder.environment().remove("OPENAI_API_KEY")
                builder.environment().remove("CODEX_API_KEY")
            }
            .start()

        // We sturen niets via stdin. De CLI (m.n. codex exec) leest anders
        // "additional input from stdin" en blokkeert eindeloos. Sluit stdin meteen
        // zodat het proces direct EOF krijgt.
        process.outputStream.close()

        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach(onLine)
        }
        return process.waitFor()
    }
}

class CodexAiClient(
    private val env: Map<String, String>,
    private val runner: CodexCommandRunner = LocalCodexCommandRunner(),
    private val credentialHomes: List<Path>? = null,
) : AiClient {
    override val supplier: String = "codex"

    override fun run(context: AgentContext): AgentOutcome {
        val repoRoot = context.repoRoot ?: Path.of(env["SF_REPO_ROOT"] ?: ".").toAbsolutePath().normalize()
        val credentialsError = validateCredentials(env)
        if (credentialsError != null) {
            return AgentOutcome(
                phase = null,
                comment = credentialsError,
                outcome = "error-codex-credentials",
                exitCode = 1,
            )
        }

        val taskFile = repoRoot.resolve(".task.md")
        writeTaskFile(repoRoot, taskFile, context.taskMarkdown)
        val lastMessageFile = repoRoot.resolve(".codex-last-message.txt")
        lastMessageFile.deleteIfExists()

        val lines = mutableListOf<String>()
        val startedAt = System.currentTimeMillis()
        return try {
            val exitCode = runner.run(
                command = command(context, lastMessageFile),
                cwd = repoRoot,
                env = codexProcessEnvironment(env),
            ) { line ->
                val redacted = SupportApi.default().redact(line)
                lines += redacted
                println(redacted)
            }
            val durationMs = (System.currentTimeMillis() - startedAt).toInt()
            val report = CodexStreamParser.parse(lines)
            val summaryText = readLastMessage(lastMessageFile).ifBlank { report.summaryText }
            val usage = report.usage.copy(durationMs = durationMs)

            if (exitCode != 0) {
                return AgentOutcome(
                    phase = null,
                    comment = "Codex CLI faalde met exit-code $exitCode. ${summaryText.ifBlank { "Bekijk de agent-events voor details." }}",
                    outcome = "error-codex-cli",
                    exitCode = 1,
                    usage = usage,
                    events = report.events,
                )
            }
            if (summaryText.isBlank()) {
                return AgentOutcome(
                    phase = null,
                    comment = "Codex CLI gaf geen finale message terug.",
                    outcome = "error-codex-no-result",
                    exitCode = 1,
                    usage = usage,
                    events = report.events,
                )
            }
            outcomeForRole(context.role, summaryText, usage, report.events)
        } finally {
            taskFile.deleteIfExists()
            lastMessageFile.deleteIfExists()
        }
    }

    fun command(context: AgentContext, lastMessageFile: Path): List<String> =
        buildList {
            add("codex")
            add("exec")
            add("--json")
            add("--sandbox")
            add("danger-full-access")
            add("--skip-git-repo-check")
            context.model?.takeIf { it.isNotBlank() }?.let {
                add("--model")
                add(it)
            }
            add("--output-last-message")
            add(lastMessageFile.toAbsolutePath().toString())
            add(prompt(context))
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
                comment = "Codex output kon niet naar een geldig ${role.markerKeyPart}-besluit worden geparsed. Output:\n\n${summaryText.take(2000)}",
                outcome = "error-codex-outcome-parse",
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
            subtasks = decision.subtasks,
        )
    }

    private fun writeTaskFile(repoRoot: Path, taskFile: Path, taskMarkdown: String) {
        taskFile.writeText(taskMarkdown.trimEnd() + "\n")
        val exclude = repoRoot.resolve(".git").resolve("info").resolve("exclude")
        runCatching {
            if (Files.exists(exclude)) {
                val current = Files.readString(exclude)
                val needed = listOf(".task.md", ".codex-last-message.txt")
                val missing = needed.filterNot { current.lines().contains(it) }
                if (missing.isNotEmpty()) {
                    Files.writeString(exclude, current.trimEnd() + "\n" + missing.joinToString("\n") + "\n")
                }
            }
        }
    }

    private fun readLastMessage(lastMessageFile: Path): String =
        runCatching { lastMessageFile.readText().trim() }.getOrDefault("")

    private fun validateCredentials(env: Map<String, String>): String? {
        val candidateHomes = credentialHomes
            ?: listOfNotNull(env["HOME"], "/home/runner", System.getProperty("user.home"))
                .distinct()
                .map { Path.of(it) }
        if (candidateHomes.any { hasCodexCredentials(it.resolve(".codex")) }) {
            return null
        }
        return "Codex supplier gekozen, maar geen Codex-login gevonden. Draai `codex login` op je host en mount SF_AI_CREDENTIALS_DIR naar /home/runner/.codex."
    }

    private fun hasCodexCredentials(path: Path): Boolean {
        if (!Files.exists(path)) {
            return false
        }
        if (!Files.isDirectory(path)) {
            return true
        }
        if (Files.exists(path.resolve("auth.json"))) {
            return true
        }
        return Files.list(path).use { children -> children.findAny().isPresent }
    }

    private fun codexProcessEnvironment(env: Map<String, String>): Map<String, String> =
        buildMap {
            put("HOME", env["HOME"] ?: "/home/runner")
            put("NPM_CONFIG_UPDATE_NOTIFIER", "false")
        }
}

data class CodexRunReport(
    val summaryText: String,
    val usage: AgentUsage,
    val events: List<AgentEvent>,
)

/**
 * Parser voor de JSONL-stream van `codex exec --json`. We halen hieruit de
 * token-usage (uit `turn.completed`) en bewaren elk event voor observability.
 * De finale agent-message lezen we primair uit het --output-last-message
 * bestand; de message uit deze stream is alleen een fallback.
 */
object CodexStreamParser {
    private val objectMapper = jacksonObjectMapper()

    fun parse(lines: List<String>): CodexRunReport {
        val events = mutableListOf<AgentEvent>()
        var summaryText = ""
        var inputTokens = 0
        var outputTokens = 0
        var cacheReadInputTokens = 0
        var turns = 0

        lines.filter { it.isNotBlank() }.forEach { line ->
            val node = runCatching { objectMapper.readTree(line) }.getOrNull()
            if (node == null) {
                events += AgentEvent("codex-raw", objectMapper.writeValueAsString(mapOf("text" to line.take(4000))))
                return@forEach
            }

            val type = node.path("type").asText("unknown")
            events += AgentEvent("codex-$type", objectMapper.writeValueAsString(node))

            when (type) {
                "turn.completed" -> {
                    turns += 1
                    val usageNode = node.path("usage")
                    if (!usageNode.isMissingNode) {
                        inputTokens = usageNode.path("input_tokens").asInt(inputTokens)
                        outputTokens = usageNode.path("output_tokens").asInt(outputTokens)
                        cacheReadInputTokens = usageNode.path("cached_input_tokens").asInt(cacheReadInputTokens)
                    }
                }
                "item.completed" -> {
                    val item = node.path("item")
                    val itemType = item.path("type").asText("")
                    if (itemType == "agent_message" || itemType == "assistant_message") {
                        val text = item.path("text").asText("")
                            .ifBlank { item.path("content").asText("") }
                        if (text.isNotBlank()) {
                            summaryText = text.trim()
                        }
                    }
                }
            }
        }

        val usage = AgentUsage(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheReadInputTokens = cacheReadInputTokens,
            cacheCreationInputTokens = 0,
            numTurns = turns,
            durationMs = 0,
            costUsdEst = 0.0,
        )
        return CodexRunReport(summaryText, usage, events)
    }
}
