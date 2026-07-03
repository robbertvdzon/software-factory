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
        val process = ProcessBuilder(command)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .also { builder -> builder.environment().putAll(env) }
            .start()

        // We sturen niets via stdin; sluit het meteen zodat de CLI direct EOF
        // krijgt i.p.v. te blokkeren op het lezen van stdin.
        process.outputStream.close()

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
            taskFile.deleteIfExists()
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
            knowledgeUpdates = ClaudeOutcomeParser.extractKnowledgeUpdates(report.summaryText),
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
            add(ClaudePromptBuilder.systemPrompt(context.role, context.effort))
            add("--permission-mode")
            add("bypassPermissions")
            add("--verbose")
            add("--output-format")
            add("stream-json")
            add("--print")
            val userPrompt = ClaudePromptBuilder.userPrompt(context.role)
            add(if (retry) userPrompt + "\n\n" + ClaudePromptBuilder.retryContractReminder(context.role) else userPrompt)
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

    /** Eén CLI-poging: het afgeleide [outcome] plus het ruwe [report] voor het vraag-vangnet. */
    private data class AttemptResult(val outcome: AgentOutcome, val report: ClaudeRunReport?)

    private companion object {
        const val OUTCOME_PARSE_ERROR = "error-claude-outcome-parse"
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
            AgentRole.PLANNER -> "Lees de gerefinede story in .task.md en maak een implementatieplan in de story-body. Volg exact het JSON-outputcontract uit de system prompt."
            AgentRole.DEVELOPER -> "Implementeer de story uit .task.md. Maak lokale wijzigingen op deze branch; commit, push en PR-acties worden na jouw run door de factory gedaan."
            AgentRole.REVIEWER -> "Review de branch aan de hand van .task.md en de repo. Volg exact het JSON-outputcontract uit de system prompt."
            AgentRole.TESTER -> "Test de branch aan de hand van .task.md en beschikbare preview-context. Volg exact het JSON-outputcontract uit de system prompt."
            AgentRole.SUMMARIZER -> "Maak de eindsamenvatting van deze story. Volg exact het JSON-outputcontract uit de system prompt."
            AgentRole.DOCUMENTER -> "Werk de relevante documentatie bij obv .task.md en de story-diff. Volg exact het JSON-outputcontract uit de system prompt."
            AgentRole.ASSISTANT, // assistent draait server-side, nooit via de agentworker-CLI
            AgentRole.COST_MONITOR,
            AgentRole.ORCHESTRATOR,
            -> error("Role $role is not supported by Claude Code.")
        }

    /**
     * Extra herinnering die bij een retry achter de user-prompt wordt geplakt: de vorige run leverde
     * geen parsebaar JSON-besluit. Bevat een rol-specifiek voorbeeld zodat het contract glashelder is.
     */
    fun retryContractReminder(role: AgentRole): String =
        "BELANGRIJK: je vorige antwoord miste het verplichte JSON-besluit, waardoor de factory het niet " +
            "kon verwerken. Voer geen werk opnieuw uit dat al klaar is; geef alleen je beslissing en eindig " +
            "met EXACT één JSON-object op de laatste regel, volgens het contract. Voorbeeld voor jouw rol: " +
            retryExample(role)

    private fun retryExample(role: AgentRole): String =
        when (role) {
            AgentRole.REFINER -> """{"phase":"refined"} of {"phase":"refined-with-questions","questions":["vraag 1"]}"""
            AgentRole.PLANNER -> """{"phase":"planned","subtasks":[{"type":"development","title":"..."}]} of {"phase":"planned-with-questions","questions":["vraag 1"]}"""
            AgentRole.REVIEWER -> """{"phase":"reviewed"} of {"phase":"review-rejected"} of {"phase":"reviewed-with-questions","questions":["vraag 1"]}"""
            AgentRole.TESTER -> """{"phase":"tested"} of {"phase":"test-rejected"} of {"phase":"tested-with-questions","questions":["vraag 1"]}"""
            AgentRole.SUMMARIZER -> """{"phase":"summarized"} of {"phase":"summary-with-questions","questions":["vraag 1"]}"""
            AgentRole.DOCUMENTER -> """{"phase":"documented"} of {"phase":"documentation-with-questions","questions":["vraag 1"]}"""
            AgentRole.DEVELOPER,
            AgentRole.ASSISTANT, // assistent draait server-side, nooit via de agentworker-CLI
            AgentRole.COST_MONITOR,
            AgentRole.ORCHESTRATOR,
            -> """{"phase":"..."}"""
        }

    private fun rolePrompt(role: AgentRole): String =
        when (role) {
            AgentRole.REFINER -> """
                Refiner-regels:
                - Schrijf geen code en wijzig geen bestanden.
                - Stel alleen blokkerende vragen; beantwoord alles wat je zelf in repo/docs kunt vinden.
                - Bij voldoende duidelijkheid: beschrijf aannames op gedragsniveau.
                - Bij {"phase":"refined"} (geen blokkerende vragen): lever het definitieve, zelfstandig
                  leesbare story-voorstel (scope, acceptatiecriteria, aannames) afgebakend met exact deze
                  twee markers, elk op een eigen regel:
                  <!-- proposed-description:start -->
                  ## Scope
                  ...
                  ## Acceptance criteria
                  ...
                  ## Aannames
                  ...
                  <!-- proposed-description:end -->
                  Alles tússen de markers wordt na menselijke goedkeuring de nieuwe story-description.
                  Zet daar dus alleen de afgesproken spec, als nette description (geen "ik heb X gelezen"-
                  preambule). Meta-commentaar en de JSON-regels horen buíten het blok.
                - Laatste regel is exact een JSON-object:
                  {"phase":"refined"}
                  of
                  {"phase":"refined-with-questions","questions":["vraag 1"]}
            """.trimIndent()
            AgentRole.PLANNER -> """
                Planner-regels:
                - Schrijf geen code. Maak een implementatieplan in de story-body en
                  **declareer** de subtaken in de JSON-output (de factory maakt ze aan, jij niet).
                - Beschrijf de aanpak op gedragsniveau; benoem geraakte modules en risico's.
                - Subtask-types: development / review / test / manual / summary.
                - **Het schrijven van (unit)tests is ontwikkelwerk** en hoort bij de `development`-subtaak.
                  Maak NOOIT een `test`-subtaak om tests te schrijven. De `test`-subtaak is uitsluitend
                  voor de tester, die alleen verifieert (build/tests draaien, gedrag controleren) en zelf
                  niets maakt. Eén story-brede `test`-subtaak volstaat.
                - **Houd het aantal subtaken minimaal.** De standaard is precies DRIE subtaken:
                  ÉÉN `development` (al het ontwikkelwerk samen), gevolgd door één story-brede
                  `test` en één `summary`.
                - **Maak GEEN aparte `review`-subtaak in het standaardgeval.** De `development`-subtaak
                  bevat zelf al een review-stap; die volstaat. Voeg alleen een story-brede `review`-subtaak
                  toe als de gebruiker daar in de issue-comments expliciet om vraagt (dan komt die direct
                  na de laatste `development`-subtaak).
                - Splits het ontwikkelwerk ALLEEN in meerdere `development`-subtaken als het echt
                  complex/omvangrijk is en opdeling duidelijke waarde heeft; in dat geval beschrijf je
                  in het plan kort waarom. Twijfel je? → houd het op één development-subtaak. De meeste
                  stories zijn één dev-stap; meerdere is de uitzondering, niet de regel.
                - Honoreer een expliciet verzoek van de gebruiker over het aantal/opdeling van subtaken
                  (in de issue-comments) strikt.
                - **De proza en de JSON moeten exact overeenkomen**: de subtaken die je in de
                  plantekst noemt zijn precies dezelfde (zelfde aantal, types en opdeling) als in de
                  `subtasks`-array. Geen tegenstrijdigheid — de JSON is leidend, dus schrijf de proza
                  ernaartoe. Noem in de proza nooit een ander aantal dan in de JSON staat.
                - Stel alleen blokkerende vragen als het plan niet te maken is zonder antwoord.
                - Laatste regel is exact een JSON-object (standaardgeval, drie subtaken):
                  {"phase":"planned","subtasks":[{"type":"development","title":"...","description":"..."},{"type":"test","title":"Story-brede test"},{"type":"summary","title":"Eindsamenvatting"}]}
                  of
                  {"phase":"planned-with-questions","questions":["vraag 1"]}
            """.trimIndent()
            AgentRole.DEVELOPER -> """
                Developer-regels:
                - Implementeer de story op de huidige branch.
                - **Schrijf zelf alle (unit)tests** voor je wijziging — testen schrijven is ontwikkelwerk,
                  niet de taak van de tester. Draai die tests/build ook.
                - Houd docs/stories/worklog/<issue-key>-worklog.md bij als die bestaat of nodig is.
                - Voer nooit git commit, git push, gh pr create/update/merge of andere PR-acties uit.
                - Laat alle wijzigingen uncommitted in de working tree; de factory commit, pusht en opent/bijwerkt de PR na jouw run.
                - Eindig met een handover met exact deze koppen: Samenvatting, Gedaan, Niet gedaan / aangepast.
            """.trimIndent()
            AgentRole.REVIEWER -> """
                Reviewer-regels:
                - Schrijf geen code en wijzig geen implementatiebestanden.
                - Je mag alleen docs/stories/worklog/<issue-key>-worklog.md bijwerken als je review-notities of voortgang toevoegt.
                - Voer nooit git commit, git push, gh pr create/update/merge of andere PR-acties uit.
                  Laat eventuele worklog-wijzigingen uncommitted in de working tree; de factory commit en pusht na jouw run.
                - **Beoordeel de VOLLEDIGE story-diff t.o.v. de base-branch (`git diff <base-branch>...HEAD`),
                  niet alleen de meest recente wijziging.** De story kan uit meerdere subtaken bestaan die
                  allemaal op deze branch committen; je reviewt al die code samen, inclusief werk van eerdere
                  subtaken. De base-branch staat in de Factory Task-kop ("Base branch").
                - Beoordeel bugs, regressies, scope en testdekking.
                - Gebruik bevinding-prefixes [blocker], [bug], [suggestie], [info].
                - Laatste regel is exact een JSON-object:
                  {"phase":"reviewed"}                 (akkoord)
                  of {"phase":"reviewed-with-questions","questions":["vraag 1"]}
                  of {"phase":"review-rejected"}        (findings → terug naar developer)
            """.trimIndent()
            AgentRole.TESTER -> """
                Tester-regels:
                - Je VERIFIEERT alleen — je schrijft GEEN code en GEEN tests, en maakt verder niets aan.
                  De developer schrijft alle code ÉN alle (unit)tests; dat is uitdrukkelijk niet jouw taak.
                - Jouw taak: controleer of de code correct is en of de applicatie zich gedraagt zoals de
                  story vereist. Draai bestaande tests/build, test het gedrag, en gebruik
                  browser/preview-context wanneer beschikbaar.
                - Maak bij browser/preview-tests screenshots en laat ze in /work/screenshots staan.
                - Wijzig geen code, tests of infra. Je mag UITSLUITEND tijdelijke testdata (met cleanup)
                  en docs/stories/worklog/<issue-key>-worklog.md aanpassen.
                - Voer nooit git commit, git push, gh pr create/update/merge of andere PR-acties uit.
                  Laat wijzigingen uncommitted in de working tree; de factory commit en pusht na jouw run.
                - Vind je een probleem? → `test-rejected` (terug naar de developer). Fix het niet zelf.
                - Laatste regel is exact een JSON-object:
                  {"phase":"tested"}                   (geslaagd)
                  of {"phase":"tested-with-questions","questions":["vraag 1"]}
                  of {"phase":"test-rejected"}          (bug → terug naar developer)
            """.trimIndent()
            AgentRole.SUMMARIZER -> """
                Summarizer-regels:
                - Schrijf geen code en wijzig geen implementatiebestanden.
                - Lees .task.md, docs/stories/worklog en de relevante agent-comments in de task-context.
                - Maak een compacte eindsamenvatting voor de PO: wat is gebouwd, welke keuzes zijn gemaakt, wat is getest en wat eventueel bewust niet is gedaan.
                - De factory schrijft jouw samenvatting daarna naar YouTrack en naar docs/stories/<issue-key>-<korte-omschrijving>.md.
                - Laatste regel is exact een JSON-object:
                  {"phase":"summarized"}
                  of {"phase":"summary-with-questions","questions":["vraag 1"]}
            """.trimIndent()
            AgentRole.DOCUMENTER -> """
                Documenter-regels:
                - Werk ALLE relevante documentatie bij zodat die klopt met wat er in deze story is gedaan:
                  README's, `docs/` (incl. docs/factory functional-spec/technical-spec en UX-docs), runbook,
                  changelogs, API-docs e.d. Bepaal zelf welke docs geraakt zijn obv .task.md en de story-diff
                  (`git diff <base-branch>...HEAD`).
                - Schrijf GEEN productiecode en wijzig geen implementatiebestanden of tests; je raakt alleen
                  documentatie aan.
                - Voer nooit git commit, git push, gh pr create/update/merge of andere PR-acties uit.
                  Laat de doc-wijzigingen uncommitted in de working tree; de factory commit en pusht na jouw run.
                - Is de bestaande documentatie al correct, dan hoef je niets te wijzigen; rapporteer dat dan.
                - Stel alleen blokkerende vragen als je de docs niet kunt bijwerken zonder antwoord.
                - Laatste regel is exact een JSON-object:
                  {"phase":"documented"}
                  of {"phase":"documentation-with-questions","questions":["vraag 1"]}
            """.trimIndent()
            AgentRole.ASSISTANT, // assistent draait server-side, nooit via de agentworker-CLI
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
    val subtasks: List<AgentSubtaskSpec> = emptyList(),
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
        val jsonNodes = jsonObjects(normalized).mapNotNull { parseJson(it) }
        val candidates = jsonNodes
            .mapNotNull { it.path("phase").asText(null) }
            .mapNotNull { mapPhase(role, it) }
        val subtasks = if (role == AgentRole.PLANNER) extractSubtasks(jsonNodes) else emptyList()
        if (candidates.isNotEmpty()) {
            return ClaudeDecision(candidates.last(), subtasks)
        }

        val regexCandidate = phasePattern.findAll(normalized)
            .mapNotNull { mapPhase(role, it.groupValues[1]) }
            .lastOrNull()
        return regexCandidate?.let { ClaudeDecision(it, subtasks) }
    }

    /** Haal een door de planner gedeclareerde `subtasks`-array uit de JSON-output. */
    private fun extractSubtasks(jsonNodes: List<JsonNode>): List<AgentSubtaskSpec> =
        jsonNodes.asReversed().firstNotNullOfOrNull { root ->
            val array = root.path("subtasks").takeIf { it.isArray } ?: return@firstNotNullOfOrNull null
            array.mapNotNull { node ->
                val type = node.path("type").asText("").trim()
                val title = node.path("title").asText("").trim()
                if (type.isBlank() || title.isBlank()) {
                    null
                } else {
                    AgentSubtaskSpec(
                        type = type,
                        title = title,
                        description = node.path("description").asText("").trim().takeIf { it.isNotBlank() },
                        model = node.path("model").asText("").trim().takeIf { it.isNotBlank() },
                        effort = node.path("effort").asText("").trim().takeIf { it.isNotBlank() },
                    )
                }
            }
        }.orEmpty()

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
                -> "refined"
                "awaiting-po",
                "refined-with-questions",
                "refined-with-questions-for-user",
                -> "refined-with-questions"
                else -> null
            }
            AgentRole.PLANNER -> when (phase) {
                "planned",
                "planned-finished",
                "planning-finished",
                -> "planned"
                "awaiting-po",
                "planned-with-questions",
                "planning-with-questions",
                -> "planned-with-questions"
                else -> null
            }
            AgentRole.REVIEWER -> when (phase) {
                "reviewed-ok",
                "reviewed",
                "review-finished",
                -> "reviewed"
                "reviewed-with-questions",
                "awaiting-po",
                -> "reviewed-with-questions"
                "reviewed-changes",
                "review-rejected",
                "reviewed-with-feedback-for-developer",
                -> "review-rejected"
                else -> null
            }
            AgentRole.TESTER -> when (phase) {
                "tested-ok",
                "tested",
                "tested-successfully",
                -> "tested"
                "tested-with-questions",
                "awaiting-po",
                -> "tested-with-questions"
                "tested-fail",
                "test-rejected",
                "tested-with-feedback-for-developer",
                -> "test-rejected"
                else -> null
            }
            AgentRole.SUMMARIZER -> when (phase) {
                "summary-finished",
                "summarized",
                "summarized-finished",
                -> "summarized"
                "summary-with-questions",
                "awaiting-po",
                -> "summary-with-questions"
                else -> null
            }
            AgentRole.DOCUMENTER -> when (phase) {
                "documented",
                "documentation-finished",
                "documented-finished",
                -> "documented"
                "documentation-with-questions",
                "awaiting-po",
                -> "documentation-with-questions"
                else -> null
            }
            AgentRole.DEVELOPER -> when (phase) {
                "developed",
                "developed-finished",
                -> "developed"
                "developed-with-questions",
                "awaiting-po",
                -> "developed-with-questions"
                else -> null
            }
            AgentRole.ASSISTANT, // assistent draait server-side, nooit via de agentworker-CLI
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
