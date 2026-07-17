package nl.vdzon.softwarefactory.runtime.services

import nl.vdzon.softwarefactory.runtime.models.*
import nl.vdzon.softwarefactory.runtime.types.*

import nl.vdzon.softwarefactory.contract.AgentResultVerificationEvidence
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AgentRunRepository
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.runtime.models.AgentRunCompleteRequest
import nl.vdzon.softwarefactory.verification.VerificationConfigParser
import nl.vdzon.softwarefactory.verification.CheckoutIdentityResolver
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.Duration

/** Onafhankelijke factory-side gate voor ieder door een tester gerapporteerd `tested`. */
@Component
class TesterVerificationEvidenceValidator(
    private val agentRunRepository: AgentRunRepository,
    private val gitApi: GitApi,
) {
    private val checkoutIdentityResolver = CheckoutIdentityResolver(gitApi)

    fun enforce(request: AgentRunCompleteRequest): AgentRunCompleteRequest =
        if (request.role != AgentRole.TESTER.markerKeyPart || request.phase != "tested") {
            request
        } else {
            validate(request)?.let { rejection -> rejected(request, rejection) } ?: request
        }

    private fun validate(request: AgentRunCompleteRequest): String? {
        val validation = runCatching {
            val evidence = requireNotNull(request.verificationEvidence) { "gestructureerd testerbewijs ontbreekt" }
            val run = requireNotNull(
                agentRunRepository.activeRuns().firstOrNull { it.containerName == request.containerName },
            ) { "actieve tester-run ontbreekt" }
            val repoRoot = requireNotNull(repositoryRoot(run.workspacePath)) { "testerworkspace/repository ontbreekt" }
            val config = VerificationConfigParser.parse(repoRoot)
            require(evidence.configVersion == config.version) {
                "configversie mismatch: bewijs=${evidence.configVersion}, checkout=${config.version}"
            }
            val expectedIds = config.commands.filter { it.agentRunnable }.map { it.id }
            val actualIds = evidence.commands.map { it.commandId }
            require(actualIds == expectedIds) {
                "commandobewijs mismatch: verwacht=$expectedIds, ontvangen=$actualIds"
            }
            validateCommands(evidence)
            val identity = requireNotNull(checkoutIdentityResolver.resolve(repoRoot)) {
                "actuele HEAD/worktree-tree niet leesbaar"
            }
            require(evidence.testedHeadSha.equals(identity.headSha, ignoreCase = true)) {
                "HEAD mismatch: bewijs=${evidence.testedHeadSha}, checkout=${identity.headSha}"
            }
            require(evidence.testedTreeSha.equals(identity.treeSha, ignoreCase = true)) {
                "tree mismatch: bewijs=${evidence.testedTreeSha}, checkout=${identity.treeSha}"
            }
        }
        return validation.exceptionOrNull()?.message
    }

    private fun validateCommands(evidence: AgentResultVerificationEvidence) {
        require(SHA.matches(evidence.testedHeadSha) && SHA.matches(evidence.testedTreeSha)) {
            "HEAD/tree in bewijs heeft geen geldige SHA-vorm"
        }
        evidence.commands.forEach { command ->
            require(command.status == "passed" && command.exitCode == 0) {
                "command ${command.commandId} niet groen: status=${command.status}, exitCode=${command.exitCode}"
            }
            val started = requireNotNull(runCatching { Instant.parse(command.startedAt) }.getOrNull()) {
                "command ${command.commandId} heeft ongeldige starttijd"
            }
            val ended = requireNotNull(runCatching { Instant.parse(command.endedAt) }.getOrNull()) {
                "command ${command.commandId} heeft ongeldige eindtijd"
            }
            require(!ended.isBefore(started) && command.durationMs >= 0) {
                "command ${command.commandId} heeft ongeldige duur"
            }
            // Exacte gelijkheid was te broos: agents berekenen start/eind en de gerapporteerde duur
            // soms via net iets andere klok-calls (bv. seconden- i.p.v. milliseconde-precisie), wat
            // tot een paar honderd ms afronding kan leiden zonder dat er iets mis is met het bewijs
            // zelf. Kleine tolerantie voorkomt onnodige reject-retry-loops (zie SF-957).
            val computedMillis = Duration.between(started, ended).toMillis()
            require(kotlin.math.abs(computedMillis - command.durationMs) <= DURATION_TOLERANCE_MILLIS) {
                "command ${command.commandId} heeft een duur die niet met start/eind overeenkomt " +
                    "(berekend=${computedMillis}ms, gerapporteerd=${command.durationMs}ms)"
            }
            require(!command.summary.isNullOrBlank() || !command.reportLocation.isNullOrBlank()) {
                "command ${command.commandId} mist rapportlocatie of samenvatting"
            }
            require(command.summary.orEmpty().length <= MAX_SUMMARY_CHARS) {
                "command ${command.commandId} heeft een onbegrensde samenvatting"
            }
            require(command.reportLocation.orEmpty().length <= MAX_REPORT_LOCATION_CHARS) {
                "command ${command.commandId} heeft een onbegrensde rapportlocatie"
            }
        }
    }

    private fun repositoryRoot(workspacePath: String?): Path? {
        val workspace = workspacePath?.let(Path::of)
        return workspace?.resolve("repo")?.takeIf(Files::isDirectory)
            ?: workspace?.takeIf { Files.isDirectory(it.resolve(".git")) }
    }

    private fun rejected(request: AgentRunCompleteRequest, rejection: String): AgentRunCompleteRequest =
        request.copy(
            phase = "test-rejected",
            outcome = "test-rejected",
            exitCode = 0,
            summaryText = buildString {
                append(request.summaryText.orEmpty())
                if (!request.summaryText.isNullOrBlank()) append("\n\n")
                append("[FACTORY EVIDENCE REJECTED] ")
                append(rejection)
            },
        )

    private companion object {
        val SHA = Regex("^[0-9a-fA-F]{40,64}$")
        const val MAX_SUMMARY_CHARS = 4000
        const val MAX_REPORT_LOCATION_CHARS = 1024
        const val DURATION_TOLERANCE_MILLIS = 2000L
    }
}
