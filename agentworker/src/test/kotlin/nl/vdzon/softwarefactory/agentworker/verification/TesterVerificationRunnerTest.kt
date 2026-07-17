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

    @Test
    fun `local runner distinguishes missing tooling and kills timed out child process`() {
        val runner = LocalVerificationProcessRunner()
        val missing = runner.run(listOf("factory-tool-that-does-not-exist-927"), repo, 1)
        assertEquals("tool-missing", missing.status)
        assertEquals(null, missing.exitCode)

        val timeout = runner.run(listOf("/bin/sh", "-c", "sleep 30 & echo ${'$'}!; wait"), repo, 1)
        assertEquals("timeout", timeout.status)
        assertEquals(null, timeout.exitCode)
        val childPid = timeout.output.lineSequence()
            .map(String::trim)
            .first { it.matches(Regex("^\\d+$")) }
            .toLong()
        // "Gekilld" bewijzen kan hier niet met alleen isAlive: in de agent-sandbox is geen
        // PID-1-subreaper, dus het verweesde, gekillde kind blijft als zombie staan en
        // ProcessHandle.isAlive() blijft dan true rapporteren (op macOS/CI reapt PID 1 wél
        // en verdwijnt het proces gewoon). Dood-of-zombie telt daarom allebei als gedood;
        // we wachten kort omdat de kill/reap net na de timeout nog kan lopen.
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!effectivelyKilled(childPid) && System.nanoTime() < deadline) {
            Thread.sleep(100)
        }
        assertTrue(effectivelyKilled(childPid), "kindproces $childPid leeft nog (en is geen zombie)")
    }

    private fun effectivelyKilled(pid: Long): Boolean {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return true
        if (!handle.isAlive) {
            return true
        }
        // /proc/<pid>/stat: "pid (comm) state ..." — comm kan spaties/haakjes bevatten,
        // dus de state is het eerste teken ná de láátste ") ". Bestaat /proc niet (macOS),
        // dan is er ook geen subreaper-probleem en telt alleen isAlive.
        val stat = runCatching {
            java.nio.file.Files.readString(Path.of("/proc/$pid/stat"))
        }.getOrNull() ?: return false
        return stat.substringAfterLast(") ").firstOrNull() == 'Z'
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
