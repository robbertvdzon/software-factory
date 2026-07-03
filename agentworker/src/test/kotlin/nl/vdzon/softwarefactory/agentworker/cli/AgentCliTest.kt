package nl.vdzon.softwarefactory.agentworker.cli

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import nl.vdzon.softwarefactory.contract.AgentResultFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Tests voor de hoofdloop van de agent-CLI ([runAgent]) met de mock-supplier (DummyAiClient),
 * zonder echte repo of container. Het contract: de loop schrijft ALTIJD een result-file
 * ([AgentResultFile], het gedeelde wire-contract uit factory-common) — ook bij fouten — en
 * de exit-code volgt de outcome.
 */
class AgentCliTest {

    @TempDir
    lateinit var tempDir: Path

    private val mapper = jacksonObjectMapper()

    private fun env(vararg extra: Pair<String, String>): Map<String, String> =
        mapOf(
            "SF_TICKET_KEY" to "KAN-1",
            "SF_AGENT_TYPE" to "refiner",
            "SF_AI_SUPPLIER" to "mock",
            "SF_CONTAINER_NAME" to "factory-kan-1-refiner",
            "SF_AGENT_RESULT_FILE" to tempDir.resolve("agent-result.json").toString(),
            // Geen SF_REPO_URL: er is dus geen repository-sessie nodig; het repo-pad bestaat bewust niet.
            "SF_REPO_ROOT" to tempDir.resolve("bestaat-niet").toString(),
            "SF_DUMMY_SKIP_SLEEP" to "true",
        ) + extra

    private fun readResult(): AgentResultFile =
        mapper.readValue(tempDir.resolve("agent-result.json").readText())

    @Test
    fun `happy path schrijft een result-file met de contract-velden en exit 0`() {
        val exitCode = runAgent(env("SF_DUMMY_FORCE_OUTCOME" to "ok"))

        assertEquals(0, exitCode)
        val result = readResult()
        assertEquals("KAN-1", result.storyKey)
        assertEquals("refiner", result.role)
        assertEquals("factory-kan-1-refiner", result.containerName)
        assertEquals("refined", result.phase)
        assertEquals("ok", result.outcome)
        assertEquals("(dummy) refinement OK", result.summaryText)
        assertEquals(0, result.exitCode)
        // Mock-runs rapporteren gesimuleerde usage; de velden moeten gevuld zijn.
        assertTrue(result.inputTokens > 0, "inputTokens hoort gevuld te zijn")
        assertTrue(result.events.isNotEmpty(), "er hoort minstens een outcome-event te zijn")
    }

    @Test
    fun `geforceerde agent-fout eindigt in een result-file met error-outcome in plaats van een crash`() {
        val exitCode = runAgent(env("SF_DUMMY_FORCE_OUTCOME" to "error"))

        assertEquals(1, exitCode)
        val result = readResult()
        assertEquals("error", result.outcome)
        assertEquals(1, result.exitCode)
        assertNotNull(result.summaryText)
    }

    @Test
    fun `setup-fout (repo niet gemount) eindigt in een result-file met error-outcome`() {
        // SF_REPO_URL is gezet maar het repo-pad bestaat niet -> TargetRepositoryPreparer gooit.
        val exitCode = runAgent(
            env(
                "SF_REPO_URL" to "git@github.com:robbertvdzon/demo.git",
            ),
        )

        assertEquals(1, exitCode)
        val result = readResult()
        assertEquals("error", result.outcome)
        assertTrue(
            result.summaryText.orEmpty().contains("setup faalde"),
            "summary hoort de setup-fout te benoemen: ${result.summaryText}",
        )
    }

    @Test
    fun `planner-run neemt de gedeclareerde subtaken op in de result-file`() {
        val exitCode = runAgent(
            env(
                "SF_AGENT_TYPE" to "planner",
                "SF_DUMMY_FORCE_OUTCOME" to "ok",
            ),
        )

        assertEquals(0, exitCode)
        val result = readResult()
        assertEquals("planner", result.role)
        assertEquals("planned", result.phase)
        assertEquals(
            listOf("development", "review", "test", "summary"),
            result.subtasks.map { it.type },
        )
    }

    @Test
    fun `ontbrekende verplichte env-var faalt hard voordat er een result-file kan bestaan`() {
        // Zonder SF_TICKET_KEY weet de agent niet eens voor welke story hij draait;
        // dit is de enige situatie waarin er geen result-file geschreven wordt.
        assertThrows<IllegalArgumentException> {
            runAgent(mapOf("SF_AGENT_TYPE" to "refiner"))
        }
        assertTrue(!tempDir.resolve("agent-result.json").exists())
    }
}
