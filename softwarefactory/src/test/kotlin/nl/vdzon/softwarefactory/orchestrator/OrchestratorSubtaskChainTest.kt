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
 * Ketengedrag van subtaken: doorschakelen naar de volgende sibling, de
 * manual-approve-poort (SF-192) en de test-bevinding-keten-reset (SF-200).
 *
 * Afgesplitst uit de voormalige OrchestratorServiceTest; wiring en fakes staan in
 * `nl.vdzon.softwarefactory.testsupport` ([OrchestratorTestHarness]).
 */
class OrchestratorSubtaskChainTest : OrchestratorTestHarness() {
    @Test
    fun `terminal subtask chains to next not-done sibling`() {
        val s1 = issue("PF-7", type = "Task", subtaskType = "manual", subtaskPhase = "manual-action-done")
        val s2 = issue("PF-8", type = "Task", subtaskType = "development", subtaskPhase = null)
        val s3 = issue("PF-9", type = "Task", subtaskType = "development", subtaskPhase = "review-approved")
        val issueTracker = FakeTrackerApi(listOf(s1, s2, s3), parentKey = "PF-1", subtasks = listOf(s1, s2, s3))

        val result = service(issueTracker).processIssue(s1)

        assertEquals(IssueProcessResult.Chained("PF-7", "PF-8"), result)
        // De volgende subtaak wordt op fase `start` gezet (geen labels meer).
        assertEquals("start", issueTracker.lastUpdate("PF-8").values[TrackerField.SUBTASK_PHASE])
        assertEquals(emptyList<Pair<String, String>>(), issueTracker.addedTags)
        assertEquals(emptyList<Pair<String, String>>(), issueTracker.removedTags)
        // De afgeronde subtask gaat naar Done; de story nog niet (er volgt een subtask).
        assertEquals(listOf("PF-7" to "Done"), issueTracker.transitions)
    }

    @Test
    fun `last terminal subtask untags itself and chains to nothing`() {
        val only = issue("PF-9", type = "Task", subtaskType = "summary", subtaskPhase = "summary-approved")
        val parent = issue("PF-1", subtaskPhase = null)
        val issueTracker = FakeTrackerApi(listOf(only, parent), parentKey = "PF-1", subtasks = listOf(only))
        val storyRuns = InMemoryStoryRunRepository()
        val openRun = storyRuns.openOrCreate("PF-1", "git@example/repo.git")

        val result = service(issueTracker, storyRuns = storyRuns).processIssue(only)

        assertEquals(IssueProcessResult.Chained("PF-9", null), result)
        assertEquals(emptyList<Pair<String, String>>(), issueTracker.addedTags)
        assertEquals(emptyList<Pair<String, String>>(), issueTracker.removedTags)
        // Laatste subtask klaar → subtask Done én de hele story Done.
        assertEquals(listOf("PF-9" to "Done", "PF-1" to "Done"), issueTracker.transitions)
        // SF-817-bug: de nog-open story_run (bv. van de deploy-fase) moet nu ook echt sluiten,
        // anders blijft "ended" voor een voltooide story voor altijd leeg in het dashboard.
        assertEquals(listOf(openRun.id to "done"), storyRuns.closed)
    }

    @Test
    fun `terminal subtask does not restart an already-running next sibling`() {
        // Regressie: een terminale subtaak wordt elke poll opnieuw verwerkt (geen labels meer).
        // De al-lopende volgende subtaak mag NIET telkens terug op `start` worden gezet.
        val finished = issue("PF-7", type = "Task", subtaskType = "development", subtaskPhase = "review-approved")
        val running = issue("PF-8", type = "Task", subtaskType = "development", subtaskPhase = "developing")
        val issueTracker = FakeTrackerApi(
            listOf(finished, running),
            parentKey = "PF-1",
            subtasks = listOf(finished, running),
        )

        val result = service(issueTracker).processIssue(finished)

        assertEquals(IssueProcessResult.Chained("PF-7", "PF-8"), result)
        // PF-8 loopt al → geen fase-update (geen herstart-loop).
        assertFalse(issueTracker.updates.containsKey("PF-8"))
        // De afgeronde subtaak gaat wel naar Done; de story niet (er loopt nog een subtaak).
        assertEquals(listOf("PF-7" to "Done"), issueTracker.transitions)
    }

