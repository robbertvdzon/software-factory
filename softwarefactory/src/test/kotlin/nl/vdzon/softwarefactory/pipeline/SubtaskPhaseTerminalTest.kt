package nl.vdzon.softwarefactory.pipeline

import nl.vdzon.softwarefactory.core.contracts.SubtaskPhase
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubtaskPhaseTerminalTest {

    @Test
    fun `MERGE_APPROVED is terminal`() {
        assertTrue(SubtaskPhase.MERGE_APPROVED.isTerminal)
    }

    @Test
    fun `DEPLOY_APPROVED is terminal`() {
        assertTrue(SubtaskPhase.DEPLOY_APPROVED.isTerminal)
    }

    @Test
    fun `MERGING is not terminal`() {
        assertFalse(SubtaskPhase.MERGING.isTerminal)
    }

    @Test
    fun `DEPLOYING is not terminal`() {
        assertFalse(SubtaskPhase.DEPLOYING.isTerminal)
    }

    @Test
    fun `DEPLOY_FAILED is terminal`() {
        assertTrue(SubtaskPhase.DEPLOY_FAILED.isTerminal)
    }

    @Test
    fun `existing terminal phases still terminal`() {
        assertTrue(SubtaskPhase.REVIEW_APPROVED.isTerminal)
        assertTrue(SubtaskPhase.TEST_APPROVED.isTerminal)
        assertTrue(SubtaskPhase.SUMMARY_APPROVED.isTerminal)
        assertTrue(SubtaskPhase.MANUAL_ACTION_DONE.isTerminal)
    }

    @Test
    fun `HOTFIX_APPROVED is terminal maar DEVELOPMENT_APPROVED niet`() {
        // SF-1959 — de hotfix-keten eindigt op een eigen terminale fase. DEVELOPMENT_APPROVED mag
        // NIET terminaal worden: dat is in een development-subtaak juist de developer -> reviewer-stap.
        assertTrue(SubtaskPhase.HOTFIX_APPROVED.isTerminal)
        assertFalse(SubtaskPhase.DEVELOPMENT_APPROVED.isTerminal)
        assertFalse(SubtaskPhase.DEVELOPED.isTerminal)
    }

    @Test
    fun `fromTracker parses new phases`() {
        assert(SubtaskPhase.fromTracker("merge-approved") == SubtaskPhase.MERGE_APPROVED)
        assert(SubtaskPhase.fromTracker("deploy-approved") == SubtaskPhase.DEPLOY_APPROVED)
        assert(SubtaskPhase.fromTracker("merging") == SubtaskPhase.MERGING)
        assert(SubtaskPhase.fromTracker("deploying") == SubtaskPhase.DEPLOYING)
        assert(SubtaskPhase.fromTracker("deploy-failed") == SubtaskPhase.DEPLOY_FAILED)
        assert(SubtaskPhase.fromTracker("hotfix-approved") == SubtaskPhase.HOTFIX_APPROVED)
    }
}
