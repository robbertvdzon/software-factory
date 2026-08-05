package nl.vdzon.softwarefactory.pipeline

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.IssueProcessResult
import nl.vdzon.softwarefactory.core.contracts.SubtaskPhase
import nl.vdzon.softwarefactory.core.contracts.SubtaskType
import nl.vdzon.softwarefactory.pipeline.service.StoryRefinementCoordinator
import nl.vdzon.softwarefactory.testsupport.FakeAgentRuntime
import nl.vdzon.softwarefactory.testsupport.FakeTrackerApi
import nl.vdzon.softwarefactory.testsupport.OrchestratorTestHarness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SF-1959 — de reviewerloze hotfix-subtaak-pipeline in [SubtaskExecutionCoordinator]:
 * `start → developing → developed → hotfix-approved`, met `development-rejected` als loopback en
 * zonder enige reviewer- of goedkeuringsstap. De story-niveau routing (`Story Phase = start` met
 * `hotfix = true`) staat in [StoryRefinementCoordinatorAutoStartTest].
 */
class HotfixSubtaskFlowTest : OrchestratorTestHarness() {

    @Test
    fun `hotfix-subtaak op start dispatcht de DEVELOPER`() {
        val sub = hotfixSubtask(SubtaskPhase.START.trackerValue)
        val tracker = FakeTrackerApi(listOf(sub), parentKey = STORY, subtasks = listOf(sub), parentIssue = issue(STORY))
        val runtime = FakeAgentRuntime(now)

        val result = service(tracker, runtime = runtime).processIssue(sub)

        assertTrue(result is IssueProcessResult.Dispatched, "Verwacht Dispatched, kreeg $result")
        assertEquals(AgentRole.DEVELOPER, (result as IssueProcessResult.Dispatched).role)
        assertEquals(AgentRole.DEVELOPER, runtime.dispatches.single().role)
    }

    @Test
    fun `hotfix-subtaak op developed gaat direct naar hotfix-approved, ook bij goedkeuring elke-stap`() {
        // ApprovalMode van de parent staat op `elke-stap` (autoApprove = false): een DEVELOPMENT-subtaak
        // zou hier op een mens wachten, een hotfix-subtaak nadrukkelijk niet.
        val sub = hotfixSubtask(SubtaskPhase.DEVELOPED.trackerValue)
        val tracker = FakeTrackerApi(
            listOf(sub),
            parentKey = STORY,
            subtasks = listOf(sub),
            parentIssue = issue(STORY, autoApprove = false),
        )
        val runtime = FakeAgentRuntime(now)

        val result = service(tracker, runtime = runtime).processIssue(sub)

        assertTrue(result is IssueProcessResult.Recovered, "Verwacht Recovered, kreeg $result")
        assertEquals(
            SubtaskPhase.HOTFIX_APPROVED.trackerValue,
            tracker.lastUpdate(sub.key).values[TrackerField.SUBTASK_PHASE],
        )
        assertTrue(runtime.dispatches.isEmpty(), "geen reviewer (of andere agent) op een hotfix-subtaak")
    }

    @Test
    fun `hotfix-subtaak op development-rejected loopt terug naar de developer met de eigen diagnose`() {
        val sub = hotfixSubtask(SubtaskPhase.DEVELOPMENT_REJECTED.trackerValue)
        val tracker = FakeTrackerApi(listOf(sub), parentKey = STORY, subtasks = listOf(sub), parentIssue = issue(STORY))
        val runtime = FakeAgentRuntime(now)

        val result = service(tracker, runtime = runtime).processIssue(sub)

        assertTrue(result is IssueProcessResult.Dispatched, "Verwacht Dispatched, kreeg $result")
        assertEquals(AgentRole.DEVELOPER, runtime.dispatches.single().role)
    }

    @Test
    fun `hotfix-subtaak op hotfix-approved zet de keten door en gaat naar Done`() {
        val hotfix = hotfixSubtask(SubtaskPhase.HOTFIX_APPROVED.trackerValue)
        val merge = issue("$STORY-merge", type = "Task", subtaskType = "merge", subtaskPhase = null)
        val tracker = FakeTrackerApi(
            listOf(hotfix),
            parentKey = STORY,
            subtasks = listOf(hotfix, merge),
            parentIssue = issue(STORY),
        )

        val result = service(tracker).processIssue(hotfix)

        assertTrue(result is IssueProcessResult.Chained, "Verwacht Chained, kreeg $result")
        assertEquals(merge.key, (result as IssueProcessResult.Chained).nextSubtaskKey)
        assertTrue(tracker.transitions.contains(hotfix.key to "Done"))
        assertEquals(
            SubtaskPhase.START.trackerValue,
            tracker.lastUpdate(merge.key).values[TrackerField.SUBTASK_PHASE],
        )
    }

    @Test
    fun `hotfix-subtaak zonder fase doet niets`() {
        val sub = hotfixSubtask(null)
        val tracker = FakeTrackerApi(listOf(sub), parentKey = STORY, subtasks = listOf(sub), parentIssue = issue(STORY))

        val result = service(tracker).processIssue(sub)

        assertTrue(result is IssueProcessResult.Skipped, "Verwacht Skipped, kreeg $result")
        assertEquals("not-started", (result as IssueProcessResult.Skipped).reason)
        assertNull(tracker.updates[sub.key])
    }

    @Test
    fun `de hotfix-keten is exact hotfix, merge, deploy`() {
        assertEquals(
            listOf(SubtaskType.HOTFIX, SubtaskType.MERGE, SubtaskType.DEPLOY),
            StoryRefinementCoordinator.HOTFIX_CHAIN_SPECS.map { it.type },
        )
        // Vaste titels: de materialisatie is idempotent op titel.
        assertEquals(
            listOf("Hotfix uitvoeren", "Merge story-branch", "Deploy naar productie"),
            StoryRefinementCoordinator.HOTFIX_CHAIN_SPECS.map { it.title },
        )
    }

    @Test
    fun `subtaaktype en terminale fase zijn uit de tracker leesbaar`() {
        assertEquals(SubtaskType.HOTFIX, SubtaskType.fromTracker("hotfix"))
        assertEquals(SubtaskPhase.HOTFIX_APPROVED, SubtaskPhase.fromTracker("hotfix-approved"))
        assertTrue(SubtaskPhase.HOTFIX_APPROVED.isTerminal, "anders zet advanceSubtaskChain de keten nooit door")
    }

    private fun hotfixSubtask(phase: String?) =
        issue("$STORY-hotfix", type = "Task", subtaskType = SubtaskType.HOTFIX.trackerValue, subtaskPhase = phase)

    private companion object {
        const val STORY = "SF-1959"
    }
}
