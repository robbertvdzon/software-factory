package nl.vdzon.softwarefactory.web.services

import nl.vdzon.softwarefactory.core.AgentRole
import nl.vdzon.softwarefactory.core.TrackerField
import nl.vdzon.softwarefactory.core.contracts.StoryRunRecord
import nl.vdzon.softwarefactory.core.contracts.StoryRunRepository
import nl.vdzon.softwarefactory.core.contracts.SubtaskPhase
import nl.vdzon.softwarefactory.dashboard.services.TestDecisionService
import nl.vdzon.softwarefactory.github.GitHubApi
import nl.vdzon.softwarefactory.testsupport.FakeTrackerApi
import nl.vdzon.softwarefactory.testsupport.InMemoryAgentRunRepository
import nl.vdzon.softwarefactory.testsupport.OrchestratorTestHarness
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestDecisionServiceTest : OrchestratorTestHarness() {
    private val test = issue("SF-2", type = "Task", subtaskType = "test", subtaskPhase = "test-decision-needed")
    private val tracker = FakeTrackerApi(listOf(test), parentKey = "SF-1")
    private val storyRuns = mock(StoryRunRepository::class.java)
    private val agentRuns = InMemoryAgentRunRepository()
    private val github = mock(GitHubApi::class.java)
    private val sha = "a".repeat(40)
    private val decisions = TestDecisionService(tracker, storyRuns, agentRuns, github)

    init {
        `when`(storyRuns.latestFor("SF-1")).thenReturn(StoryRunRecord(1, "SF-1", "repo", branchName = "ai/SF-1"))
        `when`(github.latestCommitSha("repo", "ai/SF-1")).thenReturn(sha)
        agentRuns.addEnded(1, AgentRole.TESTER, "success", "Integratietests groen; livecontrole pas na merge.",
            subtaskKey = test.key, resultPhase = "test-decision-needed", checkoutCommitSha = sha)
    }

    @Test
    fun `override records reason run and tested commit without changing the report`() {
        decisions.decide(test.copy(fields = test.fields.copy(paused = true)), SubtaskPhase.TEST_APPROVED, "Integratiebewijs volstaat")
        assertEquals("test-approved", tracker.lastUpdate(test.key).values[TrackerField.SUBTASK_PHASE])
        assertEquals(false, tracker.lastUpdate(test.key).values[TrackerField.PAUSED])
        val audit = tracker.postedComments.single().second
        assertTrue(audit.contains("Integratiebewijs volstaat"))
        assertTrue(audit.contains(sha))
        assertTrue(audit.contains("Testrun: 1"))
        assertTrue(agentRuns.latestForRole(1, AgentRole.TESTER)!!.summaryText!!.contains("livecontrole pas na merge"))
    }

    @Test
    fun `changed or unreachable branch cannot be overridden`() {
        for (head in listOf("b".repeat(40), null)) {
            `when`(github.latestCommitSha("repo", "ai/SF-1")).thenReturn(head)
            assertThrows<IllegalStateException> { decisions.decide(test, SubtaskPhase.TEST_APPROVED, "Akkoord") }
        }
        assertTrue(tracker.updates.isEmpty())
        assertTrue(tracker.postedComments.isEmpty())
    }

    @Test
    fun `missing reason and stale page do not advance the story`() {
        assertThrows<IllegalArgumentException> { decisions.decide(test, SubtaskPhase.TEST_APPROVED, " ") }
        val stale = test.copy(fields = test.fields.copy(subtaskPhase = "testing"))
        assertThrows<IllegalArgumentException> { decisions.decide(stale, SubtaskPhase.TEST_APPROVED, "Akkoord") }
        assertTrue(tracker.updates.isEmpty())
    }

    @Test
    fun `repair carries the explicit instruction and does not approve evidence`() {
        decisions.decide(test, SubtaskPhase.TEST_REPAIR_REQUESTED, "Maak de klok van de mock instelbaar")
        assertEquals("test-repair-requested", tracker.lastUpdate(test.key).values[TrackerField.SUBTASK_PHASE])
        assertTrue(tracker.postedComments.single().second.contains("Maak de klok van de mock instelbaar"))
    }
}