    @Test
    fun `terminal subtask that already has board status Done does not transitionIssue again`() {
        // SF-904: voorkomt dat een reeds afgeronde subtask zichzelf blijft opwekken via een
        // onvoorwaardelijke transitionIssue-write (bumpt updated_at zonder echte statuswijziging).
        val s1 = issue("PF-7", type = "Task", subtaskType = "manual", subtaskPhase = "manual-action-done").copy(status = "Done")
        val s2 = issue("PF-8", type = "Task", subtaskType = "development", subtaskPhase = null)
        val issueTracker = FakeTrackerApi(listOf(s1, s2), parentKey = "PF-1", subtasks = listOf(s1, s2))

        val result = service(issueTracker).processIssue(s1)

        assertEquals(IssueProcessResult.Chained("PF-7", "PF-8"), result)
        assertTrue(issueTracker.transitions.isEmpty(), "status is al Done, transitionIssue mag niet nogmaals aangeroepen worden")
        assertEquals("start", issueTracker.lastUpdate("PF-8").values[TrackerField.SUBTASK_PHASE])
    }

    @Test
    fun `last terminal subtask does not transitionIssue the parent when its board status is already Done`() {
        val only = issue("PF-9", type = "Task", subtaskType = "summary", subtaskPhase = "summary-approved")
        val parent = issue("PF-1", subtaskPhase = null).copy(status = "Done")
        val issueTracker = FakeTrackerApi(listOf(only, parent), parentKey = "PF-1", subtasks = listOf(only))
        val storyRuns = InMemoryStoryRunRepository()

        val result = service(issueTracker, storyRuns = storyRuns).processIssue(only)

        assertEquals(IssueProcessResult.Chained("PF-9", null), result)
        // De subtaak zelf gaat wél naar Done (haar eigen board-status was nog niet Done); de parent
        // niet nogmaals, want die staat al op Done.
        assertEquals(listOf("PF-9" to "Done"), issueTracker.transitions)
    }

    @Test
    fun `documentation-approved subtask chains to the next sibling`() {
        val doc = issue("PF-7", type = "Task", subtaskType = "documentation", subtaskPhase = "documentation-approved")
        val gate = issue("PF-8", type = "Task", subtaskType = "manual-approve", subtaskPhase = null)
        val issueTracker = FakeTrackerApi(listOf(doc, gate), parentKey = "PF-1", subtasks = listOf(doc, gate))

        val result = service(issueTracker).processIssue(doc)

        assertEquals(IssueProcessResult.Chained("PF-7", "PF-8"), result)
        assertEquals("start", issueTracker.lastUpdate("PF-8").values[TrackerField.SUBTASK_PHASE])
        assertEquals(listOf("PF-7" to "Done"), issueTracker.transitions)
    }

    // ---- SF-192: manual-approve-poort (coördinator-fase-overgangen + reset) ----

    @Test
    fun `manual-approve start moves the gate to manual-approve-needed`() {
        val gate = issue(key = "KAN-1-sub1", type = "Task", subtaskType = "manual-approve", subtaskPhase = "start")
        val issueTracker = FakeTrackerApi(listOf(gate), parentKey = "KAN-1", subtasks = listOf(gate))

        service(issueTracker).processIssue(gate)

        assertEquals(
            "manual-approve-needed",
            issueTracker.lastUpdate("KAN-1-sub1").values[TrackerField.SUBTASK_PHASE],
        )
    }

    @Test
    fun `manual-approve-needed waits for a human`() {
        val gate = issue(key = "KAN-1-sub1", type = "Task", subtaskType = "manual-approve", subtaskPhase = "manual-approve-needed")
        val issueTracker = FakeTrackerApi(listOf(gate), parentKey = "KAN-1", subtasks = listOf(gate))

        val result = service(issueTracker).processIssue(gate)

        assertTrue(result is IssueProcessResult.Skipped)
        assertTrue(issueTracker.updates["KAN-1-sub1"] == null)
    }

    @Test
    fun `manually-approved advances the chain to the next subtask`() {
        val gate = issue(key = "KAN-1-sub1", type = "Task", subtaskType = "manual-approve", subtaskPhase = "manually-approved")
        val merge = issue(key = "KAN-1-sub2", type = "Task", subtaskType = "merge", subtaskPhase = null)
        val issueTracker = FakeTrackerApi(listOf(gate, merge), parentKey = "KAN-1", subtasks = listOf(gate, merge))

        service(issueTracker).processIssue(gate)

        // De poort is afgerond → Done; de eerstvolgende subtaak (merge) gaat op `start`.
        assertTrue(issueTracker.transitions.contains("KAN-1-sub1" to "Done"))
        assertEquals("start", issueTracker.lastUpdate("KAN-1-sub2").values[TrackerField.SUBTASK_PHASE])
    }

