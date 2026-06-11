package nl.vdzon.softwarefactory.web.services

import nl.vdzon.softwarefactory.web.models.UiAgentRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class FactoryDashboardServiceTest {

    @Test
    fun `latestAgentQuestions takes the most recent run with a non-blank summary, not just the last run`() {
        // De vraag-tester (eerder) stelde de vraag; een latere lege/half-afgeronde run (recovery-churn)
        // mag die vraag niet verbergen.
        val question = run(subtaskKey = "SF-8", startedAt = at(1), summaryText = "Mag ik de acceptatiecriteria bevestigen?")
        val laterBlank = run(subtaskKey = "SF-8", startedAt = at(2), summaryText = null)

        val result = FactoryDashboardService.latestAgentQuestions(listOf(question, laterBlank), fallbackKey = "SF-1")

        assertEquals(mapOf("SF-8" to "Mag ik de acceptatiecriteria bevestigen?"), result)
    }

    @Test
    fun `latestAgentQuestions prefers the newest non-blank summary and groups story-level runs under the story key`() {
        val older = run(subtaskKey = null, startedAt = at(1), summaryText = "oude vraag")
        val newer = run(subtaskKey = null, startedAt = at(3), summaryText = "nieuwe vraag")

        val result = FactoryDashboardService.latestAgentQuestions(listOf(older, newer), fallbackKey = "SF-1")

        assertEquals(mapOf("SF-1" to "nieuwe vraag"), result)
    }

    @Test
    fun `latestAgentQuestions drops a key when no run has a non-blank summary`() {
        val blank = run(subtaskKey = "SF-9", startedAt = at(1), summaryText = "   ")
        val nullSummary = run(subtaskKey = "SF-9", startedAt = at(2), summaryText = null)

        val result = FactoryDashboardService.latestAgentQuestions(listOf(blank, nullSummary), fallbackKey = "SF-1")

        assertEquals(emptyMap<String, String>(), result)
    }

    @Test
    fun `questionTextFrom extracts only the questions from the control JSON, not the whole report`() {
        val summary = """
            Ik heb het worklog gelezen. Hier is de eindsamenvatting voor de PO.

            ## SF-1 — Eindsamenvatting
            Een heleboel rapport-tekst die de gebruiker NIET als "de vraag" wil zien.

            {"agent_tips_update":[]}
            {"phase":"summary-with-questions","questions":["Heeft de CI een Docker-daemon beschikbaar?","Hoe moet de PR-strategie eruitzien?"]}
        """.trimIndent()

        assertEquals(
            "1. Heeft de CI een Docker-daemon beschikbaar?\n\n2. Hoe moet de PR-strategie eruitzien?",
            FactoryDashboardService.questionTextFrom(summary),
        )
    }

    @Test
    fun `questionTextFrom returns a single question without numbering`() {
        val summary = """
            Korte toelichting.
            {"phase":"refined-with-questions","questions":["Kun je de acceptatiecriteria bevestigen?"]}
        """.trimIndent()

        assertEquals("Kun je de acceptatiecriteria bevestigen?", FactoryDashboardService.questionTextFrom(summary))
    }

    @Test
    fun `questionTextFrom falls back to the full summary when there is no questions JSON`() {
        val summary = "Gewoon een samenvatting zonder vragen-control-JSON."

        assertEquals(summary, FactoryDashboardService.questionTextFrom(summary))
    }

    private fun at(seconds: Long): OffsetDateTime =
        OffsetDateTime.parse("2026-06-11T10:00:00Z").plusSeconds(seconds)

    private fun run(subtaskKey: String?, startedAt: OffsetDateTime, summaryText: String?): UiAgentRun =
        UiAgentRun(
            id = 1,
            storyRunId = 1,
            storyKey = "SF-1",
            role = "tester",
            containerName = "c",
            model = null,
            effort = null,
            level = null,
            startedAt = startedAt,
            endedAt = null,
            outcome = null,
            inputTokens = 0,
            outputTokens = 0,
            cacheReadInputTokens = 0,
            cacheCreationInputTokens = 0,
            numTurns = 0,
            durationMs = 0,
            costUsdEst = 0.0,
            summaryText = summaryText,
            workspacePath = null,
            subtaskKey = subtaskKey,
        )
}
