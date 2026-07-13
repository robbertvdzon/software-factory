package nl.vdzon.softwarefactory.agent.ai.claude

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.agent.AgentContext
import nl.vdzon.softwarefactory.agent.AgentEvent
import nl.vdzon.softwarefactory.agent.AgentKnowledgeDraft
import nl.vdzon.softwarefactory.agent.AgentOutcome
import nl.vdzon.softwarefactory.agent.AgentSubtaskSpec
import nl.vdzon.softwarefactory.agent.AgentUsage
import nl.vdzon.softwarefactory.agent.AiClient
import nl.vdzon.softwarefactory.agent.ai.shared.AgentOutcomeParser
import nl.vdzon.softwarefactory.agent.ai.shared.AgentPromptBuilder
import nl.vdzon.softwarefactory.agent.ai.shared.CliProcessRunner
import nl.vdzon.softwarefactory.agent.ai.shared.TaskFileManager
import nl.vdzon.softwarefactory.support.SupportApi
import nl.vdzon.softwarefactory.core.AgentRole
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
        return CliProcessRunner.run(command, cwd, env, onLine = onLine)
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

        val taskFile = TaskFileManager.write(repoRoot, context.taskMarkdown)

        return try {
            // Eerste poging.
            val first = execute(context, repoRoot, retry = false)
            // Alleen een beslissings-parsefout is retrybaar (CLI zelf slaagde, maar de agent vergat
            // het verplichte JSON-besluit). Een CLI-crash of lege output is dat niet.
            if (first.outcome.outcome != OUTCOME_PARSE_ERROR) {
                return first.outcome
            }
            // Tweede poging met striktere contract-herinnering.
            val second = execute(context, repoRoot, retry = true)
            if (second.outcome.outcome != OUTCOME_PARSE_ERROR) {
                return second.outcome
            }
            // Twee keer geen geldig besluit → niet hard falen, maar de run naar de mens routeren via
            // de *-with-questions-fase van deze rol (de agent-output bevat doorgaans de vragen zelf).
            questionsFallback(context.role, second.report)
        } finally {
            TaskFileManager.cleanup(taskFile)
        }
    }

    /** Eén CLI-run + interpretatie. [retry] voegt een striktere herinnering aan het output-contract toe. */
    private fun execute(context: AgentContext, repoRoot: Path, retry: Boolean): AttemptResult {
        val lines = mutableListOf<String>()
        val exitCode = runner.run(
            command = command(context, retry),
            cwd = repoRoot,
            env = claudeProcessEnvironment(env),
        ) { line ->
            val redacted = SupportApi.default().redact(line)
            lines += redacted
            println(redacted)
        }
        val report = ClaudeStreamParser.parse(lines)
        if (exitCode != 0) {
            return AttemptResult(
                AgentOutcome(
                    phase = null,
                    comment = "Claude Code faalde met exit-code $exitCode. ${report.summaryText.ifBlank { "Bekijk de agent-events voor details." }}",
                    outcome = "error-claude-cli",
                    exitCode = 1,
                    usage = report.usage,
                    events = report.events,
                ),
                report,
            )
        }
        if (report.summaryText.isBlank()) {
            return AttemptResult(
                AgentOutcome(
                    phase = null,
                    comment = "Claude Code gaf geen finale result-output terug.",
                    outcome = "error-claude-no-result",
                    exitCode = 1,
                    usage = report.usage,
                    events = report.events,
                ),
                report,
            )
        }
        return AttemptResult(outcomeForRole(context.role, report), report)
    }

    /**
     * Vangnet als de agent na de retry nog steeds geen geldig JSON-besluit gaf: route naar de
     * *-with-questions-fase zodat de PO het oppakt i.p.v. de keten hard te laten falen.
     * Bestaat er voor deze rol geen vraag-fase (bv. developer), dan blijft het de parsefout.
     */
    private fun questionsFallback(role: AgentRole, report: ClaudeRunReport?): AgentOutcome {
        val phase = questionsPhaseFor(role)
        if (phase == null || report == null) {
            return report?.let { outcomeForRole(role, it) } ?: AgentOutcome(
                phase = null,
                comment = "Claude Code output kon niet naar een geldig ${role.markerKeyPart}-besluit worden geparsed.",
                outcome = OUTCOME_PARSE_ERROR,
                exitCode = 1,
            )
        }
        return AgentOutcome(
            phase = phase,
            comment = report.summaryText,
            outcome = phase,
            usage = report.usage,
            knowledgeUpdates = AgentOutcomeParser.extractKnowledgeUpdates(report.summaryText),
            events = report.events,
        )
    }

    /** De "wacht-op-mens"-fase per beslissingsrol; null voor rollen zonder zo'n fase. */
    private fun questionsPhaseFor(role: AgentRole): String? = when (role) {
        AgentRole.REFINER -> "refined-with-questions"
        AgentRole.PLANNER -> "planned-with-questions"
        AgentRole.REVIEWER -> "reviewed-with-questions"
        AgentRole.TESTER -> "tested-with-questions"
        AgentRole.SUMMARIZER -> "summary-with-questions"
        AgentRole.DOCUMENTER -> "documentation-with-questions"
        AgentRole.DEVELOPER,
        AgentRole.ASSISTANT, // assistent draait server-side, nooit via de agentworker-CLI
        AgentRole.COST_MONITOR,
        AgentRole.ORCHESTRATOR,
        -> null
    }

    fun command(context: AgentContext, retry: Boolean = false): List<String> =
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
            add(AgentPromptBuilder.systemPrompt(context.role, context.effort))
            add("--permission-mode")
            add("bypassPermissions")
            add("--verbose")
            add("--output-format")
            add("stream-json")
            add("--print")
            val userPrompt = AgentPromptBuilder.userPrompt(context.role)
            add(if (retry) userPrompt + "\n\n" + AgentPromptBuilder.retryContractReminder(context.role) else userPrompt)
        }

    private fun outcomeForRole(role: AgentRole, report: ClaudeRunReport): AgentOutcome {
        val knowledgeUpdates = AgentOutcomeParser.extractKnowledgeUpdates(report.summaryText)
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

        val decision = AgentOutcomeParser.parse(role, report.summaryText)
            ?: return AgentOutcome(
                phase = null,
                comment = "Claude Code output kon niet naar een geldig ${role.markerKeyPart}-besluit worden geparsed. Output:\n\n${report.summaryText.take(2000)}",
                outcome = OUTCOME_PARSE_ERROR,
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
            subtasks = decision.subtasks,
        )
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

    /** Eén CLI-poging: het afgeleide [outcome] plus het ruwe [report] voor het vraag-vangnet. */
    private data class AttemptResult(val outcome: AgentOutcome, val report: ClaudeRunReport?)

    private companion object {
        const val OUTCOME_PARSE_ERROR = "error-claude-outcome-parse"
    }
}
