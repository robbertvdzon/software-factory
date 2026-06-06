package nl.vdzon.softwarefactory.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.agent.ai.copilot.CopilotAiClient
import nl.vdzon.softwarefactory.agent.ai.copilot.CopilotCommandRunner
import nl.vdzon.softwarefactory.youtrack.AgentRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class CopilotAiClientTest {
    private val objectMapper = jacksonObjectMapper()

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `builds copilot command with prompt json and autonomous flags`() {
        val client = CopilotAiClient(mapOf("SF_COPILOT_TOKEN" to "tok"), FakeCopilotRunner())
        val command = client.command(
            AgentContext(
                ticketKey = "SP-3",
                role = AgentRole.REVIEWER,
                taskMarkdown = "task",
                forcedOutcome = null,
                repoRoot = tempDir,
                model = "gpt-5",
                effort = "medium",
            ),
        )

        assertEquals("copilot", command.first())
        assertTrue(command.contains("--prompt"))
        assertTrue(command.contains("--allow-all-tools"))
        assertTrue(command.contains("--allow-all-paths"))
        assertTrue(command.contains("--no-ask-user"))
        assertTrue(command.contains("--no-remote"))
        assertTrue(command.windowed(2).any { it == listOf("--output-format", "json") })
        assertTrue(command.windowed(2).any { it == listOf("--model", "gpt-5") })
        assertTrue(command.windowed(2).any { it == listOf("--effort", "medium") })
    }

    @Test
    fun `does not pass effort for copilot claude models because copilot rejects it`() {
        val client = CopilotAiClient(mapOf("SF_COPILOT_TOKEN" to "tok"), FakeCopilotRunner())
        listOf("claude-haiku-4.5", "claude-sonnet-4.5", "claude-opus-4.5").forEach { model ->
            val command = client.command(
                AgentContext(
                    ticketKey = "SP-3",
                    role = AgentRole.TESTER,
                    taskMarkdown = "task",
                    forcedOutcome = null,
                    repoRoot = tempDir,
                    model = model,
                    effort = "medium",
                ),
            )

            assertTrue(command.windowed(2).any { it == listOf("--model", model) }, "model $model should be passed")
            assertFalse(command.contains("--effort"), "model $model must not receive --effort")
        }
    }

    @Test
    fun `does not pass effort for gpt 4 1 because copilot rejects it`() {
        val client = CopilotAiClient(mapOf("SF_COPILOT_TOKEN" to "tok"), FakeCopilotRunner())
        val command = client.command(
            AgentContext(
                ticketKey = "SP-3",
                role = AgentRole.SUMMARIZER,
                taskMarkdown = "task",
                forcedOutcome = null,
                repoRoot = tempDir,
                model = "gpt-4.1",
                effort = "low",
            ),
        )

        assertTrue(command.windowed(2).any { it == listOf("--model", "gpt-4.1") })
        assertFalse(command.contains("--effort"))
    }

    @Test
    fun `parses assistant message usage events and reviewer phase`() {
        val assistantMessage = objectMapper.writeValueAsString(
            mapOf(
                "type" to "assistant.message",
                "data" to mapOf(
                    "content" to "Samenvatting:\nAlles ziet er goed uit.\n{\"phase\":\"reviewed-ok\"}",
                    "outputTokens" to 12,
                ),
            ),
        )
        val resultLine = objectMapper.writeValueAsString(
            mapOf(
                "type" to "result",
                "usage" to mapOf("sessionDurationMs" to 7771, "premiumRequests" to 0),
            ),
        )
        val runner = FakeCopilotRunner(
            lines = listOf(
                objectMapper.writeValueAsString(mapOf("type" to "user.message", "text" to "SF_GITHUB_TOKEN=secret-value")),
                assistantMessage,
                resultLine,
            ),
        )

        val outcome = CopilotAiClient(mapOf("SF_COPILOT_TOKEN" to "tok"), runner).run(
            AgentContext("SP-3", AgentRole.REVIEWER, "task", null, tempDir),
        )

        assertEquals("review-finished", outcome.phase)
        assertEquals("review-finished", outcome.outcome)
        assertEquals(12, outcome.usage.outputTokens)
        assertEquals(7771, outcome.usage.durationMs)
        assertTrue(outcome.events.any { it.kind == "copilot-assistant.message" })
        assertFalse(outcome.events.joinToString("\n") { it.payload }.contains("secret-value"))
        assertTrue(runner.command.single().contains("copilot"))
    }

    @Test
    fun `developer run reports developed phase`() {
        val runner = FakeCopilotRunner(
            lines = listOf(
                objectMapper.writeValueAsString(
                    mapOf(
                        "type" to "assistant.message",
                        "data" to mapOf("content" to "Samenvatting: klaar.\nGedaan: implementatie.", "outputTokens" to 5),
                    ),
                ),
            ),
        )

        val outcome = CopilotAiClient(mapOf("SF_COPILOT_TOKEN" to "tok"), runner).run(
            AgentContext("SP-3", AgentRole.DEVELOPER, "task", null, tempDir),
        )

        assertEquals("developed", outcome.phase)
        assertEquals("developed", outcome.outcome)
    }

    @Test
    fun `missing copilot token fails before starting command`() {
        val runner = FakeCopilotRunner()
        val outcome = CopilotAiClient(emptyMap(), runner, credentialHomes = listOf(tempDir.resolve("empty-home"))).run(
            AgentContext("SP-3", AgentRole.REFINER, "task", null, tempDir),
        )

        assertEquals(null, outcome.phase)
        assertEquals("error-copilot-credentials", outcome.outcome)
        assertEquals(1, outcome.exitCode)
        assertTrue(runner.command.isEmpty())
    }

    @Test
    fun `uses mounted copilot config credentials without token env`() {
        val home = tempDir.resolve("home")
        home.resolve(".copilot").createDirectories()
        home.resolve(".copilot").resolve("config.json").writeText("""{"token":"stored"}""")
        val runner = FakeCopilotRunner(
            lines = listOf(
                objectMapper.writeValueAsString(
                    mapOf(
                        "type" to "assistant.message",
                        "data" to mapOf("content" to "Samenvatting: klaar.\nGedaan: implementatie."),
                    ),
                ),
            ),
        )

        val outcome = CopilotAiClient(emptyMap(), runner, credentialHomes = listOf(home)).run(
            AgentContext("SP-3", AgentRole.DEVELOPER, "task", null, tempDir),
        )

        assertEquals("developed", outcome.phase)
        assertTrue(runner.command.single().contains("copilot"))
    }

    @Test
    fun `uses gh token as copilot github token`() {
        val runner = FakeCopilotRunner(
            lines = listOf(
                objectMapper.writeValueAsString(
                    mapOf(
                        "type" to "assistant.message",
                        "data" to mapOf("content" to "Samenvatting: klaar.\nGedaan: implementatie."),
                    ),
                ),
            ),
        )

        val outcome = CopilotAiClient(mapOf("GH_TOKEN" to "gho-token"), runner).run(
            AgentContext("SP-3", AgentRole.DEVELOPER, "task", null, tempDir),
        )

        assertEquals("developed", outcome.phase)
        assertEquals("gho-token", runner.envs.single()["COPILOT_GITHUB_TOKEN"])
    }

    @Test
    fun `cli failure includes raw error line in comment`() {
        val runner = FakeCopilotRunner(
            lines = listOf("""{"type":"session.started"}""", """Error: Model "claude-opus-4.5" from --model flag is not available."""),
            exitCode = 1,
        )

        val outcome = CopilotAiClient(mapOf("SF_COPILOT_TOKEN" to "tok"), runner).run(
            AgentContext("SP-3", AgentRole.SUMMARIZER, "task", null, tempDir),
        )

        assertEquals(null, outcome.phase)
        assertEquals("error-copilot-cli", outcome.outcome)
        assertTrue(outcome.comment.contains("Model \"claude-opus-4.5\""))
    }

    private class FakeCopilotRunner(
        private val lines: List<String> = emptyList(),
        private val exitCode: Int = 0,
    ) : CopilotCommandRunner {
        val command = mutableListOf<List<String>>()
        val envs = mutableListOf<Map<String, String>>()

        override fun run(command: List<String>, cwd: Path, env: Map<String, String>, onLine: (String) -> Unit): Int {
            this.command += command
            this.envs += env
            lines.forEach(onLine)
            return exitCode
        }
    }
}
