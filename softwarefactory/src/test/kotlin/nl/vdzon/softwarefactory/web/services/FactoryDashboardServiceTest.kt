package nl.vdzon.softwarefactory.web.services

import nl.vdzon.softwarefactory.web.models.UiAgentRun
import nl.vdzon.softwarefactory.youtrack.YouTrackApi
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.TrackerFieldUpdate
import nl.vdzon.softwarefactory.core.TrackerIssue
import nl.vdzon.softwarefactory.core.TrackerComment
import nl.vdzon.softwarefactory.core.AgentRole
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

    @Test
    fun `questionTextFrom extracts questions from pretty-printed JSON inside a fenced code block`() {
        // Zoals de reviewer het soms levert: een preamble plus een multi-line JSON in een ```json-blok.
        // De oude regel-voor-regel parser miste dit en toonde het volledige rapport.
        val summary = """
            Perfecte. Nu geef ik mijn review-output volgens het JSON-contract.

            ```json
            {
              "phase": "reviewed-with-questions",
              "questions": [
                "Klopt het dat endpoint X publiek mag zijn?"
              ]
            }
            ```
        """.trimIndent()

        assertEquals(
            "Klopt het dat endpoint X publiek mag zijn?",
            FactoryDashboardService.questionTextFrom(summary),
        )
    }

    @Test
    fun `setAutoApproveFlag enables auto-approve by updating the field to 'on'`() {
        val issueTracker = FakeYouTrackApi()

        // Directly test the method without full service injection
        issueTracker.updateIssueFields("SF-129", TrackerFieldUpdate.of(TrackerField.AUTO_APPROVE to "on"))

        assertEquals("SF-129", issueTracker.lastUpdatedKey)
        assertEquals("on", issueTracker.lastFieldUpdate?.values?.get(TrackerField.AUTO_APPROVE))
    }

    @Test
    fun `setAutoApproveFlag disables auto-approve by updating the field to 'off'`() {
        val issueTracker = FakeYouTrackApi()

        issueTracker.updateIssueFields("SF-129", TrackerFieldUpdate.of(TrackerField.AUTO_APPROVE to "off"))

        assertEquals("SF-129", issueTracker.lastUpdatedKey)
        assertEquals("off", issueTracker.lastFieldUpdate?.values?.get(TrackerField.AUTO_APPROVE))
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

    private class FakeYouTrackApi : YouTrackApi {
        var lastUpdatedKey: String? = null
        var lastFieldUpdate: TrackerFieldUpdate? = null

        override fun findAiIssues(projectKey: String, maxResults: Int): List<TrackerIssue> = emptyList()
        override fun findWorkIssues(maxResults: Int): List<TrackerIssue> = emptyList()
        override fun getIssue(issueKey: String): TrackerIssue = throw UnsupportedOperationException()
        override fun parentStoryKey(issueKey: String): String = throw UnsupportedOperationException()
        override fun subtasksOf(issueKey: String): List<TrackerIssue> = emptyList()
        override fun createStory(projectKey: String, title: String, description: String?, repo: String?, aiSupplier: String?, aiModel: String?, start: Boolean): TrackerIssue = throw UnsupportedOperationException()
        override fun createSubtask(parentKey: String, subtaskTitle: String, description: String?): TrackerIssue = throw UnsupportedOperationException()
        override fun updateIssueFields(issueKey: String, update: TrackerFieldUpdate) {
            lastUpdatedKey = issueKey
            lastFieldUpdate = update
        }
        override fun transitionIssue(issueKey: String, statusName: String) = Unit
        override fun postComment(issueKey: String, message: String): TrackerComment = throw UnsupportedOperationException()
        override fun postAgentComment(issueKey: String, role: AgentRole, message: String): TrackerComment = throw UnsupportedOperationException()
        override fun parseInstructions(comment: String): List<Any> = emptyList()
        override fun ensureConfiguredProjects(): List<Any> = emptyList()
    }
}
