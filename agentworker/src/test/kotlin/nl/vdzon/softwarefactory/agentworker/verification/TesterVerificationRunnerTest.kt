package nl.vdzon.softwarefactory.agentworker.verification

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import nl.vdzon.softwarefactory.verification.CheckoutIdentity
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TesterVerificationRunnerTest {
    @TempDir
    lateinit var repo: Path

    private val head = "a".repeat(40)
    private val tree = "b".repeat(40)
    private val clock = Clock.fixed(Instant.parse("2026-07-11T13:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `green command produces revision-bound evidence`() {
        prepareConfig()
        val runner = scripted(passed("BUILD SUCCESS"))

        val result = runner.verify(repo)

        assertTrue(result.accepted)
        assertEquals(head, result.evidence?.testedHeadSha)
        assertEquals(tree, result.evidence?.testedTreeSha)
        assertEquals("passed", result.evidence?.commands?.single()?.status)
        assertEquals(0, result.evidence?.commands?.single()?.exitCode)
    }

    @Test
    fun `nonzero timeout and tooling failure are never accepted`() {
        listOf(
            VerificationProcessResult("failed", 2, "tests failed"),
            VerificationProcessResult("timeout", null, "timed out"),
            VerificationProcessResult("tool-missing", null, "mvn missing"),
        ).forEach { failed ->
            prepareConfig()
            val result = scripted(failed).verify(repo)
            assertFalse(result.accepted)
            assertEquals(failed.status, result.evidence?.commands?.single()?.status)
        }
    }

    @Test
    fun `head or tree change during command is rejected`() {
        prepareConfig()
        val result = scripted(
            passed("green"),
            afterIdentity = CheckoutIdentity("c".repeat(40), tree),
        ).verify(repo)

        assertFalse(result.accepted)
        assertEquals("execution-error", result.evidence?.commands?.single()?.status)
        assertTrue(result.evidence?.commands?.single()?.summary.orEmpty().contains("Revision veranderde"))
    }

    @Test
    fun `missing or unknown config returns no fabricated evidence`() {
        repo.resolve(".git").createDirectories()
        val missing = scripted().verify(repo)
        assertFalse(missing.accepted)
        assertEquals(null, missing.evidence)

        repo.resolve(".factory").createDirectories()
        repo.resolve(".factory/verification.yaml").writeText("version: 99\ncommands: []")
        val unknown = scripted().verify(repo)
        assertFalse(unknown.accepted)
        assertEquals(null, unknown.evidence)
        assertNotNull(unknown.diagnosis)
    }

    private fun prepareConfig() {
        repo.resolve(".git").createDirectories()
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
    }

    private fun scripted(
        vararg results: VerificationProcessResult,
        afterIdentity: CheckoutIdentity = CheckoutIdentity(head, tree),
    ): TesterVerificationRunner {
        val queue = ArrayDeque(results.toList())
        val identities = ArrayDeque(listOf(CheckoutIdentity(head, tree), afterIdentity))
        return TesterVerificationRunner(
            processRunner = VerificationProcessRunner { _, _, _ -> queue.removeFirst() },
            clock = clock,
            identityProvider = { identities.removeFirst() },
        )
    }

    private fun passed(output: String) = VerificationProcessResult("passed", 0, output)
}