    @Test
    fun `manually-not-approved resets every subtask to todo and restarts the chain`() {
        val dev = issue(key = "KAN-1-sub1", type = "Task", subtaskType = "development", subtaskPhase = "review-approved")
        val merge = issue(key = "KAN-1-sub2", type = "Task", subtaskType = "merge", subtaskPhase = null)
        val gate = issue(key = "KAN-1-sub3", type = "Task", subtaskType = "manual-approve", subtaskPhase = "manually-not-approved")
        val issueTracker = FakeTrackerApi(
            listOf(dev, merge, gate),
            parentKey = "KAN-1",
            subtasks = listOf(dev, merge, gate),
        )

        service(issueTracker).processIssue(gate)

        // Alle subtaken (incl. de poort zelf) zijn fase-leeggemaakt en naar de todo-lane gezet.
        listOf("KAN-1-sub1", "KAN-1-sub2", "KAN-1-sub3").forEach { key ->
            assertTrue(issueTracker.transitions.contains(key to "Open"), "$key moet naar de todo-lane")
            assertTrue(
                issueTracker.updates.getValue(key).any { it.values[TrackerField.SUBTASK_PHASE] == null },
                "$key moet fase-leeg gemaakt zijn",
            )
        }
        // De story zelf gaat ook terug naar todo en de eerste subtaak start opnieuw.
        assertTrue(issueTracker.transitions.contains("KAN-1" to "Open"))
        assertEquals("start", issueTracker.lastUpdate("KAN-1-sub1").values[TrackerField.SUBTASK_PHASE])
    }

    // ---- SF-200: test-bevinding reset de hele keten i.p.v. developer-loopback ----

    @Test
    fun `test-rejected resets the whole chain and writes the test reason to the story`() {
        val dev = issue(key = "SF-1-sub1", type = "Task", subtaskType = "development", subtaskPhase = "review-approved")
        val test = issue(
            key = "SF-1-sub2",
            type = "Task",
            subtaskType = "test",
            subtaskPhase = "test-rejected",
            description = "Story-omschrijving",
        )
        val parent = issue(key = "SF-1", description = "Story-omschrijving")
        val issueTracker = FakeTrackerApi(
            listOf(dev, test, parent),
            parentKey = "SF-1",
            subtasks = listOf(dev, test),
        )
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("SF-1", "git@example/repo.git")
        val agentRuns = InMemoryAgentRunRepository().apply {
            addEnded(storyRun.id, AgentRole.TESTER, outcome = "test-rejected", summary = "Knop doet niets bij klik.")
        }
        val runtime = FakeAgentRuntime(now)

        val result = service(issueTracker, runtime = runtime, storyRuns = storyRuns, agentRuns = agentRuns)
            .processIssue(test)

        // De tester start GEEN developer-fix meer.
        assertTrue(runtime.dispatches.isEmpty(), "test-bevinding mag geen agent dispatchen")
        assertTrue(result is IssueProcessResult.Chained, "verwacht een keten-reset, kreeg $result")
        // Alle subtaken (incl. de test-subtaak zelf) → fase-leeg + todo-lane; eerste subtaak op start.
        listOf("SF-1-sub1", "SF-1-sub2").forEach { key ->
            assertTrue(issueTracker.transitions.contains(key to "Open"), "$key moet naar de todo-lane")
        }
        assertTrue(issueTracker.transitions.contains("SF-1" to "Open"))
        assertEquals("start", issueTracker.lastUpdate("SF-1-sub1").values[TrackerField.SUBTASK_PHASE])
        // De testreden staat in een gemarkeerd blok in de story-description.
        val updatedDescription = issueTracker.descriptionUpdates.getValue("SF-1")
        assertTrue(updatedDescription.contains("<!-- test-feedback:start -->"))
        assertTrue(updatedDescription.contains("Knop doet niets bij klik."))
    }

    @Test
    fun `repeated test reason replaces the marker block instead of stacking`() {
        val test = issue(key = "SF-1-sub2", type = "Task", subtaskType = "test", subtaskPhase = "test-rejected")
        val parent = issue(
            key = "SF-1",
            description = "Story-omschrijving\n\n<!-- test-feedback:start -->\n## Test-feedback\nOude bevinding.\n<!-- test-feedback:end -->",
        )
        val issueTracker = FakeTrackerApi(
            listOf(test, parent),
            parentKey = "SF-1",
            subtasks = listOf(test),
        )
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("SF-1", "git@example/repo.git")
        val agentRuns = InMemoryAgentRunRepository().apply {
            addEnded(storyRun.id, AgentRole.TESTER, outcome = "test-rejected", summary = "Nieuwe bevinding.")
        }

        service(issueTracker, storyRuns = storyRuns, agentRuns = agentRuns).processIssue(test)

        val updatedDescription = issueTracker.descriptionUpdates.getValue("SF-1")
        assertTrue(updatedDescription.contains("Nieuwe bevinding."))
        assertFalse(updatedDescription.contains("Oude bevinding."), "vorige test-feedback mag vervangen zijn")
        // Geen gestapelde blokken.
        assertEquals(1, "<!-- test-feedback:start -->".toRegex().findAll(updatedDescription).count())
    }

