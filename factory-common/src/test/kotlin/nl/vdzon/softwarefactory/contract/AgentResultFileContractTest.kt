package nl.vdzon.softwarefactory.contract

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Contract-test voor het wire-formaat van `/work/agent-result.json` ([AgentResultFile]).
 *
 * Schrijver: agentworker `AgentCli`; lezer: factory `AgentResultFileCompletionPoller`.
 * De letterlijke JSON-payload hieronder is representatief voor wat de agentworker
 * vandaag schrijft. Elke wijziging aan [AgentResultFile] die dit formaat breekt
 * (hernoemd veld, verplicht nieuw veld) breekt deze test — en zou in productie een
 * lopende oude agent-container breken. Nieuwe velden alleen toevoegen met default.
 */
class AgentResultFileContractTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `round-trip met alle velden gevuld blijft identiek`() {
        val original = AgentResultFile(
            storyKey = "SF-123",
            role = "developer",
            containerName = "factory-sf-123-developer",
            phase = "developed",
            outcome = "developed",
            summaryText = "Implemented the feature; branch story/SF-123",
            exitCode = 0,
            inputTokens = 1200,
            outputTokens = 340,
            cacheReadInputTokens = 5600,
            cacheCreationInputTokens = 780,
            numTurns = 14,
            durationMs = 92000,
            costUsdEst = 1.23,
            events = listOf(
                AgentResultEvent(kind = "claude-outcome", payload = "Implemented the feature"),
                AgentResultEvent(kind = "github-pr", payload = """{"prNumber":42}"""),
            ),
            knowledgeUpdates = listOf(
                AgentResultKnowledgeUpdate(category = "build", key = "mvn", content = "Use mvn test."),
            ),
            subtasks = listOf(
                AgentResultSubtask(
                    type = "development",
                    title = "Development subtask",
                    description = "Implement it",
                    model = "claude-opus-4-8",
                    effort = "high",
                ),
            ),
        )

        val json = objectMapper.writeValueAsString(original)
        val parsed = objectMapper.readValue<AgentResultFile>(json)

        assertEquals(original, parsed)
    }

    @Test
    fun `representatieve oude agentworker-payload blijft leesbaar`() {
        // Letterlijke payload zoals de agentworker-CLI die vandaag schrijft: alle velden
        // aanwezig, in deze veldnamen. NIET aanpassen aan model-wijzigingen — als deze
        // test breekt, is het wire-formaat incompatibel geworden met oude result-files.
        val legacyJson = """
            {
              "storyKey": "SF-123",
              "role": "developer",
              "containerName": "factory-sf-123-developer",
              "phase": "developed",
              "outcome": "developed",
              "summaryText": "Implemented the feature; branch story/SF-123",
              "exitCode": 0,
              "inputTokens": 1200,
              "outputTokens": 340,
              "cacheReadInputTokens": 5600,
              "cacheCreationInputTokens": 780,
              "numTurns": 14,
              "durationMs": 92000,
              "costUsdEst": 1.23,
              "events": [
                {"kind": "claude-outcome", "payload": "Implemented the feature"},
                {"kind": "github-pr", "payload": "{\"prNumber\":42}"}
              ],
              "knowledgeUpdates": [
                {"category": "build", "key": "mvn", "content": "Use mvn test."}
              ],
              "subtasks": [
                {
                  "type": "development",
                  "title": "Development subtask",
                  "description": "Implement it",
                  "model": "claude-opus-4-8",
                  "effort": "high"
                }
              ]
            }
        """.trimIndent()

        val parsed = objectMapper.readValue<AgentResultFile>(legacyJson)

        assertEquals("SF-123", parsed.storyKey)
        assertEquals("developer", parsed.role)
        assertEquals("factory-sf-123-developer", parsed.containerName)
        assertEquals("developed", parsed.phase)
        assertEquals("developed", parsed.outcome)
        assertEquals("Implemented the feature; branch story/SF-123", parsed.summaryText)
        assertEquals(0, parsed.exitCode)
        assertEquals(1200, parsed.inputTokens)
        assertEquals(340, parsed.outputTokens)
        assertEquals(5600, parsed.cacheReadInputTokens)
        assertEquals(780, parsed.cacheCreationInputTokens)
        assertEquals(14, parsed.numTurns)
        assertEquals(92000, parsed.durationMs)
        assertEquals(1.23, parsed.costUsdEst)
        assertEquals(
            listOf(
                AgentResultEvent(kind = "claude-outcome", payload = "Implemented the feature"),
                AgentResultEvent(kind = "github-pr", payload = """{"prNumber":42}"""),
            ),
            parsed.events,
        )
        assertEquals(
            listOf(AgentResultKnowledgeUpdate(category = "build", key = "mvn", content = "Use mvn test.")),
            parsed.knowledgeUpdates,
        )
        assertEquals(
            listOf(
                AgentResultSubtask(
                    type = "development",
                    title = "Development subtask",
                    description = "Implement it",
                    model = "claude-opus-4-8",
                    effort = "high",
                ),
            ),
            parsed.subtasks,
        )
    }

    @Test
    fun `minimale payload valt terug op defaults`() {
        // Een resultaat-bestand met alleen de verplichte velden (zoals fakes/oude schrijvers
        // kunnen produceren) moet leesbaar blijven met de gedocumenteerde defaults.
        val minimalJson = """
            {
              "storyKey": "SF-1",
              "role": "tester",
              "containerName": "factory-sf-1-tester",
              "outcome": "ok"
            }
        """.trimIndent()

        val parsed = objectMapper.readValue<AgentResultFile>(minimalJson)

        assertEquals(null, parsed.phase)
        assertEquals(null, parsed.summaryText)
        assertEquals(0, parsed.exitCode)
        assertEquals(0, parsed.inputTokens)
        assertEquals(emptyList(), parsed.events)
        assertEquals(emptyList(), parsed.knowledgeUpdates)
        assertEquals(emptyList(), parsed.subtasks)
    }

    @Test
    fun `onbekende velden van een nieuwere schrijver worden genegeerd`() {
        // Rolling upgrade andersom: een nieuwere agentworker mag velden toevoegen zonder
        // dat een oudere factory-lezer erop struikelt.
        val jsonWithExtraField = """
            {
              "storyKey": "SF-1",
              "role": "tester",
              "containerName": "factory-sf-1-tester",
              "outcome": "ok",
              "someFutureField": "ignored",
              "events": [{"kind": "x", "payload": "y", "futureEventField": 1}]
            }
        """.trimIndent()

        val parsed = objectMapper.readValue<AgentResultFile>(jsonWithExtraField)

        assertEquals("SF-1", parsed.storyKey)
        assertEquals(AgentResultEvent(kind = "x", payload = "y"), parsed.events.single())
    }
}
