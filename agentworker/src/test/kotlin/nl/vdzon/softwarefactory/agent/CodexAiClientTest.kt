package nl.vdzon.softwarefactory.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.agent.ai.codex.CodexAiClient
import nl.vdzon.softwarefactory.agent.ai.codex.CodexCommandRunner
import nl.vdzon.softwarefactory.core.AgentRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class CodexAiClientTest {
    private val objectMapper = jacksonObjectMapper()

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `builds codex exec command with json sandbox and model flags`() {
        val client = CodexAiClient(emptyMap(), FakeCodexRunner())
        val lastMessageFile = tempDir.resolve(".codex-last-message.txt")
        val command = client.command(
            AgentContext(
                ticketKey = "SP-3",
                role = AgentRole.REVIEWER,
                taskMarkdown = "task",
                forcedOutcome = null,
                repoRoot = tempDir,
                model = "gpt-5-codex",
                effort = "deep",
            ),
            lastMessageFile,
        )

        assertEquals("codex", command[0])
        assertEquals("exec", command[1])
        assertTrue(command.contains("--json"))
        assertTrue(command.windowed(2).any { it == listOf("--sandbox", "danger-full-access") })
        assertTrue(command.contains("--skip-git-repo-check"))
        assertTrue(command.windowed(2).any { it == listOf("--model", "gpt-5-codex") })
        assertTrue(
            command.windowed(2).any {
                it == listOf("--output-last-message", lastMessageFile.toAbsolutePath().toString())
            },
        )
    }

    @Test
    fun `parses last message usage events and reviewer phase`() {
        val home = codexHome()
        val turnCompleted = objectMapper.writeValueAsString(
            mapOf(
                "type" to "turn.completed",
                "usage" to mapOf(
                    "input_tokens" to 10,
                    "output_tokens" to 20,
                    "cached_input_tokens" to 30,
                ),
            ),
        )
        val runner = FakeCodexRunner(
            lines = listOf(
                objectMapper.writeValueAsString(mapOf("type" to "item.started", "text" to "SF_GITHUB_TOKEN=secret-value")),
                turnCompleted,
            ),
            lastMessage = """
                Samenvatting:
                Alles ziet er goed uit.
                {"phase":"reviewed-ok"}
            """.trimIndent(),
        )

        val outcome = CodexAiClient(emptyMap(), runner, credentialHomes = listOf(home)).run(
            AgentContext("SP-3", AgentRole.REVIEWER, "task", null, tempDir),
        )

        assertEquals("reviewed", outcome.phase)
        assertEquals("reviewed", outcome.outcome)
        assertEquals(10, outcome.usage.inputTokens)
        assertEquals(20, outcome.usage.outputTokens)
        assertEquals(30, outcome.usage.cacheReadInputTokens)
        assertTrue(outcome.events.any { it.kind == "codex-turn.completed" })
        assertFalse(outcome.events.joinToString("\n") { it.payload }.contains("secret-value"))
        assertTrue(runner.command.single().contains("codex"))
    }

    @Test
    fun `developer run reports developed phase`() {
        val home = codexHome()
        val runner = FakeCodexRunner(
            lines = listOf(objectMapper.writeValueAsString(mapOf("type" to "turn.completed", "usage" to emptyMap<String, Int>()))),
            lastMessage = "Samenvatting: klaar.\nGedaan: implementatie.\nNiet gedaan / aangepast: niks.",
        )

        val outcome = CodexAiClient(emptyMap(), runner, credentialHomes = listOf(home)).run(
            AgentContext("SP-3", AgentRole.DEVELOPER, "task", null, tempDir),
        )

        assertEquals("developed", outcome.phase)
        assertEquals("developed", outcome.outcome)
    }

    @Test
    fun `missing codex credentials fails before starting command`() {
        val runner = FakeCodexRunner()
        val outcome = CodexAiClient(emptyMap(), runner, credentialHomes = listOf(tempDir.resolve("empty-home"))).run(
            AgentContext("SP-3", AgentRole.REFINER, "task", null, tempDir),
        )

        assertEquals(null, outcome.phase)
        assertEquals("error-codex-credentials", outcome.outcome)
        assertEquals(1, outcome.exitCode)
        assertTrue(runner.command.isEmpty())
    }

    private fun codexHome(): Path {
        val home = tempDir.resolve("home")
        Files.createDirectories(home.resolve(".codex"))
        home.resolve(".codex").resolve("auth.json").writeText("{}")
        return home
    }

    private class FakeCodexRunner(
        private val lines: List<String> = emptyList(),
        private val lastMessage: String? = null,
        private val exitCode: Int = 0,
    ) : CodexCommandRunner {
        val command = mutableListOf<List<String>>()

        override fun run(command: List<String>, cwd: Path, env: Map<String, String>, onLine: (String) -> Unit): Int {
            this.command += command
            if (lastMessage != null) {
                val index = command.indexOf("--output-last-message")
                if (index >= 0 && index + 1 < command.size) {
                    Path.of(command[index + 1]).writeText(lastMessage)
                }
            }
            lines.forEach(onLine)
            return exitCode
        }
    }
}
