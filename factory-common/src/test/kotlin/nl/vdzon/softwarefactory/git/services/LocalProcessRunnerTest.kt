package nl.vdzon.softwarefactory.git.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

class LocalProcessRunnerTest {

    private val runner = LocalProcessRunner()

    @Test
    fun `captures normal output and exit code`() {
        val result = runner.run(listOf("sh", "-c", "echo hello; echo world 1>&2; exit 3"), timeoutSeconds = 10)
        assertEquals(3, result.exitCode)
        assertTrue(result.stdout.contains("hello"))
        assertTrue(result.stderr.contains("world"))
    }

    // Regressie: vóór de fix las LocalProcessRunner stdout/stderr pas ná process.waitFor(), zonder
    // ze intussen af te tappen. Een kind dat meer dan de OS-pipebuffer (~64KB) schrijft vóórdat de
    // parent begint te lezen, blokkeert dan op zijn eigen write() -- en de parent zit in waitFor() te
    // wachten op precies dat kind. Dit produceert ruim boven de buffergrens aan output, ruim binnen
    // de timeout; zonder de fix zou dit altijd op de timeout uitlopen (JUnit's @Timeout is het
    // uiteindelijke vangnet zodat een regressie de testrun niet voor onbepaalde tijd blokkeert).
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `does not deadlock when the child writes more than the OS pipe buffer before being read`() {
        val result = runner.run(
            listOf("sh", "-c", "for i in \$(seq 1 20000); do echo \"line \$i lorem ipsum dolor sit amet\"; done"),
            timeoutSeconds = 10,
        )
        assertEquals(0, result.exitCode, "mag niet op timeout uitlopen: ${result.stderr}")
        assertTrue(result.stdout.contains("line 20000"), "de laatste regel moet ook echt binnen zijn")
    }

    @Test
    fun `reports a timeout without hanging when the process truly does not finish`() {
        val result = runner.run(listOf("sh", "-c", "sleep 5"), timeoutSeconds = 1)
        assertEquals(124, result.exitCode)
        assertTrue(result.stderr.contains("timed out"))
    }
}