    @Test
    fun `test reason falls back to a placeholder when the tester run has no summary`() {
        val test = issue(key = "SF-1-sub2", type = "Task", subtaskType = "test", subtaskPhase = "test-rejected")
        val parent = issue(key = "SF-1", description = "Story-omschrijving")
        val issueTracker = FakeTrackerApi(listOf(test, parent), parentKey = "SF-1", subtasks = listOf(test))
        val storyRuns = InMemoryStoryRunRepository()
        // Geen TESTER-run geseed -> geen reden beschikbaar.
        service(issueTracker, storyRuns = storyRuns).processIssue(test)

        assertTrue(issueTracker.descriptionUpdates.getValue("SF-1").contains("(geen reden opgegeven)"))
    }

    @Test
    fun `test-chain reset cap stops the chain with an error instead of resetting again`() {
        val test = issue(key = "SF-1-sub2", type = "Task", subtaskType = "test", subtaskPhase = "test-rejected")
        val parent = issue(key = "SF-1", description = "Story-omschrijving")
        val issueTracker = FakeTrackerApi(listOf(test, parent), parentKey = "SF-1", subtasks = listOf(test))
        val storyRuns = InMemoryStoryRunRepository()
        val storyRun = storyRuns.openOrCreate("SF-1", "git@example/repo.git")
        val agentRuns = InMemoryAgentRunRepository().apply {
            // Default-cap = 3 -> blokkeren vanaf de 4e TESTER-run.
            repeat(4) { addEnded(storyRun.id, AgentRole.TESTER, outcome = "test-rejected", summary = "bevinding") }
        }

        val result = service(issueTracker, storyRuns = storyRuns, agentRuns = agentRuns).processIssue(test)

        assertTrue(result is IssueProcessResult.Errored, "verwacht Errored bij overschrijden cap, kreeg $result")
        // De error staat op de test-subtaak zelf (zoals de developer-loopback-cap) zodat de top-level
        // error-guard 'm daarna skipt.
        val error = issueTracker.lastUpdate("SF-1-sub2").values[TrackerField.ERROR] as String
        assertTrue(error.contains("Test-chain reset cap bereikt"))
        // De triage-melding mag NIET het niet-werkende developer-cap-pad beloven: de test-cap kent geen
        // resume-increment, dus enkel `Error` legen herstart niets (re-error-loop op de volgende poll).
        // Ze moet juist de wél werkende herstelpaden noemen (pauzeren / re-implement → verse story-run).
        assertFalse(
            error.contains("leeg `Error` om opnieuw te proberen"),
            "triage-melding mag het niet-werkende 'leeg Error om opnieuw te proberen'-pad niet beloven",
        )
        assertTrue(error.contains("Paused = true"), "triage-melding moet pauzeren als werkende escape noemen")
        assertTrue(error.contains("re-implement"), "triage-melding moet re-implement als werkende escape noemen")
        // Geen reset: noch de story noch de subtaak is terug naar de todo-lane gezet en er is geen
        // test-feedback weggeschreven.
        assertFalse(issueTracker.transitions.contains("SF-1" to "Open"))
        assertTrue(issueTracker.descriptionUpdates.isEmpty())
    }

    @Test
    fun `chain is idempotent after a test reset - empty phase does nothing`() {
        // Na een reset is de test-subtaak fase-leeg; de eerstvolgende poll mag geen nieuwe reset triggeren.
        val test = issue(key = "SF-1-sub2", type = "Task", subtaskType = "test", subtaskPhase = null)
        val issueTracker = FakeTrackerApi(listOf(test), parentKey = "SF-1", subtasks = listOf(test))

        val result = service(issueTracker).processIssue(test)

        assertEquals(IssueProcessResult.Skipped("SF-1-sub2", "not-started"), result)
        assertTrue(issueTracker.descriptionUpdates.isEmpty())
        assertTrue(issueTracker.transitions.isEmpty())
    }
}
