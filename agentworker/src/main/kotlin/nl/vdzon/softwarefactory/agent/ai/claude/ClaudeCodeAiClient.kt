package nl.vdzon.softwarefactory.agent.ai.claude

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.agent.AgentContext
import nl.vdzon.softwarefactory.agent.AgentEvent
import nl.vdzon.softwarefactory.agent.AgentKnowledgeDraft
import nl.vdzon.softwarefactory.agent.AgentOutcome
import nl.vdzon.softwarefactory.agent.AgentUsage
import nl.vdzon.softwarefactory.agent.AiClient
import nl.vdzon.softwarefactory.support.SupportApi
import nl.vdzon.softwarefactory.youtrack.AgentRole
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

interface ClaudeCommandRunner {
    fun run(
        command: List<String>,
        cwd: Path,
        env: Map<String, String>,
        onLine: (String) -> Unit,
    ): Int
}

class LocalClaudeCommandRunner : ClaudeCommandRunner {
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

class ClaudeCodeAiClient(
    private val env: Map<String, String>,
    private val runner: ClaudeCommandRunner = LocalClaudeCommandRunner(),
    private val credentialHomes: List<Path>? = null,
) : AiClient {
    override val supplier: String = "claude"

    override fun run(context: AgentContext): AgentOutcome {
        val repoRoot = context.repoRoot ?: Path.of(env["SF_REPO_ROOT"] ?: ".").toAbsolutePath().normalize()
        val credentialsError = validateCredentials(env)
        if (credentialsError != null) {
            return AgentOutcome(
                phase = null,
                comment = credentialsError,
                outcome = "error-claude-credentials",
                exitCode = 1,
            )
        }

        val taskFile = repoRoot.resolve(".task.md")
        writeTaskFile(repoRoot, taskFile, context.taskMarkdown)

        val lines = mutableListOf<String>()
        return try {
            val exitCode = runner.run(
                command = command(context),
                cwd = repoRoot,
                env = claudeProcessEnvironment(env),
            ) { line ->
                val redacted = SupportApi.default().redact(line)
                lines += redacted
                println(redacted)
            }
            val report = ClaudeStreamParser.parse(lines)
            if (exitCode != 0) {
                return AgentOutcome(
                    phase = null,
                    comment = "Claude Code faalde met exit-code $exitCode. ${report.summaryText.ifBlank { "Bekijk de agent-events voor details." }}",
                    outcome = "error-claude-cli",
                    exitCode = 1,
                    usage = report.usage,
                    events = report.events,
                )
            }
            if (report.summaryText.isBlank()) {
                return AgentOutcome(
                    phase = null,
                    comment = "Claude Code gaf geen finale result-output terug.",
                    outcome = "error-claude-no-result",
                    exitCode = 1,
                    usage = report.usage,
                    events = report.events,
                )
            }
            outcomeForRole(context.role, report)
        } finally {
            taskFile.deleteIfExists()
        }
    }

    fun command(context: AgentContext): List<String> =
        buildList {
            add("claude")
            context.model?.takeIf { it.isNotBlank() }?.let {
                add("--model")
                add(it)
            }
            context.effort?.takeIf { it.isNotBlank() }?.let {
                add("--effort")
                add(it)
            }
            add("--append-system-prompt")
            add(ClaudePromptBuilder.systemPrompt(context.role, context.effort))
            add("--permission-mode")
            add("bypassPermissions")
            add("--verbose")
            add("--output-format")
            add("stream-json")
            add("--print")
            add(ClaudePromptBuilder.userPrompt(context.role))
        }

    private fun outcomeForRole(role: AgentRole, report: ClaudeRunReport): AgentOutcome {
        val knowledgeUpdates = ClaudeOutcomeParser.extractKnowledgeUpdates(report.summaryText)
        if (role == AgentRole.DEVELOPER) {
            return AgentOutcome(
                phase = "developed",
                comment = report.summaryText,
                outcome = "developed",
                usage = report.usage,
                knowledgeUpdates = knowledgeUpdates,
                events = report.events,
            )
        }

        val decision = ClaudeOutcomeParser.parse(role, report.summaryText)
            ?: return AgentOutcome(
                phase = null,
                comment = "Claude Code output kon niet naar een geldig ${role.markerKeyPart}-besluit worden geparsed. Output:\n\n${report.summaryText.take(2000)}",
                outcome = "error-claude-outcome-parse",
                exitCode = 1,
                usage = report.usage,
                knowledgeUpdates = knowledgeUpdates,
                events = report.events,
            )

        return AgentOutcome(
            phase = decision.phase,
            comment = report.summaryText,
            outcome = decision.phase,
            usage = report.usage,
            knowledgeUpdates = knowledgeUpdates,
            events = report.events,
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
        if (!env["SF_AI_OAUTH_TOKEN"].isNullOrBlank()) {
            return null
        }
        val candidateHomes = credentialHomes
            ?: listOfNotNull(env["HOME"], "/home/runner", System.getProperty("user.home"))
                .distinct()
                .map { Path.of(it) }
        if (candidateHomes.any { hasClaudeCredentials(it.resolve(".claude")) }) {
            return null
        }
        return "Claude supplier gekozen, maar geen Claude Code credentials gevonden. Mount SF_AI_CREDENTIALS_DIR naar /home/runner/.claude of zet SF_AI_OAUTH_TOKEN."
    }

    private fun hasClaudeCredentials(path: Path): Boolean {
        if (!Files.exists(path)) {
            return false
        }
        if (!Files.isDirectory(path)) {
            return true
        }
        return Files.list(path).use { children -> children.findAny().isPresent }
    }

    private fun claudeProcessEnvironment(env: Map<String, String>): Map<String, String> =
        buildMap {
            env["SF_AI_OAUTH_TOKEN"]?.takeIf { it.isNotBlank() }?.let {
                put("CLAUDE_CODE_OAUTH_TOKEN", it)
            }
            put("HOME", env["HOME"] ?: "/home/runner")
            put("NPM_CONFIG_UPDATE_NOTIFIER", "false")
        }
}

object ClaudePromptBuilder {
    fun systemPrompt(role: AgentRole, effort: String?): String =
        buildString {
            appendLine("Je bent een ${role.markerKeyPart}-agent binnen Software Factory.")
            appendLine("Werk zelfstandig, pragmatisch en volgens de factory-regels in .task.md.")
            appendLine("Lees eerst .task.md, docs/factory en eventuele .agent-tips.md.")
            appendLine("Gebruik alleen de checkout in de huidige working directory.")
            appendLine("Gebruik geen secrets in output. Meld problemen concreet.")
            effort?.takeIf { it.isNotBlank() }?.let {
                appendLine("Gevraagde effort: $it. Pas je diepgang daarop aan.")
            }
            appendLine()
            appendLine(rolePrompt(role))
            appendLine()
            appendLine(tipsPrompt())
        }.trim()

    fun userPrompt(role: AgentRole): String =
        when (role) {
            AgentRole.REFINER -> "Lees .task.md en bepaal of de story implementeerbaar is. Volg exact het JSON-outputcontract uit de system prompt."
            AgentRole.DEVELOPER -> "Implementeer de story uit .task.md. Maak lokale wijzigingen op deze branch; commit, push en PR-acties worden na jouw run door de factory gedaan."
            AgentRole.REVIEWER -> "Review de branch aan de hand van .task.md en de repo. Volg exact het JSON-outputcontract uit de system prompt."
            AgentRole.TESTER -> "Test de branch aan de hand van .task.md en beschikbare preview-context. Volg exact het JSON-outputcontract uit de system prompt."
            AgentRole.COST_MONITOR,
            AgentRole.ORCHESTRATOR,
            -> error("Role $role is not supported by Claude Code.")
        }

    private fun rolePrompt(role: AgentRole): String =
        when (role) {
            AgentRole.REFINER -> """
                Refiner-regels:
                - Schrijf geen code en wijzig geen bestanden.
                - Stel alleen blokkerende vragen; beantwoord alles wat je zelf in repo/docs kunt vinden.
                - Bij voldoende duidelijkheid: beschrijf aannames op gedragsniveau.
                - Laatste regel is exact een JSON-object:
                  {"phase":"refined-finished"}
                  of
                  {"phase":"refined-with-questions-for-user","questions":["vraag 1"]}
            """.trimIndent()
            AgentRole.DEVELOPER -> """
                Developer-regels:
                - Implementeer de story op de huidige branch.
                - Houd docs/stories/<issue-key>-<korte-omschrijving>.md bij als die bestaat of nodig is.
                - Draai passende tests waar mogelijk.
                - Voer nooit git commit, git push, gh pr create/update/merge of andere PR-acties uit.
                - Laat alle wijzigingen uncommitted in de working tree; de factory commit, pusht en opent/bijwerkt de PR na jouw run.
                - Eindig met een handover met exact deze koppen: Samenvatting, Gedaan, Niet gedaan / aangepast.
            """.trimIndent()
            AgentRole.REVIEWER -> """
                Reviewer-regels:
                - Schrijf geen code en wijzig geen bestanden.
                - Beoordeel bugs, regressies, scope en testdekking.
                - Gebruik bevinding-prefixes [blocker], [bug], [suggestie], [info].
                - Laatste regel is exact een JSON-object:
                  {"phase":"review-finished"}
                  of
                  {"phase":"reviewed-with-feedback-for-developer"}
            """.trimIndent()
            AgentRole.TESTER -> """
                Tester-regels:
                - Test gedrag, niet alleen code.
                - Gebruik browser/preview-context wanneer beschikbaar.
                - Maak bij browser/preview-tests screenshots en laat ze in /work/screenshots staan.
                - Wijzig geen code, infra of data behalve tijdelijke testdata met cleanup.
                - Laatste regel is exact een JSON-object:
                  {"phase":"tested-successfully"}
                  of
                  {"phase":"tested-with-feedback-for-developer"}
            """.trimIndent()
            AgentRole.COST_MONITOR,
            AgentRole.ORCHESTRATOR,
            -> error("Role $role is not supported by Claude Code.")
        }

    private fun tipsPrompt(): String =
        """
        Agent tips:
        Als je nieuwe herbruikbare kennis leert, voeg aan het einde een los JSON-object toe met:
        {"agent_tips_update":[{"category":"...","key":"...","content":"..."}]}
        Gebruik {"agent_tips_update":[]} als je niets nieuws hebt geleerd.
        Dit object mag voor het verplichte phase-JSON staan, niet erna.
        """.trimIndent()
}

data class ClaudeDecision(
    val phase: String,
)

data class ClaudeRunReport(
    val summaryText: String,
    val usage: AgentUsage,
    val outcome: String,
    val events: List<AgentEvent>,
)

object ClaudeStreamParser {
    private val objectMapper = jacksonObjectMapper()

    fun parse(lines: List<String>): ClaudeRunReport {
        val events = mutableListOf<AgentEvent>()
        var summaryText = ""
        var outcome = "success"
        var usage = AgentUsage(0, 0, 0, 0, 0, 0, 0.0)

        lines.filter { it.isNotBlank() }.forEach { line ->
            val node = runCatching { objectMapper.readTree(line) }.getOrNull()
            if (node == null) {
                events += AgentEvent("claude-raw", objectMapper.writeValueAsString(mapOf("text" to line.take(4000))))
                return@forEach
            }

            val type = node.path("type").asText("unknown")
            events += AgentEvent("claude-$type", objectMapper.writeValueAsString(node))

            if (type == "result") {
                summaryText = node.path("result").asText("").trim()
                outcome = node.path("subtype").asText(outcome).ifBlank { outcome }
                usage = AgentUsage(
                    inputTokens = node.path("usage").path("input_tokens").asInt(0),
                    outputTokens = node.path("usage").path("output_tokens").asInt(0),
                    cacheReadInputTokens = node.path("usage").path("cache_read_input_tokens").asInt(0),
                    cacheCreationInputTokens = node.path("usage").path("cache_creation_input_tokens").asInt(0),
                    numTurns = node.path("num_turns").asInt(0),
                    durationMs = node.path("duration_ms").asInt(0),
                    costUsdEst = node.path("total_cost_usd").asDouble(0.0),
                )
            }
        }

        return ClaudeRunReport(summaryText, usage, outcome, events)
    }
}

object ClaudeOutcomeParser {
    private val objectMapper = jacksonObjectMapper()
    private val smartChars = mapOf(
        '“' to '"',
        '”' to '"',
        '‘' to '\'',
        '’' to '\'',
        '«' to '"',
        '»' to '"',
        '–' to '-',
        '—' to '-',
    )
    private val phasePattern = Regex("""["']?phase["']?\s*[:=]\s*["']?([a-z][a-z-]*[a-z])["']?""")

    fun parse(role: AgentRole, text: String): ClaudeDecision? {
        val normalized = normalize(text)
        val candidates = jsonObjects(normalized)
            .mapNotNull { parseJson(it) }
            .mapNotNull { it.path("phase").asText(null) }
            .mapNotNull { mapPhase(role, it) }
        if (candidates.isNotEmpty()) {
            return ClaudeDecision(candidates.last())
        }

        val regexCandidate = phasePattern.findAll(normalized)
            .mapNotNull { mapPhase(role, it.groupValues[1]) }
            .lastOrNull()
        return regexCandidate?.let { ClaudeDecision(it) }
    }

    fun extractKnowledgeUpdates(text: String): List<AgentKnowledgeDraft> {
        val normalized = normalize(text)
        return jsonObjects(normalized)
            .asReversed()
            .firstNotNullOfOrNull { candidate ->
                val root = parseJson(candidate) ?: return@firstNotNullOfOrNull null
                val updates = root.path("agent_tips_update").takeIf { it.isArray } ?: return@firstNotNullOfOrNull null
                updates.mapNotNull { update ->
                    val category = update.path("category").asText("").trim()
                    val key = update.path("key").asText("").trim()
                    val content = update.path("content").asText("").trim()
                    if (category.isBlank() || key.isBlank() || content.isBlank()) {
                        null
                    } else {
                        AgentKnowledgeDraft(category, key, content)
                    }
                }
            }.orEmpty()
    }

    private fun mapPhase(role: AgentRole, rawPhase: String): String? {
        val phase = rawPhase.trim().lowercase()
        return when (role) {
            AgentRole.REFINER -> when (phase) {
                "refined",
                "refined-finished",
                -> "refined-finished"
                "awaiting-po",
                "refined-with-questions-for-user",
                -> "refined-with-questions-for-user"
                else -> null
            }
            AgentRole.REVIEWER -> when (phase) {
                "reviewed-ok",
                "review-finished",
                -> "review-finished"
                "reviewed-changes",
                "reviewed-with-feedback-for-developer",
                -> "reviewed-with-feedback-for-developer"
                else -> null
            }
            AgentRole.TESTER -> when (phase) {
                "tested-ok",
                "tested-successfully",
                -> "tested-successfully"
                "tested-fail",
                "tested-with-feedback-for-developer",
                -> "tested-with-feedback-for-developer"
                else -> null
            }
            AgentRole.DEVELOPER -> "developed".takeIf { phase == "developed" }
            AgentRole.COST_MONITOR,
            AgentRole.ORCHESTRATOR,
            -> null
        }
    }

    private fun normalize(text: String): String {
        val translated = buildString(text.length) {
            text.forEach { append(smartChars[it] ?: it) }
        }
        return translated
            .replace(Regex("""```(?:json|JSON)?\s*"""), "")
            .replace("```", "")
    }

    private fun jsonObjects(text: String): List<String> {
        val result = mutableListOf<String>()
        var start: Int? = null
        var depth = 0
        text.forEachIndexed { index, char ->
            when (char) {
                '{' -> {
                    if (depth == 0) start = index
                    depth += 1
                }
                '}' -> {
                    if (depth > 0) {
                        depth -= 1
                        if (depth == 0) {
                            start?.let { result += repairJson(text.substring(it, index + 1)) }
                            start = null
                        }
                    }
                }
            }
        }
        return result
    }

    private fun parseJson(candidate: String): JsonNode? =
        runCatching { objectMapper.readTree(candidate) }
            .recoverCatching { objectMapper.readTree(stripJsonComments(candidate)) }
            .getOrNull()

    private fun repairJson(value: String): String =
        value.replace(Regex(""",(\s*[}\]])"""), "$1")

    private fun stripJsonComments(value: String): String =
        value
            .replace(Regex("""//[^\n]*"""), "")
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .let(::repairJson)
}
