package nl.vdzon.softwarefactory.core.contracts

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/** SF-1261 — as 2 (Goedkeuring): [HumanActionPolicy.autoApproveActive] op de nieuwe [ApprovalMode]-as. */
class HumanActionPolicyTest {

    @Test
    fun `story gebruikt het eigen approvalMode-veld`() {
        val story = story(approvalMode = ApprovalMode.EVERY_STEP.trackerValue)

        assertFalse(HumanActionPolicy.autoApproveActive(story) { fail("parent-lookup mag niet gebeuren voor een story") })
    }

    @Test
    fun `subtaak erft approvalMode van de parent-story`() {
        val subtask = subtask()
        val parentFields = fields(approvalMode = ApprovalMode.AUTOMATIC.trackerValue)

        assertTrue(HumanActionPolicy.autoApproveActive(subtask) { parentFields })
    }

    @Test
    fun `subtaak met elke-stap-parent wacht op een mens`() {
        val subtask = subtask()
        val parentFields = fields(approvalMode = ApprovalMode.EVERY_STEP.trackerValue)

        assertFalse(HumanActionPolicy.autoApproveActive(subtask) { parentFields })
    }

    @Test
    fun `subtaak met falende parent-lookup is fail-safe (geen auto-approve)`() {
        // Regressietest: een lookup-falen mag NIET terugvallen op het eigen (subtaak-)veld, want
        // dat is altijd de class-default AUTOMATIC en zou de goedkeuringsgate fail-open maken.
        val subtask = subtask()

        assertFalse(HumanActionPolicy.autoApproveActive(subtask) { null })
    }

    private fun story(approvalMode: String): TrackerIssue =
        TrackerIssue(
            key = "SF-1",
            summary = "Story",
            status = "",
            comments = emptyList(),
            fields = fields(approvalMode = approvalMode, type = "User Story"),
        )

    private fun subtask(): TrackerIssue =
        TrackerIssue(
            key = "SF-2",
            summary = "Subtaak",
            status = "",
            comments = emptyList(),
            fields = fields(type = "Task"),
        )

    private fun fields(approvalMode: String = ApprovalMode.AUTOMATIC.trackerValue, type: String? = null): TrackerIssueFields =
        TrackerIssueFields(
            targetRepo = null,
            aiPhase = null,
            aiLevel = null,
            aiTokenBudget = null,
            aiTokensUsed = null,
            agentStartedAt = null,
            paused = false,
            approvalMode = approvalMode,
            error = null,
            type = type,
        )
}
