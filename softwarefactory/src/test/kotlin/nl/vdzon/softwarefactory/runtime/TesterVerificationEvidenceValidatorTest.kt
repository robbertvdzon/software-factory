package nl.vdzon.softwarefactory.runtime

import nl.vdzon.softwarefactory.runtime.models.*
import nl.vdzon.softwarefactory.runtime.types.*

import nl.vdzon.softwarefactory.contract.AgentResultVerificationCommand
import nl.vdzon.softwarefactory.contract.AgentResultVerificationEvidence
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.AgentRunRecord
import nl.vdzon.softwarefactory.core.contracts.AgentRunRepository
import nl.vdzon.softwarefactory.git.GitApi
import nl.vdzon.softwarefactory.runtime.services.TesterVerificationEvidenceValidator
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentRunCompletionTesterEvidenceTest {
    @TempDir
    lateinit var repo: Path

    private lateinit var head: String
    private lateinit var tree: String
    private lateinit var validator: TesterVerificationEvidenceValidator

    @BeforeEach
    fun setUp() {
        repo.resolve(".factory").createDirectories()
        repo.resolve(".factory/verification.yaml").writeText(
            """
            version: 1
            commands:
              - id: repository-verify
                argv: [mvn, verify]
                workingDirectory: .
                timeoutSeconds: 60
            """.trimIndent(),
        )
        git("init")
        git("config", "user.email", "test@example.invalid")
        git("config", "user.name", "Test")
        git("add", ".factory/verification.yaml")
        git("commit", "-m", "fixture")
        head = git("rev-parse", "HEAD")
        tree = git("rev-parse", "HEAD^{tree}")
        val runs = mock(AgentRunRepository::class.java)
        `when`(runs.activeRuns()).thenReturn(
            listOf(
                AgentRunRecord(
                    id = 1,
                    storyRunId = 2,
                    role = AgentRole.TESTER,
                    containerName = "tester-1",
                    startedAt = OffsetDateTime.now(),
                    endedAt = null,
                    outcome = null,
                    summaryText = null,
                    workspacePath = repo.toString(),
                ),
            ),
        )
        validator = TesterVerificationEvidenceValidator(runs, GitApi.default())
    }

    @Test
    fun `complete green evidence for exact checkout remains tested`() {
        val result = validator.enforce(tested(evidence()))

        assertEquals("tested", result.phase)
        assertEquals("ok", result.outcome)
    }

    @Test
    fun `missing red and revision-mismatched evidence reset to test-rejected`() {
        val missing = validator.enforce(tested(null))
        assertRejected(missing, "ontbreekt")

        val red = evidence().copy(commands = listOf(command(status = "failed", exitCode = 1)))
        assertRejected(validator.enforce(tested(red)), "niet groen")

        val mismatched = evidence().copy(testedHeadSha = "f".repeat(40))
        assertRejected(validator.enforce(tested(mismatched)), "HEAD mismatch")
    }

    @Test
    fun `small duration rounding drift within tolerance still counts as tested`() {
        // Agents berekenen start/eind en de gerapporteerde duur soms via net iets andere
        // klok-calls (seconden- i.p.v. milliseconde-precisie) — een paar honderd ms afronding is
        // geen echt bewijsprobleem (zie SF-957). 60_000ms werkelijk vs 59_100ms gerapporteerd = 900ms
        // afwijking, binnen de tolerantie.
        val slightlyOff = evidence().copy(commands = listOf(command().copy(durationMs = 59_100)))

        val result = validator.enforce(tested(slightlyOff))

        assertEquals("tested", result.phase)
    }

    @Test
    fun `tooling timeout malformed timing and prose-only proof are rejected`() {
        listOf("tool-missing", "timeout").forEach { status ->
            val invalid = evidence().copy(commands = listOf(command(status = status, exitCode = null)))
            assertRejected(validator.enforce(tested(invalid)), "niet groen")
        }
        val malformed = evidence().copy(commands = listOf(command(startedAt = "not-a-time")))
        assertRejected(validator.enforce(tested(malformed)), "starttijd")
        val mismatchedDuration = evidence().copy(commands = listOf(command().copy(durationMs = 1)))
        assertRejected(validator.enforce(tested(mismatchedDuration)), "niet met start/eind")
        val oversizedReport = evidence().copy(
            commands = listOf(command().copy(summary = null, reportLocation = "x".repeat(1025))),
        )
        assertRejected(validator.enforce(tested(oversizedReport)), "onbegrensde rapportlocatie")
        val proseOnly = tested(null).copy(summaryText = "Alle tests zijn groen, echt waar.")
        assertRejected(validator.enforce(proseOnly), "ontbreekt")
    }

    @Test
    fun `old non-tester and explicit tester rejection stay backward compatible`() {
        val developer = tested(null).copy(role = "developer", phase = "developed")
        assertEquals(developer, validator.enforce(developer))
        val rejected = tested(null).copy(phase = "test-rejected", outcome = "test-rejected")
        assertEquals(rejected, validator.enforce(rejected))
    }

    @Test
    fun `agentRunnable false commands hoeven geen bewijs te leveren`() {
        // Config met een tweede, CI-only command (bv. een docker-image-build zonder docker-CLI
        // in de agent-container) — de validator mag daar géén bewijs voor eisen.
        repo.resolve(".factory/verification.yaml").writeText(
            """
            version: 1
            commands:
              - id: repository-verify
                argv: [mvn, verify]
                workingDirectory: .
                timeoutSeconds: 60
              - id: image-build
                agentRunnable: false
                argv: [docker, build, .]
                workingDirectory: .
                timeoutSeconds: 60
            """.trimIndent(),
        )
        git("add", ".factory/verification.yaml")
        git("commit", "-m", "add ci-only command")
        val newHead = git("rev-parse", "HEAD")
        val newTree = git("rev-parse", "HEAD^{tree}")
        val evidenceWithoutCiOnlyCommand = AgentResultVerificationEvidence(1, newHead, newTree, listOf(command()))

        val result = validator.enforce(tested(evidenceWithoutCiOnlyCommand))

        assertEquals("tested", result.phase)
        assertEquals("ok", result.outcome)
    }

    @Test
    fun `skipped status voor een out-of-scope pathPrefixes-command blijft geldig bewijs`() {
        // De agent-harness draait dit command niet (diff raakt dashboard-frontend/ niet) en
        // rapporteert status=skipped i.p.v. het te draaien; de validator mag dat niet afkeuren
        // zolang het commando wél aanwezig is in de evidence (expectedIds blijft ongewijzigd —
        // pathPrefixes beïnvloedt alleen agentRunnable=true-commando's, niet welke ids verwacht
        // worden).
        repo.resolve(".factory/verification.yaml").writeText(
            """
            version: 1
            commands:
              - id: repository-verify
                argv: [mvn, verify]
                workingDirectory: .
                timeoutSeconds: 60
              - id: frontend-test
                pathPrefixes: [dashboard-frontend/]
                argv: [flutter, test]
                workingDirectory: .
                timeoutSeconds: 60
            """.trimIndent(),
        )
        git("add", ".factory/verification.yaml")
        git("commit", "-m", "add scoped command")
        val newHead = git("rev-parse", "HEAD")
        val newTree = git("rev-parse", "HEAD^{tree}")
        val skippedCommand = command(status = "skipped", exitCode = null).copy(commandId = "frontend-test")
        val evidenceWithSkip = AgentResultVerificationEvidence(1, newHead, newTree, listOf(command(), skippedCommand))

        val result = validator.enforce(tested(evidenceWithSkip))

        assertEquals("tested", result.phase)
        assertEquals("ok", result.outcome)
    }

    private fun tested(evidence: AgentResultVerificationEvidence?) =
        AgentRunCompleteRequest(
            storyKey = "SF-1",
            role = "tester",
            containerName = "tester-1",
            phase = "tested",
            outcome = "ok",
            summaryText = "tester says green",
            verificationEvidence = evidence,
        )

    private fun evidence() =
        AgentResultVerificationEvidence(1, head, tree, listOf(command()))

    private fun command(
        status: String = "passed",
        exitCode: Int? = 0,
        startedAt: String = "2026-07-11T13:00:00Z",
    ) = AgentResultVerificationCommand(
        commandId = "repository-verify",
        startedAt = startedAt,
        endedAt = "2026-07-11T13:01:00Z",
        durationMs = 60_000,
        exitCode = exitCode,
        status = status,
        summary = "bounded output",
    )

    private fun assertRejected(request: AgentRunCompleteRequest, diagnosis: String) {
        assertEquals("test-rejected", request.phase)
        assertEquals("test-rejected", request.outcome)
        assertEquals(0, request.exitCode)
        assertTrue(request.summaryText.orEmpty().contains(diagnosis))
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", *args)).directory(repo.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { output }
        return output
    }
}
