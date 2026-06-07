package nl.vdzon.softwarefactory.agent

import nl.vdzon.softwarefactory.agent.*
import nl.vdzon.softwarefactory.agent.ai.claude.*
import nl.vdzon.softwarefactory.agent.ai.codex.*
import nl.vdzon.softwarefactory.agent.ai.copilot.*
import nl.vdzon.softwarefactory.agent.ai.dummy.*
import nl.vdzon.softwarefactory.agent.ai.unsupported.*
import nl.vdzon.softwarefactory.agentworker.flows.*

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.softwarefactory.youtrack.AgentRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ClaudeCodeAiClientTest {
    private val objectMapper = jacksonObjectMapper()

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `supplier factory routes mock claude and future suppliers`() {
        assertInstanceOf(DummyAiClient::class.java, AiClientFactory.create(emptyMap()))
        assertInstanceOf(DummyAiClient::class.java, AiClientFactory.create(mapOf("SF_AI_SUPPLIER" to "mock")))
        assertInstanceOf(DummyAiClient::class.java, AiClientFactory.create(mapOf("SF_AI_SUPPLIER" to "dummy")))
        assertInstanceOf(DummyAiClient::class.java, AiClientFactory.create(mapOf("SF_AI_SUPPLIER" to "none")))
        assertInstanceOf(ClaudeCodeAiClient::class.java, AiClientFactory.create(mapOf("SF_AI_SUPPLIER" to "claude")))
        assertInstanceOf(CodexAiClient::class.java, AiClientFactory.create(mapOf("SF_AI_SUPPLIER" to "openai")))
        assertInstanceOf(CodexAiClient::class.java, AiClientFactory.create(mapOf("SF_AI_SUPPLIER" to "codex")))
        assertInstanceOf(CopilotAiClient::class.java, AiClientFactory.create(mapOf("SF_AI_SUPPLIER" to "copilot")))
        assertInstanceOf(CopilotAiClient::class.java, AiClientFactory.create(mapOf("SF_AI_SUPPLIER" to "github")))
        assertInstanceOf(NotImplementedAiClient::class.java, AiClientFactory.create(mapOf("SF_AI_SUPPLIER" to "microsoft")))
    }

    @Test
    fun `builds claude command with model and stream json flags`() {
        val client = ClaudeCodeAiClient(mapOf("SF_AI_OAUTH_TOKEN" to "token"), FakeClaudeRunner())
        val command = client.command(
            AgentContext(
                ticketKey = "SP-3",
                role = AgentRole.REVIEWER,
                taskMarkdown = "task",
                forcedOutcome = null,
                repoRoot = tempDir,
                model = "claude-sonnet-test",
                effort = "deep",
            ),
        )

        assertEquals("claude", command.first())
        assertTrue(command.windowed(2).any { it == listOf("--model", "claude-sonnet-test") })
        assertTrue(command.windowed(2).any { it == listOf("--effort", "deep") })
        assertTrue(command.windowed(2).any { it == listOf("--output-format", "stream-json") })
        assertTrue(command.windowed(2).any { it == listOf("--permission-mode", "bypassPermissions") })
        assertTrue(command.contains("--append-system-prompt"))
        assertTrue(command.contains("--print"))
    }

    @Test
    fun `developer prompt forbids local commits pushes and pr actions`() {
        val client = ClaudeCodeAiClient(mapOf("SF_AI_OAUTH_TOKEN" to "token"), FakeClaudeRunner())
        val command = client.command(
            AgentContext(
                ticketKey = "SP-3",
                role = AgentRole.DEVELOPER,
                taskMarkdown = "task",
                forcedOutcome = null,
                repoRoot = tempDir,
            ),
        )
        val prompt = command.joinToString("\n")

        assertTrue(prompt.contains("Voer nooit git commit, git push, gh pr create/update/merge of andere PR-acties uit."))
        assertTrue(prompt.contains("Laat alle wijzigingen uncommitted in de working tree"))
        assertFalse(prompt.contains("Commit lokaal als dat lukt"))
    }

    @Test
    fun `parses stream result usage events and reviewer phase`() {
        val resultText = """
            Samenvatting:
            Alles ziet er goed uit.
            {"phase":"reviewed-ok"}
        """.trimIndent()
        val resultLine = objectMapper.writeValueAsString(
            mapOf(
                "type" to "result",
                "subtype" to "success",
                "result" to resultText,
                "usage" to mapOf(
                    "input_tokens" to 10,
                    "output_tokens" to 20,
                    "cache_read_input_tokens" to 30,
                    "cache_creation_input_tokens" to 40,
                ),
                "num_turns" to 3,
                "duration_ms" to 1234,
                "total_cost_usd" to 0.42,
            ),
        )
        val runner = FakeClaudeRunner(
            lines = listOf(
                objectMapper.writeValueAsString(mapOf("type" to "assistant", "text" to "SF_GITHUB_TOKEN=secret-value")),
                resultLine,
            ),
        )
        val outcome = ClaudeCodeAiClient(mapOf("SF_AI_OAUTH_TOKEN" to "token"), runner).run(
            AgentContext("SP-3", AgentRole.REVIEWER, "task", null, tempDir),
        )

        assertEquals("reviewed", outcome.phase)
        assertEquals("reviewed", outcome.outcome)
        assertEquals(10, outcome.usage.inputTokens)
        assertEquals(20, outcome.usage.outputTokens)
        assertEquals(30, outcome.usage.cacheReadInputTokens)
        assertEquals(40, outcome.usage.cacheCreationInputTokens)
        assertEquals(3, outcome.usage.numTurns)
        assertEquals(1234, outcome.usage.durationMs)
        assertEquals(0.42, outcome.usage.costUsdEst)
        assertTrue(outcome.events.any { it.kind == "claude-result" })
        assertFalse(outcome.events.joinToString("\n") { it.payload }.contains("secret-value"))
        assertTrue(runner.command.single().contains("claude"))
    }

    @Test
    fun `missing claude credentials fails before starting command`() {
        val runner = FakeClaudeRunner()
        val outcome = ClaudeCodeAiClient(emptyMap(), runner, credentialHomes = listOf(tempDir.resolve("empty-home"))).run(
            AgentContext("SP-3", AgentRole.REFINER, "task", null, tempDir),
        )

        assertEquals(null, outcome.phase)
        assertEquals("error-claude-credentials", outcome.outcome)
        assertEquals(1, outcome.exitCode)
        assertTrue(runner.command.isEmpty())
    }

    @Test
    fun `outcome parser maps old and current phase names`() {
        assertEquals(
            ClaudeDecision("refined"),
            ClaudeOutcomeParser.parse(AgentRole.REFINER, "```json\n{\"phase\":\"refined\",}\n```"),
        )
        assertEquals(
            ClaudeDecision("refined-with-questions"),
            ClaudeOutcomeParser.parse(AgentRole.REFINER, "{\"phase\":\"refined-with-questions-for-user\"}"),
        )
        assertEquals(
            ClaudeDecision("planned"),
            ClaudeOutcomeParser.parse(AgentRole.PLANNER, "{\"phase\":\"planned\"}"),
        )
        assertEquals(
            ClaudeDecision("planned-with-questions"),
            ClaudeOutcomeParser.parse(AgentRole.PLANNER, "{\"phase\":\"planned-with-questions\"}"),
        )
        assertEquals(
            ClaudeDecision("review-rejected"),
            ClaudeOutcomeParser.parse(AgentRole.REVIEWER, "phase: reviewed-changes"),
        )
        assertEquals(
            ClaudeDecision("tested"),
            ClaudeOutcomeParser.parse(AgentRole.TESTER, "Done\n{\"phase\":\"tested-successfully\"}"),
        )
        assertEquals(
            ClaudeDecision("summarized"),
            ClaudeOutcomeParser.parse(AgentRole.SUMMARIZER, "Eindrapport\n{\"phase\":\"summary-finished\"}"),
        )
    }

    @Test
    fun `planner output parses phase and declared subtasks`() {
        val decision = ClaudeOutcomeParser.parse(
            AgentRole.PLANNER,
            "Plan klaar.\n{\"phase\":\"planned\",\"subtasks\":[" +
                "{\"type\":\"development\",\"title\":\"Implementeer X\",\"description\":\"doe X\"}," +
                "{\"type\":\"summary\",\"title\":\"Eindsamenvatting\"}]}",
        )

        assertEquals("planned", decision?.phase)
        assertEquals(listOf("development", "summary"), decision?.subtasks?.map { it.type })
        assertEquals("Implementeer X", decision?.subtasks?.first()?.title)
        assertEquals("doe X", decision?.subtasks?.first()?.description)
    }

    @Test
    fun `extracts agent knowledge updates`() {
        val updates = ClaudeOutcomeParser.extractKnowledgeUpdates(
            """
            {"agent_tips_update":[{"category":"gotchas","key":"build","content":"Run mvn test first."}]}
            {"phase":"tested-ok"}
            """.trimIndent(),
        )

        assertEquals(listOf(AgentKnowledgeDraft("gotchas", "build", "Run mvn test first.")), updates)
    }

    private class FakeClaudeRunner(
        private val lines: List<String> = emptyList(),
        private val exitCode: Int = 0,
    ) : ClaudeCommandRunner {
        val command = mutableListOf<List<String>>()

        override fun run(command: List<String>, cwd: Path, env: Map<String, String>, onLine: (String) -> Unit): Int {
            this.command += command
            lines.forEach(onLine)
            return exitCode
        }
    }
}
