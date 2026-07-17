package nl.vdzon.softwarefactory.verification

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerificationConfigParserTest {
    @TempDir
    lateinit var repo: Path

    @Test
    fun `parses versioned argv config without shell expansion`() {
        repo.resolve("backend").createDirectories()
        config(
            """
            version: 1
            commands:
              - id: backend-verify
                argv: [mvn, -B, verify]
                workingDirectory: backend
                timeoutSeconds: 900
            """,
        )

        val parsed = VerificationConfigParser.parse(repo)

        assertEquals(1, parsed.version)
        assertEquals(listOf("mvn", "-B", "verify"), parsed.commands.single().argv)
        assertEquals("backend", parsed.commands.single().workingDirectory)
        // Ontbrekend agentRunnable → default true (bestaande configs blijven ongewijzigd).
        assertTrue(parsed.commands.single().agentRunnable)
    }

    @Test
    fun `agentRunnable false markeert een command als CI-only`() {
        repo.resolve("backend").createDirectories()
        config(
            """
            version: 1
            commands:
              - id: backend-verify
                argv: [mvn, -B, verify]
                workingDirectory: backend
                timeoutSeconds: 900
              - id: image-build
                agentRunnable: false
                argv: [docker, build, .]
                workingDirectory: .
                timeoutSeconds: 900
            """,
        )

        val parsed = VerificationConfigParser.parse(repo)

        assertTrue(parsed.commands.single { it.id == "backend-verify" }.agentRunnable)
        assertEquals(false, parsed.commands.single { it.id == "image-build" }.agentRunnable)
    }

    @Test
    fun `missing unknown and unsafe config fail closed`() {
        assertThrows<VerificationConfigException> { VerificationConfigParser.parse(repo) }

        config("version: 2\ncommands: []")
        assertTrue(assertThrows<VerificationConfigException> { VerificationConfigParser.parse(repo) }.message!!.contains("Onbekende"))

        config(
            """
            version: 1
            commands:
              - id: duplicate
                argv: [mvn, verify]
                workingDirectory: ../outside
                timeoutSeconds: 60
            """,
        )
        assertThrows<VerificationConfigException> { VerificationConfigParser.parse(repo) }
    }

    @Test
    fun `duplicate ids empty argv and excessive timeout fail closed`() {
        config(
            """
            version: 1
            commands:
              - id: same
                argv: [git, status]
                workingDirectory: .
                timeoutSeconds: 60
              - id: same
                argv: []
                workingDirectory: .
                timeoutSeconds: 999999
            """,
        )
        assertThrows<VerificationConfigException> { VerificationConfigParser.parse(repo) }
    }

    @Test
    fun `working directory symlink cannot escape repository`() {
        val outside = repo.parent.resolve("outside-${repo.fileName}").also { it.createDirectories() }
        Files.createSymbolicLink(repo.resolve("outside-link"), outside)
        config(
            """
            version: 1
            commands:
              - id: escaped
                argv: [git, status]
                workingDirectory: outside-link
                timeoutSeconds: 60
            """,
        )

        assertTrue(
            assertThrows<VerificationConfigException> { VerificationConfigParser.parse(repo) }
                .message.orEmpty().contains("symlink buiten"),
        )
    }

    private fun config(contents: String) {
        repo.resolve(".factory").createDirectories()
        repo.resolve(VerificationConfigParser.CONFIG_PATH).writeText(contents.trimIndent())
    }
}
