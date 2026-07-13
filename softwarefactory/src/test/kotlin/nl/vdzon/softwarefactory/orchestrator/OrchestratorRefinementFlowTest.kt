package nl.vdzon.softwarefactory.orchestrator

import nl.vdzon.softwarefactory.core.contracts.AgentDispatchRequest
import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.contracts.CreditsPause
import nl.vdzon.softwarefactory.core.contracts.ErrorCategory
import nl.vdzon.softwarefactory.core.contracts.IssueProcessResult
import nl.vdzon.softwarefactory.core.contracts.TrackerComment
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.github.PullRequestComment
import nl.vdzon.softwarefactory.testsupport.FakeAgentRuntime
import nl.vdzon.softwarefactory.testsupport.FakeCostMonitor
import nl.vdzon.softwarefactory.testsupport.FakeCreditsPauseCoordinator
import nl.vdzon.softwarefactory.testsupport.FakeGitHubApi
import nl.vdzon.softwarefactory.testsupport.FakeTrackerApi
import nl.vdzon.softwarefactory.testsupport.InMemoryAgentRunRepository
import nl.vdzon.softwarefactory.testsupport.InMemoryProcessedCommentStore
import nl.vdzon.softwarefactory.testsupport.InMemoryStoryRunRepository
import nl.vdzon.softwarefactory.testsupport.OrchestratorTestHarness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Story-niveau refine-/plan-flow op het Story Phase-veld, promotie van de
 * proposed-description en het auto-approve/silent-gedrag van stories.
 *
 * Afgesplitst uit de voormalige OrchestratorServiceTest; wiring en fakes staan in
 * `nl.vdzon.softwarefactory.testsupport` ([OrchestratorTestHarness]).
 */
class OrchestratorRefinementFlowTest : OrchestratorTestHarness() {
    @Test
    fun `fase 2a story refine flow waits and dispatches on the Story Phase field`() {
        val issueTracker = FakeTrackerApi(
            listOf(
                issue("KAN-30", storyPhase = "refined-with-questions"),
                issue("KAN-31", storyPhase = "refined"),
                issue("KAN-33", storyPhase = "questions-answered"),
                issue("KAN-34", storyPhase = "refined-rejected"),
            ),
        )
        val runtime = FakeAgentRuntime(now)
        val service = service(issueTracker, runtime = runtime)

        val result = service.pollOnce()

        assertEquals(
            listOf(
                IssueProcessResult.Skipped("KAN-30", "waiting-for-user"),
                IssueProcessResult.Skipped("KAN-31", "waiting-for-approval"),
                IssueProcessResult.Dispatched("KAN-33", AgentRole.REFINER, "factory-KAN-33-refiner"),
                IssueProcessResult.Dispatched("KAN-34", AgentRole.REFINER, "factory-KAN-34-refiner"),
            ),
            result.issueResults,
        )
        // Re-dispatch (questions-answered / refined-rejected) zet de actieve status op het Story Phase-veld.
        assertEquals("refining", issueTracker.lastUpdate("KAN-33").values[TrackerField.STORY_PHASE])
        assertEquals("refining", issueTracker.lastUpdate("KAN-34").values[TrackerField.STORY_PHASE])
        assertEquals(listOf("refiner", "refiner"), runtime.dispatches.map { it.labels["role"] })
    }

    @Test
    fun `fase 2b story plan flow dispatches planner and is terminal on planning-approved`() {
        val issueTracker = FakeTrackerApi(
            listOf(
                issue("KAN-40", storyPhase = "refined-approved"),
                issue("KAN-41", storyPhase = "planned-with-questions"),
                issue("KAN-42", storyPhase = "planned"),
                issue("KAN-43", storyPhase = "planning-approved"),
                issue("KAN-44", storyPhase = "planning-rejected"),
                issue("KAN-45", storyPhase = "in-progress"),
            ),
        )
        val runtime = FakeAgentRuntime(now)
        val service = service(issueTracker, runtime = runtime)

        val result = service.pollOnce()

        assertEquals(
            listOf(
                IssueProcessResult.Dispatched("KAN-40", AgentRole.PLANNER, "factory-KAN-40-planner"),
                IssueProcessResult.Skipped("KAN-41", "waiting-for-user"),
                IssueProcessResult.Skipped("KAN-42", "waiting-for-approval"),
                IssueProcessResult.Skipped("KAN-43", "refinement-done"),
                IssueProcessResult.Dispatched("KAN-44", AgentRole.PLANNER, "factory-KAN-44-planner"),
                IssueProcessResult.Skipped("KAN-45", "development-in-progress"),
            ),
            result.issueResults,
        )
        // refined-approved / planning-rejected starten de planner op het Story Phase-veld.
        assertEquals("planning", issueTracker.lastUpdate("KAN-40").values[TrackerField.STORY_PHASE])
        assertEquals("planning", issueTracker.lastUpdate("KAN-44").values[TrackerField.STORY_PHASE])
        assertEquals(listOf("planner", "planner"), runtime.dispatches.map { it.labels["role"] })
    }

