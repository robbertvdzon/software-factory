package nl.vdzon.softwarefactory.agentworker.cli

import nl.vdzon.softwarefactory.agent.AgentContext
import nl.vdzon.softwarefactory.agent.AgentEvent
import nl.vdzon.softwarefactory.agent.AgentOutcome
import nl.vdzon.softwarefactory.agent.AiClientFactory
import nl.vdzon.softwarefactory.contract.AgentResultEvent
import nl.vdzon.softwarefactory.contract.AgentResultFile
import nl.vdzon.softwarefactory.contract.AgentResultKnowledgeUpdate
import nl.vdzon.softwarefactory.contract.AgentResultSubtask
import nl.vdzon.softwarefactory.contract.AgentResultVerificationEvidence
import nl.vdzon.softwarefactory.agentworker.verification.TesterVerificationRunner
import nl.vdzon.softwarefactory.agentworker.flows.DeveloperRepositoryFlow
import nl.vdzon.softwarefactory.agentworker.flows.TargetRepositoryPreparer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.docs.DocsApi
import nl.vdzon.softwarefactory.core.AgentRole
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
    exitProcess(runAgent(System.getenv()))
}

/**
 * Testbare hoofdloop van de agent-CLI: draait de volledige agent-flow op basis van een
 * env-map en geeft de exit-code terug. [main] roept dit aan met `System.getenv()` en
 * beëindigt daarna het proces; tests geven een eigen map mee. Elke fout in de flow
 * eindigt in een result-file met een error-outcome i.p.v. een crash.
 */
fun runAgent(env: Map<String, String>): Int {
    val ticketKey = requireEnv(env, "SF_TICKET_KEY")
    val role = parseRole(requireEnv(env, "SF_AGENT_TYPE"))
    val completionEvents = mutableListOf<AgentEvent>()
    val resultFile = env["SF_AGENT_RESULT_FILE"] ?: "/work/agent-result.json"
    println("Agent worker started: story=$ticketKey role=${role.markerKeyPart} resultFile=$resultFile")

    var outcome = executeAgent(env, ticketKey, role)
    var verificationEvidence: AgentResultVerificationEvidence? = null
    // Voor het diff-scopen van verification-commands (pathPrefixes, zie VerificationCommand):
    // ontbreekt deze (bv. oudere dispatch-aanroep), dan draait de harness gewoon altijd alles —
    // veilige kant, kost hooguit tijd, nooit zekerheid.
    val baseBranch = env["SF_BASE_BRANCH"]?.takeIf { it.isNotBlank() }
    if (role == AgentRole.TESTER && outcome.exitCode == 0 && outcome.phase == "tested") {
        val repoRoot = Path.of(env["SF_REPO_ROOT"] ?: "/work/repo")
        val verification = TesterVerificationRunner().verify(repoRoot, baseBranch)
        verificationEvidence = verification.evidence
        if (!verification.accepted) {
            outcome = outcome.copy(
                phase = "test-rejected",
                outcome = "test-rejected",
                comment = "${outcome.comment}\n\n[FACTORY VERIFICATION] ${verification.diagnosis}",
                exitCode = 0,
            )
        }
    }
    // Zelfde deterministische poort direct ná de developer: een rood vangnet komt zo een volledige
    // review-ronde eerder boven (directe loopback mét diagnose i.p.v. pas bij de tester), en
    // "de developer zegt dat het groen is" is voortaan altijd harness-geverifieerd.
    if (role == AgentRole.DEVELOPER && outcome.exitCode == 0 && outcome.phase == "developed") {
        val repoRoot = Path.of(env["SF_REPO_ROOT"] ?: "/work/repo")
        val verification = TesterVerificationRunner().verify(repoRoot, baseBranch)
        verificationEvidence = verification.evidence
        if (!verification.accepted) {
            outcome = outcome.copy(
                phase = "development-rejected",
                outcome = "development-rejected",
                comment = "${outcome.comment}\n\n[FACTORY VERIFICATION] ${verification.diagnosis}",
                exitCode = 0,
            )
        }
    }
    return finish(env, ticketKey, role, outcome, completionEvents, verificationEvidence)
}

/** De eigenlijke agent-flow; elke setup-fout resulteert in een vroege error-outcome-return. */
private fun executeAgent(
    env: Map<String, String>,
    ticketKey: String,
    role: AgentRole,
): AgentOutcome {
    val baseTaskMarkdown = Path.of("/work/task.md").takeIf { it.toFile().exists() }?.readText().orEmpty()

    val repositorySession = runCatching {
        TargetRepositoryPreparer().prepare(env, ticketKey, role)
    }.getOrElse { exception ->
        return setupErrorOutcome(role, exception)
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
            return setupErrorOutcome(role, exception)
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
        return setupErrorOutcome(role, exception, stage = "prompt build")
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

    return outcome
}

private fun finish(
    env: Map<String, String>,
    ticketKey: String,
    role: AgentRole,
    outcome: AgentOutcome,
    completionEvents: List<AgentEvent>,
    verificationEvidence: AgentResultVerificationEvidence?,
): Int {
    val supplier = AiClientFactory.normalizedSupplier(env["SF_AI_SUPPLIER"])
    val usesMockDelay = supplier.isBlank() || supplier == "mock" || supplier == "dummy" || supplier == "none"
    if (usesMockDelay && env["SF_DUMMY_SKIP_SLEEP"]?.toBooleanStrictOrNull() != true) {
        Thread.sleep(5000)
    }

    writeResult(env, ticketKey, role, outcome, completionEvents, verificationEvidence)
    return outcome.exitCode
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
    verificationEvidence: AgentResultVerificationEvidence?,
) {
    val usage = outcome.usage
    // Wire-formaat: het gedeelde contract-DTO uit factory-common; de factory-poller leest hetzelfde type.
    val result = AgentResultFile(
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
            ).map { AgentResultEvent(it.kind, it.payload) },
        knowledgeUpdates = outcome.knowledgeUpdates.map { AgentResultKnowledgeUpdate(it.category, it.key, it.content) },
        subtasks = outcome.subtasks.map { AgentResultSubtask(it.type, it.title, it.description, it.model, it.effort) },
        verificationEvidence = verificationEvidence,
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