    @Test
    fun `refined-approved promotes refiner proposed-description into the story description`() {
        val refinerComment = TrackerComment(
            "refiner-1",
            null,
            "Factory",
            """
            [REFINER] Ik heb de docs gelezen.
            <!-- proposed-description:start -->
            ## Scope
            De afgesproken spec.
            <!-- proposed-description:end -->
            {"phase":"refined"}
            """.trimIndent(),
            null,
        )
        val issueTracker = FakeTrackerApi(
            listOf(
                issue(
                    "KAN-50",
                    storyPhase = "refined-approved",
                    description = "Originele ruwe aanvraag.",
                    comments = listOf(refinerComment),
                ),
            ),
        )
        val service = service(issueTracker)

        service.pollOnce()

        val promoted = issueTracker.descriptionUpdates.getValue("KAN-50")
        assertTrue(promoted.startsWith("<!-- refined-by-factory -->"), "Sentinel-marker ontbreekt: $promoted")
        assertTrue(promoted.contains("## Scope"))
        assertTrue(promoted.contains("De afgesproken spec."))
        assertFalse(promoted.contains("proposed-description:start"), "Markers moeten gestript zijn")
        assertFalse(promoted.contains("Ik heb de docs gelezen"), "Preambule hoort niet in description")
        assertFalse(promoted.contains("\"phase\""), "JSON-control-regel hoort niet in description")
        assertFalse(promoted.contains("## Oorspronkelijke aanvraag"), "Oorspronkelijke-aanvraag-blok hoort niet meer in description")
        assertFalse(promoted.contains("Originele ruwe aanvraag."), "Oorspronkelijke ruwe aanvraagtekst hoort niet meer in description")
    }

    @Test
    fun `refined-approved without a proposed-description block leaves the description untouched`() {
        val issueTracker = FakeTrackerApi(
            listOf(
                issue(
                    "KAN-51",
                    storyPhase = "refined-approved",
                    description = "Originele ruwe aanvraag.",
                    comments = listOf(TrackerComment("refiner-1", null, "Factory", "[REFINER] Geen blok hier.", null)),
                ),
            ),
        )
        val service = service(issueTracker)

        service.pollOnce()

        assertFalse(issueTracker.descriptionUpdates.containsKey("KAN-51"), "Description mag niet gewijzigd zijn")
    }

    @Test
    fun `auto-approve advances refined story to refined-approved`() {
        val issueTracker = FakeTrackerApi(listOf(issue("KAN-31", storyPhase = "refined", autoApprove = true)))

        val result = service(issueTracker).processIssue(issueTracker.getIssue("KAN-31"))

        assertEquals(IssueProcessResult.Recovered("KAN-31", "refined-approved"), result)
        assertEquals("refined-approved", issueTracker.lastUpdate("KAN-31").values[TrackerField.STORY_PHASE])
    }

    @Test
    fun `auto-approve advances planned story to planning-approved`() {
        val issueTracker = FakeTrackerApi(listOf(issue("KAN-42", storyPhase = "planned", autoApprove = true)))

        val result = service(issueTracker).processIssue(issueTracker.getIssue("KAN-42"))

        assertEquals(IssueProcessResult.Recovered("KAN-42", "planning-approved"), result)
        assertEquals("planning-approved", issueTracker.lastUpdate("KAN-42").values[TrackerField.STORY_PHASE])
    }

    @Test
    fun `auto-approve off keeps refined story waiting for approval`() {
        val issueTracker = FakeTrackerApi(listOf(issue("KAN-31", storyPhase = "refined")))

        val result = service(issueTracker).processIssue(issueTracker.getIssue("KAN-31"))

        assertEquals(IssueProcessResult.Skipped("KAN-31", "waiting-for-approval"), result)
    }

    // ── SF-335: silent — autonoom doorzetten, vragen → error, geen wachten ───────

    @Test
    fun `silent implies auto-approve advances refined story to refined-approved`() {
        val issueTracker = FakeTrackerApi(listOf(issue("KAN-31", storyPhase = "refined", silent = true)))

        val result = service(issueTracker).processIssue(issueTracker.getIssue("KAN-31"))

        assertEquals(IssueProcessResult.Recovered("KAN-31", "refined-approved"), result)
        assertEquals("refined-approved", issueTracker.lastUpdate("KAN-31").values[TrackerField.STORY_PHASE])
    }

    @Test
    fun `silent story with refiner questions goes to clarification error instead of waiting`() {
        val story = issue(
            "KAN-31",
            storyPhase = "refined-with-questions",
            silent = true,
            comments = listOf(TrackerComment("c-1", null, "Factory", "[REFINER] Welke kleur moet de knop hebben?", null)),
        )
        val issueTracker = FakeTrackerApi(listOf(story))

        val result = service(issueTracker).processIssue(story)

        assertTrue(result is IssueProcessResult.Errored, "verwacht Errored, was $result")
        val error = issueTracker.lastUpdate("KAN-31").values[TrackerField.ERROR] as String
        assertEquals(ErrorCategory.CLARIFICATION, ErrorCategory.of(error))
        assertTrue(error.contains("Welke kleur"), error)
    }

    @Test
    fun `non-silent story with refiner questions keeps waiting`() {
        val story = issue("KAN-31", storyPhase = "refined-with-questions")
        val issueTracker = FakeTrackerApi(listOf(story))

        val result = service(issueTracker).processIssue(story)

        assertEquals(IssueProcessResult.Skipped("KAN-31", "waiting-for-user"), result)
    }
}
